plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xudong.suotu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xudong.suotu"
        minSdk = 29
        targetSdk = 37
        // Bump this on every build you install anywhere.
        //
        // Android (and vivo's launcher especially) caches an app's icon keyed on
        // package + versionCode. Reinstalling with an unchanged versionCode leaves the
        // cache untouched, so a corrected icon keeps rendering as the old one — which
        // cost real debugging time: the new drawable was verifiably inside the APK on
        // the device while the notification still showed the previous mark.
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // Minify + strip unused Compose/AndroidX code. A debug build of this app is
            // ~27 MB, almost all of it unused library code; shrinking matters because
            // the user sideloads this by hand.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key so the release APK is directly installable
            // without provisioning a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
}
