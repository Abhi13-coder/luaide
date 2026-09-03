plugins {
    id("com.android.application")
}

// The applicationId is overwritten by package-lua-project.yml per-project
// (via -PapplicationId=... / a Gradle property) so multiple packaged apps
// don't collide once installed side by side on a device.
val projectAppId: String = (findProperty("appId") as String?) ?: "com.luaide.runner.sample"
val projectAppName: String = (findProperty("appLabel") as String?) ?: "Lua App"

android {
    namespace = "com.luaide.app"
    compileSdk = 34

    defaultConfig {
        applicationId = projectAppId
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
        resValue("string", "app_name", projectAppName)

        ndk {
            abiFilters += "armeabi-v7a"
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
}
