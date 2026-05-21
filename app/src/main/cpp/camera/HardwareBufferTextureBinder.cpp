// HardwareBufferTextureBinder — JNI bridge for AHardwareBuffer → EGLImage →
// GL_TEXTURE_EXTERNAL_OES binding. Required for the ImageReader-based zero-copy
// camera path that bypasses SurfaceFlinger throttling on the SurfaceTexture
// consumer (see CAMERA_FPS_INVESTIGATION.md).
//
// Standard Android Java SDK does not expose eglCreateImageKHR /
// glEGLImageTargetTexture2DOES, so this small JNI bridge resolves them via
// eglGetProcAddress and exposes two methods to Java:
//
//   - probeExtensionsNative(): returns a string describing whether the EGL
//     extensions we need are available on the current display.
//   - bindHardwareBufferToTextureNative(HardwareBuffer, int textureId): wraps
//     the gralloc buffer as an EGLImage and binds it to the supplied OES
//     texture. Caller must hold a current EGL context on the calling thread.
//
// Lifetime: a fresh EGLImage is created and bound per call. After
// glEGLImageTargetTexture2DOES, the texture retains an internal ref to the
// gralloc buffer, so we can immediately destroy the EGLImage. The Java side
// is responsible for closing the Image / HardwareBuffer once the GL bind has
// completed.

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
// NOTE: <android/hardware_buffer_jni.h> declares
// AHardwareBuffer_fromHardwareBuffer with __INTRODUCED_IN(26). minSdk on this
// project is 28, so the symbol is always present at runtime — but the NDK
// build targets the minSdk floor and the availability checker rejects direct
// calls. We resolve via dlsym to keep the build clean across NDK versions.
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <cstring>
#include <mutex>
#include <string>

#define TAG "HwBufferBinder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Function pointer types for runtime extension resolution.
typedef EGLClientBuffer (EGLAPIENTRYP PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)(
    const struct AHardwareBuffer* buffer);
typedef EGLImageKHR (EGLAPIENTRYP PFNEGLCREATEIMAGEKHRPROC)(
    EGLDisplay dpy, EGLContext ctx, EGLenum target,
    EGLClientBuffer buffer, const EGLint* attrib_list);
typedef EGLBoolean (EGLAPIENTRYP PFNEGLDESTROYIMAGEKHRPROC)(
    EGLDisplay dpy, EGLImageKHR image);
typedef void (GL_APIENTRYP PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)(
    GLenum target, GLeglImageOES image);

// Signature of AHardwareBuffer_fromHardwareBuffer; resolved via dlsym so the
// build doesn't need NDK API 26+.
typedef AHardwareBuffer* (*PFN_AHB_FROM_HARDWARE_BUFFER)(JNIEnv*, jobject);

struct ExtFns {
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = nullptr;
    PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = nullptr;
    PFN_AHB_FROM_HARDWARE_BUFFER ahbFromHardwareBuffer = nullptr;
    bool resolved = false;
    bool ok = false;
};

ExtFns g_fns;
std::once_flag g_resolveOnce;

