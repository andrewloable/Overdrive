import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun hasExpectedSha256(file: File, expectedSha256: String): Boolean =
    file.exists() && sha256Hex(file).equals(expectedSha256, ignoreCase = true)

fun verifySha256(file: File, expectedSha256: String, label: String) {
    val actual = sha256Hex(file)
    if (!actual.equals(expectedSha256, ignoreCase = true)) {
        throw org.gradle.api.GradleException(
            "$label checksum mismatch. Expected $expectedSha256, got $actual"
        )
    }
}

fun org.gradle.api.Project.downloadVerified(
    url: String,
    dest: File,
    expectedSha256: String,
    label: String
) {
    dest.parentFile.mkdirs()
    ant.invokeMethod("get", mapOf("src" to url, "dest" to dest.absolutePath))
    if (!dest.exists() || dest.length() == 0L) {
        throw org.gradle.api.GradleException("$label download failed: $url")
    }
    verifySha256(dest, expectedSha256, label)
}

fun org.gradle.api.Project.ensureDownloadedVerified(
    url: String,
    dest: File,
    expectedSha256: String,
    label: String
) {
    if (dest.exists()) {
        if (hasExpectedSha256(dest, expectedSha256)) return
        println("$label exists but checksum changed; redownloading")
        dest.delete()
    }
    downloadVerified(url, dest, expectedSha256, label)
}

val openh264Version = "2.6.0"
val openh264ArchiveSha256 = "c702d68c9c8db492a43c1d73a497cea5f31ae5d23e330dcb13bd28cab1dbbf2a"
val openh264LibrarySha256 = "4d9bc54d2d38e53eb7bd551ec61acb8ad8320d8b957bda751cfdbdcbfabc3b07"
val openh264HeaderSha256 = mapOf(
    "codec_api.h" to "21f29b20c24f7c7946f2e243d0bc2532fb3542f6c28af338209477e70d9036c9",
    "codec_app_def.h" to "a40581a24263866dca19911928f7bc4eb354ff78d9dd56dbf0f55fc4fd923726",
    "codec_def.h" to "f974d269b5935e8dc7265b8bfc02f60e5185b4d6165d30541d2758a4506f1979",
    "codec_ver.h" to "9a241e20b7c9221a5786cccd9eae3afed91afba3525b5b9b16c2101976516f94",
)

// Auto-download OpenH264 from Cisco's official binary releases
tasks.register("downloadOpenH264") {
    val openh264Dir = file("src/main/cpp/openh264")
    
    doLast {
        // Cisco's official binary URLs - only arm64-v8a for BYD cars
        val abiMap = mapOf(
            "arm64-v8a" to "https://ciscobinary.openh264.org/libopenh264-${openh264Version}-android-arm64.8.so.bz2"
            // Removed armeabi-v7a to reduce APK size
        )
        
        abiMap.forEach { (abi, url) ->
            val libDir = file("${openh264Dir}/lib/${abi}")
            libDir.mkdirs()
            
            val soFile = file("${libDir}/libopenh264.so")
            if (soFile.exists() && hasExpectedSha256(soFile, openh264LibrarySha256)) {
                println("✓ OpenH264 verified for ${abi}")
            } else {
                if (soFile.exists()) {
                    println("OpenH264 ${abi} exists but checksum changed; redownloading")
                    soFile.delete()
                }
                println("Downloading OpenH264 ${openh264Version} for ${abi}...")
                val bzFile = file("${libDir}/temp.bz2")
                val extractedFile = file("${libDir}/temp")
                if (extractedFile.exists()) extractedFile.delete()

                project.downloadVerified(url, bzFile, openh264ArchiveSha256, "OpenH264 ${abi} archive")
                ant.invokeMethod("bunzip2", mapOf("src" to bzFile.absolutePath))
                if (!extractedFile.renameTo(soFile)) {
                    throw org.gradle.api.GradleException("Failed to install OpenH264 for ${abi}")
                }
                verifySha256(soFile, openh264LibrarySha256, "OpenH264 ${abi} library")
                println("✓ OpenH264 downloaded and verified for ${abi}")
            }
        }
        
        // Download headers from Cisco's GitHub
        val includeDir = file("${openh264Dir}/include/wels")
        includeDir.mkdirs()
        listOf("codec_api.h", "codec_app_def.h", "codec_def.h", "codec_ver.h").forEach { h ->
            val f = file("${includeDir}/${h}")
            project.ensureDownloadedVerified(
                "https://raw.githubusercontent.com/cisco/openh264/v${openh264Version}/codec/api/wels/${h}",
                f,
                openh264HeaderSha256.getValue(h),
                "OpenH264 header ${h}"
            )
        }
    }
}

