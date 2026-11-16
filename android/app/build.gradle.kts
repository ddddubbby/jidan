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
            // 包名带 .sim 后缀，可与实机版同装一台手机（开发规格 11.3）
            applicationIdSuffix = ".sim"
            // 桌面上一眼分得出两个包
            resValue("string", "app_name", "收蛋（模拟版）")
        }
        create("real") {
            dimension = "device"
            resValue("string", "app_name", "收蛋")
            // 蓝石秤 SDK 的 companyKey：不入库。构建时从 Gradle 属性 lanstar.companyKey
            // （-P 或 ~/.gradle/gradle.properties）或环境变量 LANSTAR_COMPANY_KEY 取；
            // 都没有则为空串，SDK 在没有 key 时的表现未验证（见 sdk/README.md）
            val key = providers.gradleProperty("lanstar.companyKey")
                .orElse(providers.environmentVariable("LANSTAR_COMPANY_KEY"))
                .getOrElse("")
                .replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "LANSTAR_COMPANY_KEY", "\"$key\"")
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
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test> {
    useJUnit()
}
