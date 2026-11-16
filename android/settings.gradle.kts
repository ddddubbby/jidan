pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jidan-receiving"
// 佳博 SDK2 的三个源码模块（开发包原样放入，改动见各自 build.gradle 开头与开发规格附录 E）：
// 带原生代码，构建要 NDK 27 与 CMake 3.22.1，并须按 16KB 页面对齐（sdk/ 里的 16KB 对齐说明）。
// 只有 :app 的 real 版依赖它们，sim 版构建不碰原生代码。
include(":core", ":device", ":app", ":fimage", ":jzint", ":serial-port")
