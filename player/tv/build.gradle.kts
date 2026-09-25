plugins {
    alias(gradleLibs.plugins.android.library)
    alias(gradleLibs.plugins.compose.compiler)
}

android {
    namespace = "${AppConfiguration.appId}.player.tv"
    compileSdk = AppConfiguration.compileSdk

    defaultConfig {
        minSdk = AppConfiguration.minSdk

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("r8Test") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("alpha") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        targetSdk = AppConfiguration.targetSdk
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(AppConfiguration.jdk))
    }
}


dependencies {
    implementation(project(":player:core"))
    implementation(project(":player:shared"))
    implementation(androidx.activity.compose)
    implementation(androidx.compose.constraintlayout)
    implementation(androidx.compose.material.icons)
    implementation(androidx.compose.material3)
    implementation(androidx.compose.tv.foundation)
    implementation(androidx.compose.tv.material)
    implementation(androidx.compose.ui)
    implementation(androidx.compose.ui.tooling.preview)
    implementation(androidx.compose.ui.util)
    implementation(androidx.core.ktx)
    implementation(libs.coil.compose)
    implementation(libs.logging)
    implementation(libs.material)
    implementation(libs.qrcode)
    debugImplementation(androidx.compose.ui.test.manifest)
    debugImplementation(androidx.compose.ui.tooling)
}