tasks.matching { it.name.contains("CMake") || it.name.contains("ExternalNative") }.configureEach {
    dependsOn("downloadOpenH264", "downloadOpenCV")
}

// OpenCV-mobile version for surveillance module (minimal build, ~3MB vs ~20MB)
// https://github.com/nihui/opencv-mobile
val opencvMobileTag = "v31"
val opencvMobileVersion = "4.10.0"
val opencvMobileArchiveSha256 = "1fd97600f3ed7a0ea17fbd6d009bb9902eec9d968e7b74d5141285b6d7ce3412"
val opencvMobileStaticLibSha256 = mapOf(
    "libopencv_core.a" to "a9ceca2c36c3a44fe245870cd75a32bd0f33bbb0ef8513e021fe79cfe1f8704d",
    "libopencv_imgproc.a" to "13b2237e250f5399162d5b1a3ce141f8307c4d484d83c8e14325f87bee32339f",
    "libopencv_video.a" to "0c438e88067b5b24c477e1e23d7be5afb91fa6cf10a5061530aa9c3fcb62350c",
)
tasks.register("downloadOpenCV") {
    val opencvDir = file("src/main/cpp/opencv")
    
    doLast {
        val libDir = file("${opencvDir}/lib/arm64-v8a")
        libDir.mkdirs()
        val includeDir = file("${opencvDir}/include")
        
        val staticLibsVerified = opencvMobileStaticLibSha256.all { (name, sha256) ->
            hasExpectedSha256(file("${libDir}/${name}"), sha256)
        }
        
        if (!staticLibsVerified) {
            println("Downloading opencv-mobile ${opencvMobileVersion} for Android...")
            
            // Correct URL format: /releases/download/vVERSION/
            val zipUrl = "https://github.com/nihui/opencv-mobile/releases/download/${opencvMobileTag}/opencv-mobile-${opencvMobileVersion}-android.zip"
            val zipFile = file("${opencvDir}/opencv-mobile-android.zip")
            
            try {
                // Download opencv-mobile
                println("Downloading from: $zipUrl")
                project.downloadVerified(zipUrl, zipFile, opencvMobileArchiveSha256, "opencv-mobile archive")
                
                if (zipFile.exists() && zipFile.length() > 100000) {
                    println("Extracting opencv-mobile (${zipFile.length() / 1024 / 1024}MB)...")
                    
                    ant.invokeMethod("unzip", mapOf(
                        "src" to zipFile.absolutePath,
                        "dest" to opencvDir.absolutePath
                    ))
                    
                    // List extracted contents for debugging
                    opencvDir.listFiles()?.forEach { println("  Found: ${it.name}") }
                    
                    // opencv-mobile extracts to opencv-mobile-VERSION-android/
                    val extractedDir = file("${opencvDir}/opencv-mobile-${opencvMobileVersion}-android")
                    
                    if (extractedDir.exists()) {
                        // Copy arm64-v8a static libs
                        val extractedLibDir = file("${extractedDir}/sdk/native/staticlibs/arm64-v8a")
                        if (extractedLibDir.exists()) {
                            extractedLibDir.listFiles()?.forEach { f ->
                                println("  Copying lib: ${f.name}")
                                f.copyTo(file("${libDir}/${f.name}"), overwrite = true)
                            }
                            opencvMobileStaticLibSha256.forEach { (name, sha256) ->
                                verifySha256(file("${libDir}/${name}"), sha256, "opencv-mobile ${name}")
                            }
                            println("✓ opencv-mobile libraries copied")
                        } else {
                            throw org.gradle.api.GradleException("Lib dir not found: ${extractedLibDir}")
                        }
                        
                        // Copy headers
                        val extractedInclude = file("${extractedDir}/sdk/native/jni/include")
                        if (extractedInclude.exists()) {
                            if (includeDir.exists()) includeDir.deleteRecursively()
                            extractedInclude.copyRecursively(includeDir, overwrite = true)
                            println("✓ opencv-mobile headers copied")
                        } else {
                            throw org.gradle.api.GradleException("Include dir not found: ${extractedInclude}")
                        }
                        
                        // Cleanup
                        zipFile.delete()
                        extractedDir.deleteRecursively()
                        
                        println("✓ opencv-mobile ${opencvMobileVersion} installed (~3MB vs ~20MB)")
                    } else {
                        throw org.gradle.api.GradleException(
                            "Extracted dir not found: ${extractedDir}. Available: ${opencvDir.listFiles()?.map { it.name }}"
                        )
                    }
                } else {
                    throw org.gradle.api.GradleException("opencv-mobile download failed or file too small: ${zipFile.length()} bytes")
                }
            } catch (e: Exception) {
                throw org.gradle.api.GradleException("opencv-mobile setup failed: ${e.message}", e)
            }
        } else {
            println("✓ opencv-mobile verified at ${libDir}")
        }
    }
}