void resolveExtensions() {
    std::call_once(g_resolveOnce, []() {
        g_fns.eglGetNativeClientBufferANDROID =
            (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) eglGetProcAddress(
                "eglGetNativeClientBufferANDROID");
        g_fns.eglCreateImageKHR =
            (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
        g_fns.eglDestroyImageKHR =
            (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");
        g_fns.glEGLImageTargetTexture2DOES =
            (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) eglGetProcAddress(
                "glEGLImageTargetTexture2DOES");

        // libnativewindow.so hosts AHardwareBuffer_fromHardwareBuffer on
        // Android 8+. RTLD_DEFAULT works too on most builds since the symbol
        // is exposed by the bionic dynamic linker — fall back to that if the
        // explicit dlopen fails.
        void* lib = dlopen("libnativewindow.so", RTLD_NOW);
        if (lib != nullptr) {
            g_fns.ahbFromHardwareBuffer =
                (PFN_AHB_FROM_HARDWARE_BUFFER) dlsym(
                    lib, "AHardwareBuffer_fromHardwareBuffer");
        }
        if (g_fns.ahbFromHardwareBuffer == nullptr) {
            g_fns.ahbFromHardwareBuffer =
                (PFN_AHB_FROM_HARDWARE_BUFFER) dlsym(
                    RTLD_DEFAULT, "AHardwareBuffer_fromHardwareBuffer");
        }

        g_fns.ok = g_fns.eglGetNativeClientBufferANDROID
                && g_fns.eglCreateImageKHR
                && g_fns.eglDestroyImageKHR
                && g_fns.glEGLImageTargetTexture2DOES
                && g_fns.ahbFromHardwareBuffer;
        if (!g_fns.ok) {
            LOGW("symbol resolution failed: nativeClientBuf=%p createImage=%p "
                 "destroyImage=%p targetTexture=%p ahbFromHwb=%p",
                 g_fns.eglGetNativeClientBufferANDROID,
                 g_fns.eglCreateImageKHR,
                 g_fns.eglDestroyImageKHR,
                 g_fns.glEGLImageTargetTexture2DOES,
                 g_fns.ahbFromHardwareBuffer);
        }
        g_fns.resolved = true;
    });
}

bool stringContains(const char* hay, const char* needle) {
    return hay && needle && std::strstr(hay, needle) != nullptr;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_overdrive_app_camera_HardwareBufferTextureBinder_probeExtensionsNative(
    JNIEnv* env, jclass /*clazz*/) {
    resolveExtensions();

    EGLDisplay dpy = eglGetCurrentDisplay();
    const char* eglExt = (dpy != EGL_NO_DISPLAY)
        ? eglQueryString(dpy, EGL_EXTENSIONS) : nullptr;
    const char* glExt = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));

    bool hasNativeClientBuf = stringContains(eglExt,
        "EGL_ANDROID_get_native_client_buffer");
    bool hasImageNativeBuf = stringContains(eglExt,
        "EGL_ANDROID_image_native_buffer");
    bool hasImageBase = stringContains(eglExt, "EGL_KHR_image_base");
    bool hasOesEglImage = stringContains(glExt, "GL_OES_EGL_image_external");

    std::string report;
    report += "fnsResolved=";        report += g_fns.ok ? "true" : "false";
    report += " ahbFromHwb=";        report += g_fns.ahbFromHardwareBuffer ? "y" : "n";
    report += " EGL_KHR_image_base=";          report += hasImageBase ? "y" : "n";
    report += " EGL_ANDROID_get_native_client_buffer="; report += hasNativeClientBuf ? "y" : "n";
    report += " EGL_ANDROID_image_native_buffer=";      report += hasImageNativeBuf ? "y" : "n";
    report += " GL_OES_EGL_image_external=";   report += hasOesEglImage ? "y" : "n";
    report += " currentDisplay=";    report += (dpy != EGL_NO_DISPLAY) ? "yes" : "no";

    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_HardwareBufferTextureBinder_bindHardwareBufferToTextureNative(
    JNIEnv* env, jclass /*clazz*/, jobject jHwBuffer, jint textureId) {
    if (jHwBuffer == nullptr) {
        LOGE("bindHardwareBufferToTexture: null HardwareBuffer");
        return JNI_FALSE;
    }
    resolveExtensions();
    if (!g_fns.ok) {
        LOGE("bindHardwareBufferToTexture: extension fns not available");
        return JNI_FALSE;
    }

    AHardwareBuffer* ahb = g_fns.ahbFromHardwareBuffer(env, jHwBuffer);
    if (ahb == nullptr) {
        LOGE("AHardwareBuffer_fromHardwareBuffer returned null");
        return JNI_FALSE;
    }

    EGLClientBuffer clientBuf = g_fns.eglGetNativeClientBufferANDROID(ahb);
    if (clientBuf == nullptr) {
        LOGE("eglGetNativeClientBufferANDROID returned null");
        return JNI_FALSE;
    }

    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) {
        LOGE("no current EGL display on calling thread");
        return JNI_FALSE;
    }

    static const EGLint kAttrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };
    EGLImageKHR image = g_fns.eglCreateImageKHR(
        dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuf, kAttrs);
    if (image == EGL_NO_IMAGE_KHR) {
        LOGE("eglCreateImageKHR failed err=0x%x", eglGetError());
        return JNI_FALSE;
    }

    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(textureId));
    g_fns.glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, image);
    GLenum glErr = glGetError();

    // Texture retains an internal ref to the gralloc buffer; the EGLImage
    // wrapper itself is no longer needed.
    g_fns.eglDestroyImageKHR(dpy, image);

    if (glErr != GL_NO_ERROR) {
        LOGE("glEGLImageTargetTexture2DOES failed glErr=0x%x", glErr);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
