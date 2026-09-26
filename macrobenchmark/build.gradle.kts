plugins {
    alias(gradleLibs.plugins.android.test)
}

android {
    namespace = "${AppConfiguration.appId}.macrobenchmark"
    compileSdk = AppConfiguration.compileSdk

    defaultConfig {
        minSdk = AppConfiguration.minSdk
        targetSdk = AppConfiguration.targetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 模拟器上也能跑通（结果不代表真机，但便于冒烟验证）
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        buildConfigField("String", "TARGET_PACKAGE", "\"${AppConfiguration.applicationId}\"")
    }

    buildTypes {
        // 测的是 app 的 benchmark 变体（release 同款混淆 + 资源缩减），本模块自身用 debug 签名便于本地跑
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("default") {
            dimension = "channel"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(AppConfiguration.jdk))
    }
}

dependencies {
    implementation(androidx.benchmark.macro.junit4)
    implementation(androidx.test.ext.junit)
    implementation(androidx.test.runner)
    implementation(androidx.test.uiautomator)
}
