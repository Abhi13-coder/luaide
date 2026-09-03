plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.luaide.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luaide.app"
        minSdk = 29        // Android 10 — target platform per spec
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // ARM 32-bit userspace only, per spec section 2. Extend this list
            // later if/when other ABIs are officially supported.
            abiFilters += "armeabi-v7a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // We control native module loading ourselves (see NativeModuleLoader);
        // don't let AGP silently pick a random duplicate .so between deps.
        jniLibs.pickFirsts.add("**/libc++_shared.so")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r")
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation("com.madgag.spongycastle:prov:1.58.0.0")
    implementation("com.madgag.spongycastle:pkix:1.58.0.0")
}
