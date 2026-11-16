// 纯 Kotlin 模块：不依赖 Android，也不依赖其他模块（开发规格 9.1）
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
    // 只在测试里读仓库根 fixtures/ 的算例文件；:core 本身仍然没有任何依赖
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnitPlatform()
    // 算例文件在 Gradle 工程之外，登记为输入，改了算例就不会被当成“没变化”而跳过测试
    inputs.dir("../../fixtures").withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}
