// 设备模块：Scale、LabelPrinter 接口与适配器放这里（P6-2、P8）。厂商 SDK 只在这里出现
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jidan.receiving.device"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    // 二维码矩阵：箱贴上的码必须是真码（v7 5.4）；位图与预览共用
    implementation(libs.zxing.core)
    // 蓝石电子秤 SDK：只在 LanstarScale 里用（开发规格 9.1）
    implementation(libs.lanstar.ble)
    // 佳博 SDK2：开发包不入库。把 sdk2-release.aar 放进 device/libs/ 后下面这段才生效；
    // 与 sdk/ 里 API 说明 2.1 的依赖清单一致。三个源码模块由 settings.gradle.kts 按目录是否存在加入
    if (file("libs/sdk2-release.aar").exists()) {
        implementation(files("libs/sdk2-release.aar"))
        implementation("androidx.annotation:annotation:1.7.1")
        implementation("com.jcraft:jzlib:1.1.3")
        for (m in listOf("fimage", "jzint", "serial-port")) {
            if (rootProject.file(m).isDirectory) implementation(project(":$m"))
        }
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnit()
}
