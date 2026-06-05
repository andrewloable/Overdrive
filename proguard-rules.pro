# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==================== BYD SDK Stubs (reflection) ====================
# These are compile-time stubs - real classes come from system at runtime
-keep class android.hardware.bydauto.** { *; }
-keep class android.hardware.BmmCamera** { *; }

# ==================== Daemon Entry Points (app_process) ====================
# ONLY keep class names and main() - everything else gets obfuscated
# This hides internal method names like whitelistViaBruteForce -> a()

-keep class com.loabletech.bladewatch.daemon.CameraDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.loabletech.bladewatch.daemon.SentryDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.loabletech.bladewatch.daemon.AccSentryDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.loabletech.bladewatch.daemon.TelegramBotDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.loabletech.bladewatch.daemon.GlobalProxyDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.loabletech.bladewatch.byd.BydEventDaemon {
    public static void main(java.lang.String[]);
}

# Keep listener classes that extend BYD SDK (method names must match parent)
-keep class com.loabletech.bladewatch.daemon.AccSentryDaemon$AccListener {
    <methods>;
}

# ==================== Daemon Support Classes ====================
# These are used by daemons but don't need full preservation

# Safe - Pure Java AES decryption (replaced native NativeSecrets)
-keep class com.loabletech.bladewatch.daemon.proxy.Safe {
    public static java.lang.String s(java.lang.String);
}

# S - Short alias for string decryption (used throughout daemon code)
-keep class com.loabletech.bladewatch.daemon.proxy.S {
    public static java.lang.String d(java.lang.String);
}

# Keep class names for daemon subpackages (for Class.forName if used internally)
# but allow method/field renaming
-keepnames class com.loabletech.bladewatch.daemon.** { }
-keepnames class com.loabletech.bladewatch.byd.** { }
-keepnames class com.loabletech.bladewatch.camera.** { }
-keepnames class com.loabletech.bladewatch.server.** { }
-keepnames class com.loabletech.bladewatch.encoding.** { }
-keepnames class com.loabletech.bladewatch.stream.** { }
-keepnames class com.loabletech.bladewatch.monitor.** { }

# ==================== Logging (keep class names + DaemonLogConfig for runtime control) ====================
-keepnames class com.loabletech.bladewatch.logging.** { }
-keep class com.loabletech.bladewatch.logging.DaemonLogConfig { *; }

# ==================== Native Methods (all classes) ====================
# JNI method names must match native function signatures exactly
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==================== Dadb (ADB client) ====================
-keep class dadb.** { *; }
-dontwarn dadb.**

# ==================== ZXing (QR codes) ====================
-keep class com.google.zxing.** { *; }

# ==================== Eclipse Paho MQTT ====================
# Paho uses java.util.logging internally and loads logging resource bundles
# by class name via reflection. ProGuard strips these, causing
# MissingResourceException at connect time.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep class org.eclipse.paho.client.mqttv3.logging.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
# v5 client used by BydCloudMqttSubscriber for BYD's EMQ broker — same
# reflection-based logging-bundle loader as v3. The package roots differ
# from v3 (mqttv5 sits directly under org.eclipse.paho, no .client. prefix
# in the package after that), so the v3 rules above don't cover it.
-keep class org.eclipse.paho.mqttv5.** { *; }
-keep class org.eclipse.paho.mqttv5.client.logging.** { *; }
-dontwarn org.eclipse.paho.mqttv5.**

# ==================== RTMP client ====================
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**

# ==================== TensorFlow Lite ====================
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**
-dontwarn org.tensorflow.lite.gpu.**

# AI detection classes - keep class names but allow method obfuscation
# Detection data class needs field names for any serialization
-keep class com.loabletech.bladewatch.ai.Detection { *; }
-keepnames class com.loabletech.bladewatch.ai.** { }

# ==================== Kotlin & AndroidX ====================
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
# AndroidX - only keep what's needed, not everything
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.work.** { *; }
-keep class androidx.navigation.** { *; }
-keepnames class androidx.** { }
-dontwarn androidx.**

# ==================== App Components (declared in AndroidManifest) ====================
# R8 auto-keeps these, but explicit rules for safety
-keep class com.loabletech.bladewatch.BladeWatchApplication { *; }
-keep class com.loabletech.bladewatch.ui.MainActivity { *; }
-keep class com.loabletech.bladewatch.ui.LocationStarterActivity { *; }
-keep class com.loabletech.bladewatch.BlockerActivity { *; }
-keep class com.loabletech.bladewatch.receiver.BootReceiver { *; }
-keep class com.loabletech.bladewatch.receiver.LocationBootReceiver { *; }
-keep class com.loabletech.bladewatch.services.LocationSidecarService { *; }

