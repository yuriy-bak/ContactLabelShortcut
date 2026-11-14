import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release key comes from the environment (GitHub Secrets in CI); it is never in the repository.
val keystoreFile = System.getenv("KEYSTORE_FILE")?.let(::file)
val releaseSigning = keystoreFile != null && keystoreFile.exists()

android {
    namespace = "io.github.yuriybak.contactlabelshortcut"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yuriybak.contactlabelshortcut"
        minSdk = 26
        targetSdk = 35
        // Raise both for every release; CI checks that the release tag is "v" + versionName
        versionCode = 100
        versionName = "0.1.0-beta.21"
    }

    signingConfigs {
        if (releaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Without the release key (local builds, pull requests) the APK is signed with the debug key
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
