import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dby.mobile"
    // 37: the Compose alpha BOM requires it. targetSdk stays 36.
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.dby.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 103
        versionName = "1.0.3"
        // Off for any store build: Play does not allow REQUEST_INSTALL_PACKAGES for apps like this.
        buildConfigField("boolean", "IN_APP_UPDATES", "true")
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    // The release key lives outside the repo (~/.dby-signing). Without it, release builds
    // fall back to the debug key so they still build on other machines.
    val signing = File(System.getProperty("user.home"), ".dby-signing/keystore.properties")
    if (signing.exists()) {
        val props = Properties().apply { signing.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Compose's snapshot state calls android.os.Trace, which the JVM test stubs would throw on.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// The Rust core is built and its Kotlin bindings generated before every build. Both outputs
// are git-ignored. cargo is incremental, so an unchanged core costs a second or two.
val coreDir = rootDir.resolve("../core")
val jniLibsDir = projectDir.resolve("src/main/jniLibs")
val bindingsDir = projectDir.resolve("src/main/java")
val ndkHome = "${System.getenv("ANDROID_HOME")}/ndk/30.0.16248370"

val buildRustCore by tasks.registering(Exec::class) {
    description = "Builds core/ for arm64-v8a and x86_64 into src/main/jniLibs."
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", ndkHome)
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "26",
        "-o", jniLibsDir.absolutePath, "build", "--release",
    )
}

// The release .so is stripped, which removes the symbols UniFFI reads its interface from, so
// the bindings come from an unstripped host build of the same source.
val buildHostCore by tasks.registering(Exec::class) {
    description = "Builds core/ for this machine, only to read its UniFFI interface."
    workingDir = coreDir
    commandLine("cargo", "build", "--lib")
}

val generateBindings by tasks.registering(Exec::class) {
    description = "Generates the Kotlin bindings for core/ into src/main/java/com/dby/core."
    dependsOn(buildRustCore, buildHostCore)
    workingDir = coreDir
    val os = System.getProperty("os.name")
    val hostLibrary = when {
        os.startsWith("Windows") -> "target/debug/dby_core.dll"
        os.startsWith("Mac") -> "target/debug/libdby_core.dylib"
        else -> "target/debug/libdby_core.so"
    }
    commandLine(
        "cargo", "run", "-p", "uniffi-bindgen", "--", "generate",
        "--library", coreDir.resolve(hostLibrary).absolutePath,
        "--language", "kotlin", "--out-dir", bindingsDir.absolutePath, "--no-format",
    )
}

tasks.named("preBuild") { dependsOn(generateBindings) }

dependencies {
    implementation(platform("androidx.compose:compose-bom-alpha:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // UniFFI's Kotlin bindings call the Rust library through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // Liquid and frosted glass (Apache-2.0). Replaces Haze: one capture per screen serves both.
    implementation("io.github.kyant0:backdrop:2.0.1")
    // backdrop declares its shapes dependency as runtime-only; the glass code names its shapes.
    implementation("io.github.kyant0:shapes:1.2.1")
    testImplementation("junit:junit:4.13.2")
    // android.jar only stubs org.json; the dbx import and updater tests parse real JSON.
    testImplementation("org.json:json:20250517")
}