// Check surveillance dependencies before build
tasks.register("checkSurveillanceDeps") {
    dependsOn("downloadOpenCV")
}


// Task to extract web assets to /data/local/tmp/web on device
// Run: ./gradlew :app:extractWebAssets
tasks.register("extractWebAssets") {
    description = "Extracts web assets from APK to /data/local/tmp/web on connected device"
    group = "deployment"
    
    doLast {
        val webSrcDir = file("src/main/assets/web")
        if (!webSrcDir.exists()) {
            println("⚠ No web assets found at ${webSrcDir}")
            return@doLast
        }
        
        println("Extracting web assets to device...")
        
        // Create target directory
        exec {
            commandLine("adb", "shell", "mkdir", "-p", "/data/local/tmp/web/shared")
        }
        exec {
            commandLine("adb", "shell", "mkdir", "-p", "/data/local/tmp/web/local")
        }
        
        // Push files
        webSrcDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = file.relativeTo(webSrcDir).path
            val targetPath = "/data/local/tmp/web/${relativePath}"
            println("  → ${relativePath}")
            exec {
                commandLine("adb", "push", file.absolutePath, targetPath)
            }
        }
        
        println("✓ Web assets extracted to /data/local/tmp/web/")
    }
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "key0"
        }
    }
    namespace = "com.overdrive.app"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.overdrive.app"
        minSdk = 25
        targetSdk = 25
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Note: abiFilters removed - using splits.abi instead for size optimization
        
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // This stops the build from failing due to the old targetSdk 28
        checkReleaseBuilds = false
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // Enable minification and shrinking for release builds
            isMinifyEnabled = true
            isShrinkResources = true
            
            // Auto-detect if DaemonLogConfig has any logging flags enabled.
            // When ALL flags are false (production): include proguard-rules-strip-logs.pro
            //   → R8 strips all log calls from bytecode
            // When ANY flag is true (debug build): exclude proguard-rules-strip-logs.pro
            //   → log calls stay in bytecode, DaemonLogConfig controls which tags write to disk
            val logConfigFile = file("src/main/java/com/overdrive/app/logging/DaemonLogConfig.java")
            val loggingEnabled = if (logConfigFile.exists()) {
                val content = logConfigFile.readText()
                val enableAllMatch = Regex("""public static final boolean ENABLE_ALL\s*=\s*true""").containsMatchIn(content)
                val anyFlagTrue = Regex("""public static final boolean (?!ANY_LOGGING_ENABLED)\w+\s*=\s*true""").containsMatchIn(content)
                enableAllMatch || anyFlagTrue
            } else false
            
            val proguardFilesList = mutableListOf(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
            if (loggingEnabled) {
                // Logging enabled: do NOT include strip-logs → log calls survive R8
                println("⚠ DaemonLogConfig: Logging ENABLED — DaemonLogger file logging kept, console still stripped")
            } else {
                // Production: include strip-logs → R8 removes all log calls
                proguardFilesList.add(file("proguard-rules-strip-logs.pro"))
            }
            proguardFiles(*proguardFilesList.toTypedArray())
            
            signingConfig = signingConfigs.getByName("release")
            
            // Update channel: "alpha" for release builds (checks alpha tag on GitHub)
            buildConfigField("String", "UPDATE_CHANNEL", "\"alpha\"")
        }
        debug {
            isMinifyEnabled = false
            
            // Debug builds also check alpha channel for updates
            buildConfigField("String", "UPDATE_CHANNEL", "\"alpha\"")
        }
    }
    
    // Split APKs by ABI - creates smaller APKs per architecture
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")  // Only arm64 for BYD
            isUniversalApk = false  // Don't create universal APK
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            // Tell Gradle to pick up .so files from your custom download folder
            jniLibs.srcDirs("src/main/cpp/openh264/lib")
        }
    }
    kotlinOptions { jvmTarget = "11" }
    
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
            // Both paho.mqttv3 and paho.mqttv5 jars ship the same i18n
            // bundle.properties — pick first to silence the merge conflict.
            // The file is OSGi metadata, never read at runtime in our setup.
            pickFirsts += listOf(
                "bundle.properties"
            )
        }
        // Exclude unnecessary native libs from dependencies
        jniLibs {
            // CRITICAL: Compresses .so files in the APK (saves ~20MB+)
            useLegacyPackaging = true

            // Keep only arm64-v8a (You already have this, but good to keep)
            excludes += listOf(
                "lib/armeabi-v7a/**",
                "lib/x86/**",
                "lib/x86_64/**"
            )
        }
    }
}

