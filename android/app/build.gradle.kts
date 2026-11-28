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
        versionCode = 3
        versionName = "0.2.0"
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
            // 佳博 SDK2 的原生库只做 arm64-v8a（16KB 对齐说明第 3 步）
            ndk { abiFilters += "arm64-v8a" }
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

    // 演示版（F5-3）：给合作方离线试用，没有服务器、没有登录；包名再加 .demo，可与模拟版、实机版同装。
    // 只有 simDemo 有意义（见下方 beforeVariants），其余构建类型的 BuildConfig.DEMO 为 false，行为不变。
    // 蓝牙实测版（F5-4）：同样免登录、离线假后端，但挂在实机版上，秤与打印机走真蓝牙；只有 realBttest。
    buildTypes {
        getByName("debug") { buildConfigField("boolean", "DEMO", "false") }
        getByName("release") { buildConfigField("boolean", "DEMO", "false") }
        create("demo") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO", "true")
            // 构建类型里的 app_name 压过模拟版风味里的
            resValue("string", "app_name", "收蛋（演示版）")
        }
        // 蓝牙实测版：免登录、离线假后端（同演示版），秤与打印机走真蓝牙；只有 realBttest 有意义
        create("bttest") {
            initWith(getByName("demo"))
            matchingFallbacks += "debug"
            applicationIdSuffix = ".bttest"
            versionNameSuffix = "-bttest"
            buildConfigField("boolean", "DEMO", "true")
            resValue("string", "app_name", "收蛋（蓝牙测试）")
        }
    }

    sourceSets {
        // 非演示版共用一个什么都不做的 DemoMode；演示版的在 src/demo
        getByName("debug").java.srcDir("src/nodemo/java")
        getByName("release").java.srcDir("src/nodemo/java")
        getByName("bttest").java.srcDir("src/demo/java")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 16KB 对齐说明第 3 步：.so 以 SDK 要求的方式打包
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // 渲染截图（F4-1，:app:screens）要 Robolectric 读到真的资源；纯 JVM 单测不受影响
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// 演示版只做模拟版：实机版没有真秤真打印机，演示没有意义
androidComponents {
    beforeVariants { variant ->
        if (variant.buildType == "demo" && variant.productFlavors.any { it.second == "real" }) {
            variant.enable = false
        }
        if (variant.buildType == "bttest" && variant.productFlavors.any { it.second == "sim" }) {
            variant.enable = false
        }
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

    // 佳博 SDK2：只有 real 版带（sim 版保持轻量、不编原生代码）。依赖清单见 sdk/ 里 API 说明 2.1
    "realImplementation"(files("../libs/sdk2.aar"))
    "realImplementation"(libs.androidx.annotation)
    "realImplementation"(libs.jzlib)
    "realImplementation"(project(":fimage"))
    "realImplementation"(project(":jzint"))
    "realImplementation"(project(":serial-port"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // 李四全流程测试（F1-9）：虚拟时钟 + 接口回放
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // 渲染截图（F4-1）：只在测试里用，不进 APK
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// 渲染截图测试（src/test/.../screens/）要下 Robolectric 的原生运行时、跑得慢，默认的 testSimDebugUnitTest 不跑它们。
// 要跑：`./gradlew :app:screens`（或 -Pscreens）；矩阵、尺寸等参数见 tools/ui-compare/README.md。
val screensRequested = providers.gradleProperty("screens").isPresent ||
    gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "screens" }
val screensPackage = "com.jidan.receiving.screens.*"

tasks.withType<Test>().configureEach {
    useJUnit()
    if (screensRequested) {
        filter.includeTestsMatching(screensPackage)
        // 每次都重新渲染；把 -Pscreens.* 参数传进测试 JVM
        outputs.upToDateWhen { false }
        maxHeapSize = "2g"
        maxParallelForks = 1
        systemProperty("screens.run", "1")
        // 每次运行从空目录开始：旧尺寸的图与记录不混进这一轮
        doFirst { layout.buildDirectory.dir("screens").get().asFile.deleteRecursively() }
        // Robolectric 的运行时 jar 默认从 Maven Central 取；网络受限的环境里可用 -Probolectric.repo=<镜像地址> 改
        systemProperty(
            "robolectric.dependency.repo.url",
            providers.gradleProperty("robolectric.repo").getOrElse("https://repo.maven.apache.org/maven2"),
        )
        providers.gradleProperty("screens.matrix").orNull?.let { systemProperty("screens.matrix", it) }
        providers.gradleProperty("screens.height").orNull?.let { systemProperty("screens.height", it) }
        providers.gradleProperty("screens.only").orNull?.let { systemProperty("screens.only", it) }
        providers.gradleProperty("screens.full").orNull?.let { systemProperty("screens.full", it) }
        testLogging {
            events("failed", "standard_error")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    } else {
        filter.excludeTestsMatching(screensPackage)
    }
}

tasks.register("screens") {
    group = "verification"
    description = "渲染每个 APP 页面与弹层为 PNG（模拟版，Robolectric），并检查孤字行；输出在 app/build/screens/"
    dependsOn("testSimDebugUnitTest")
}
