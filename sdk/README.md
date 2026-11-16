# 厂商资料

| 资料 | 在哪里 |
|---|---|
| 佳博标签打印机 TSPL 编程手册 v1.0.8 | 本目录 `标签打印机TSPL编程手册  v1.0.8.pdf` |
| 佳博 SDK2 2.0.3 开发包 | 63MB 压缩包，不入库，随移交包另给。v7 7.3 说要引入 `sdk2-release.aar` 与 `fimage`、`jzint`、`serial-port` 三个源码模块；压缩包里直接能看到三个源码模块、一个示例 APK 和一个 `demo.tar`，aar 应在 `demo.tar` 里，P8-2 时确认。P8-2 只把编译要用的部分放进 `android/` |
| 蓝石蓝牙电子秤 SDK | Gradle 依赖 `io.github.lansdialog:blelibrary:2.5.9`；示例仓库 <https://github.com/wuyan001/lans_ble_demo/>。初始化要用的 `companyKey` 向蓝石申请，不入库；构建实机版时通过 Gradle 属性 `lanstar.companyKey`（`-Planstar.companyKey=…` 或放进自己的 `~/.gradle/gradle.properties`）或环境变量 `LANSTAR_COMPANY_KEY` 给出，不给则为空串（SDK 在没有 key 时的表现未验证）。`LanstarScale` 在 `android/device/` |
| 畅捷通采购入库单新增接口 | <https://open.chanjet.com/docs/file/apiFile/zplus/zjkcgl/30644?id=32738>（同目录有修改、删除、查询接口；基础档案在同一文档树的“基础档案”节点） |