/*
 * BYD SDK Stubs Architecture:
 * 
 * The classes in android.hardware.bydauto.* are compile-time stubs that allow
 * the code to compile without the actual BYD SDK JAR.
 * 
 * At runtime on BYD devices:
 * - The real BYD SDK classes are loaded by the boot classloader (higher priority)
 * - Our managers (RadarManager, BodyworkManager) use REFLECTION to get instances
 * - Class.forName() returns the real class from the system framework, not our stub
 * - The stubs in our APK are never actually instantiated
 * 
 * This works because:
 * 1. Boot classloader classes take precedence over app classes
 * 2. We use reflection: Class.forName("android.hardware.bydauto.radar.BYDAutoRadarDevice")
 * 3. getInstance() is called via reflection on the real class
 */

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    
    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    
    // QR Code generation
    implementation(libs.zxing.core)
    
    // RTMP streaming client for pushing to MediaMTX
    implementation(libs.rtmp.client)
    
    // ADB client for daemon launching
    implementation(libs.dadb)
    
    // WebSocket server for zero-latency H.264 streaming
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    

    
    implementation(libs.androidx.work.runtime.ktx)
    
    // TensorFlow Lite for AI inference (replaces NCNN)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")  // GPU acceleration
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")  // GPU API interfaces
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    
    // OkHttp for Telegram HTTP client with proxy support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Encrypted SharedPreferences for secure token/owner storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // H2 Database - Pure Java embedded SQL (no native dependencies, no .so files)
    // Works for UID 2000 because it's 100% Java bytecode - no Android framework needed
    implementation("com.h2database:h2:2.2.224")

    // Eclipse Paho MQTT - Pure Java MQTT client (no native dependencies)
    // mqttv3 used by HA/Mosquitto publish path (MqttPublisherService).
    // mqttv5 used by BydCloudMqttSubscriber — BYD's EMQ broker only pushes
    // vehicleInfo events to MQTT v5 subscribers; v3.1.1 connects fine but
    // gets zero messages.  The two lib jars use different packages so they
    // coexist cleanly.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
