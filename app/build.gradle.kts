@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration.FilterType
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(gradleLibs.plugins.android.application)
    alias(gradleLibs.plugins.compose.compiler)
    alias(gradleLibs.plugins.google.ksp)
    alias(gradleLibs.plugins.kotlin.serialization)
}


val signingProp = file(project.rootProject.file("signing.properties"))

android {
    signingConfigs {
        if (signingProp.exists()) {
            val properties = Properties().apply {
                load(FileInputStream(signingProp))
            }
            create("key") {
                storeFile = rootProject.file(properties.getProperty("keystore.path"))
                storePassword = properties.getProperty("keystore.pwd")
                keyAlias = properties.getProperty("keystore.alias")
                keyPassword = properties.getProperty("keystore.alias_pwd")
            }
        }
    }

    namespace = AppConfiguration.appId
    compileSdk = AppConfiguration.compileSdk

    defaultConfig {
        applicationId = AppConfiguration.applicationId
        minSdk = AppConfiguration.minSdk
        targetSdk = AppConfiguration.targetSdk
        versionCode = AppConfiguration.versionCode
        versionName = AppConfiguration.versionName
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions.add("channel")

    productFlavors {
        // create("lite") {
        //     dimension = "channel"
        // }
        create("default") {
            dimension = "channel"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true // 移除未使用的资源
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingProp.exists()) signingConfig = signingConfigs.getByName("key")
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".debug"
        }
        create("r8Test") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".r8test"
            if (signingProp.exists()) signingConfig = signingConfigs.getByName("key")
        }
        create("alpha") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingProp.exists()) signingConfig = signingConfigs.getByName("key")
        }
        // macrobenchmark 的目标变体：与 release 同款混淆/资源缩减。
        // 有 release 签名时优先用它——真机上通常已装同包名的 release 包，同签名才能"升级安装"而不丢数据；
        // 没有签名配置（如 CI）时退回 debug 签名。
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = if (signingProp.exists()) {
                signingConfigs.getByName("key")
            } else {
                signingConfigs.getByName("debug")
            }
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        //buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "**/*.proto"
            excludes += "**/*.kotlin_metadata"
            excludes += "**/kotlin/**"
            excludes += "**/*.txt"
            excludes += "**/*.version"
        }

//        if (gradle.startParameter.taskNames.find { it.startsWith("assembleLite") } != null) {
//            jniLibs {
//                val vlcLibs = listOf("libvlc", "libc++_shared", "libvlcjni")
//                val abis = listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")
//                vlcLibs.forEach { vlcLibName -> abis.forEach { abi -> excludes.add("lib/$abi/$vlcLibName.so") } }
//            }
//        }
    }

    /*splits {
        if (gradle.startParameter.taskNames.find { it.startsWith("assembleDefault") } != null) {
            abi {
                isEnable = true
                reset()
                include("x86_64", "x86", "arm64-v8a", "armeabi-v7a")
                isUniversalApk = true
            }
        }
    }*/
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterType.ABI }?.identifier ?: "universal"
            output.outputFileName.set(
                "BV_${AppConfiguration.versionCode}_${AppConfiguration.versionName}.${variant.buildType}_${variant.flavorName}_$abi.apk"
            )
            output.versionName.set("${AppConfiguration.versionName}.${variant.buildType}")
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_build_reports")
    stabilityConfigurationFiles.addAll(
        layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(AppConfiguration.jdk))
    }
}

dependencies {
    implementation(project(":app:mobile"))
    implementation(project(":app:tv"))
    implementation(project(":app:shared"))
    // 让 APK 里 AAR 自带的 baseline profile 在首次运行时真正装上（实测冷启动 -20%）
    implementation(androidx.profileinstaller)
}

tasks.withType<Test> {
    useJUnitPlatform()
}