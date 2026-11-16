# 厂商资料

| 资料 | 在哪里 |
|---|---|
| 佳博标签打印机 TSPL 编程手册 v1.0.8 | 本目录 `标签打印机TSPL编程手册  v1.0.8.pdf` |
| 佳博 SDK2 2.0.3 开发包 | 63MB 压缩包，不入库，随移交包另给。编译要用的部分已放进 `android/`：`libs/sdk2.aar`（开发包里叫 `sdk2.aar`，在示例工程的 `app/libs/` 里）与 `fimage`、`jzint`、`serial-port` 三个源码模块（只留构建需要的，改动见各自 `build.gradle` 开头与开发规格附录 E 的 P8-2 行）；示例 APK 与示例工程不入库，调用顺序以示例里的 `PrinterConnectActivity`、`MainActivity` 为参考 |
| 佳博 SDK2 2.0.3 API 说明文档 | 本目录 `佳博SDK2-2.0.3-API说明文档.pdf`（开发包内附，已去掉文件属性里的作者信息）：连接、搜索、打印、指令集、状态查询 |
| 佳博 SDK2 16KB 对齐说明 | 本目录 `佳博SDK2-16KB对齐说明.pdf`：`sdk2.aar` 不含 .so，`fimage`、`jzint`、`serial-port` 三个模块须按 16KB 页面对齐配置，Android 15+ 才能正常运行。已照此改好 `settings.gradle.kts`、`gradle.properties`、`:app` 的打包选项，三个模块自带对齐配置 |
| 蓝石蓝牙电子秤 SDK | Gradle 依赖 `io.github.lansdialog:blelibrary:2.5.9`；示例仓库 <https://github.com/wuyan001/lans_ble_demo/>。初始化要用的 `companyKey` 向蓝石申请，不入库；构建实机版时通过 Gradle 属性 `lanstar.companyKey`（`-Planstar.companyKey=…` 或放进自己的 `~/.gradle/gradle.properties`）或环境变量 `LANSTAR_COMPANY_KEY` 给出，不给则为空串（SDK 在没有 key 时的表现未验证）。`LanstarScale` 在 `android/device/` |
| 畅捷通采购入库单新增接口 | <https://open.chanjet.com/docs/file/apiFile/zplus/zjkcgl/30644?id=32738>（同目录有修改、删除、查询接口；基础档案在同一文档树的“基础档案”节点） |
