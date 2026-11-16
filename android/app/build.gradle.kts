plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jidan.receiving"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jidan.receiving"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // 模拟版与实机版只差 DeviceFactory 和模拟台（开发规格 9.1）
    flavorDimensions += "device"
    productFlavors {
        create("sim") {
            dimension = "device"
            // 桌面上一眼分得出两个包
            resValue("string", "app_name", "收蛋（模拟版）")
        }
        create("real") {
            dimension = "device"
            resValue("string", "app_name", "收蛋")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":device"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.material)
}
