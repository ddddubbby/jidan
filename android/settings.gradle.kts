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
include(":core", ":device", ":app")

// 佳博 SDK2 的三个源码模块（fimage、jzint、serial-port）：开发包不入库，放进本目录后自动加入构建。
// 它们带原生代码，要 NDK 与 CMake 3.22.1，并须按 16KB 页面对齐（见 sdk/ 里的 16KB 对齐说明）。
// 接法见 device/sdk-link/README.md
for (m in listOf("fimage", "jzint", "serial-port")) {
    if (file(m).isDirectory) include(":$m")
}
