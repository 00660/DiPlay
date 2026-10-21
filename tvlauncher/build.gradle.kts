plugins {
    id("com.android.application") version "9.3.0"
}

android {
    namespace = "com.shihab.diplay.tvlauncher"
    compileSdk {
        version = release(35)
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.shihab.diplay.tvlauncher"
        minSdk = 19
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