# ==================== App Packages (allow obfuscation) ====================
# Keep class names for debugging but obfuscate methods/fields
-keepnames class com.loabletech.bladewatch.auth.** { }
-keepnames class com.loabletech.bladewatch.bridge.** { }
-keepnames class com.loabletech.bladewatch.byd.** { }
-keepnames class com.loabletech.bladewatch.client.** { }
-keepnames class com.loabletech.bladewatch.config.** { }
-keepnames class com.loabletech.bladewatch.launcher.** { }
-keepnames class com.loabletech.bladewatch.manager.** { }
-keepnames class com.loabletech.bladewatch.proximity.** { }
-keepnames class com.loabletech.bladewatch.recording.** { }
-keepnames class com.loabletech.bladewatch.service.** { }
-keepnames class com.loabletech.bladewatch.shell.** { }
-keepnames class com.loabletech.bladewatch.storage.** { }
-keepnames class com.loabletech.bladewatch.streaming.** { }
-keepnames class com.loabletech.bladewatch.surveillance.** { }
-keepnames class com.loabletech.bladewatch.telemetry.** { }
# TelemetrySnapshot fields accessed by overlay renderer — keep from renaming
-keepclassmembers class com.loabletech.bladewatch.telemetry.TelemetrySnapshot { public *; }
-keepnames class com.loabletech.bladewatch.abrp.** { }
-keepnames class com.loabletech.bladewatch.telegram.** { }
-keepnames class com.loabletech.bladewatch.ui.** { }
-keepnames class com.loabletech.bladewatch.util.** { }
-keepnames class com.loabletech.bladewatch.webrtc.** { }

# ==================== Serialization & Parcelable ====================
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ==================== H2 Database & JDBC Fixes (COMPLETE) ====================

# 1. Ignore Desktop UI (AWT/Swing)
-dontwarn java.awt.**
-dontwarn java.beans.**

# 2. Ignore Java Management Extensions (JMX)
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# 3. Ignore Servlets (Web Server features) - MISSING IN PREVIOUS
-dontwarn javax.servlet.**
-dontwarn jakarta.servlet.**

# 4. Ignore OSGi (Module System) - MISSING IN PREVIOUS
-dontwarn org.osgi.**

# 5. Ignore Advanced SQL/Transaction APIs (XA/JDBCType)
-dontwarn java.sql.**
-dontwarn javax.sql.**
-dontwarn javax.transaction.**
-dontwarn javax.naming.**
-dontwarn javax.security.**

# 6. Ignore GIS/Geometry Support (JTS)
-dontwarn org.locationtech.jts.**

# 7. Ignore Lucene (Full Text Search)
-dontwarn org.apache.lucene.**

# 8. Ignore other missing standard Java extensions
-dontwarn javax.xml.stream.**
-dontwarn javax.xml.transform.**
-dontwarn javax.tools.**
-dontwarn javax.script.**

# 9. Keep H2 functional
-keep class org.h2.** { *; }

# ==================== Log Stripping (Release Builds) ====================
#
# CONTROLLED BY: com.loabletech.bladewatch.logging.DaemonLogConfig
#
# DaemonLogger stripping is in a SEPARATE file: proguard-rules-strip-logs.pro
# build.gradle.kts auto-detects DaemonLogConfig flags:
#   - All flags false (default) → includes strip-logs → DaemonLogger calls stripped
#   - Any flag true            → excludes strip-logs → DaemonLogger calls kept
#
# Console/stdout stripping below is ALWAYS active (even in debug-logging builds).
# ====================================================================

# Always strip android.util.Log (logcat) — security: no log strings in production
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
    public static String getStackTraceString(...);
    public static int println(...);
}

# Always strip System.out/err (daemon stdout)
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
    public void printf(...);
}

# Keep DaemonLogConfig (R8 needs it for runtime checks when logging is enabled)
-keep class com.loabletech.bladewatch.logging.DaemonLogConfig { *; }

# Keep DaemonLogger class structure
-keep class com.loabletech.bladewatch.logging.DaemonLogger { *; }
-keep class com.loabletech.bladewatch.logging.DaemonLogger$Config { *; }
-keep class com.loabletech.bladewatch.logging.DaemonLogger$Level { *; }

# Strip Kotlin null checks (minor optimization — always safe to strip)
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
}

# ==================== General Safety ====================
-dontwarn sun.misc.Unsafe
-dontwarn java.nio.file.**
-dontwarn java.util.spi.ToolProvider