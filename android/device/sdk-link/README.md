# 接上佳博 SDK（P8-2 留给放入开发包的人）

`GainschaPrinter`（`device/src/main/kotlin/.../gainscha/`）已经按《佳博 SDK2 2.0.3 API 说明文档》写完：TSPL 转 GB 码、位图转 BITMAP 指令、结果码与状态码映射、指令文本打日志。缺的只有 SDK 那一层的真实现，因为开发包里的 `sdk2-release.aar` 不在仓库。没放包时实机版用 `GainschaSdkMissing`：搜不到、连不上，不假装成功。

**未经实物验证。** 下面的步骤和 `SdkGainschaSdk.kt.draft` 都没编译过（没有 aar）；草稿只按 API 说明文档的写法列出每个调用，文档没写的地方在草稿里标了出来。

## 步骤

1. 开发包（63MB 压缩包，见 `sdk/README.md`）里：
   - `sdk2-release.aar`（压缩包里的 `demo.tar` 中，`sdk/README.md` 的推测）放到 `android/device/libs/`。
   - `fimage`、`jzint`、`serial-port` 三个源码模块的目录放到 `android/` 下（与 `app`、`device` 同级）。
2. 三个模块的修改：
   - `fimage/build.gradle` 开头有 `apply from: '../publish.gradle'`，开发包里没有这个文件，删掉这一行（`jzint`、`serial-port` 也看一下）。
   - 16KB 对齐的配置开发包自带的 `build.gradle` 和 `CMakeLists.txt` 已写好，核对一遍与 `sdk/佳博SDK2-16KB对齐说明.pdf` 一致即可；`android/gradle.properties` 里的链接器标志已经加了。
   - 三个模块带原生代码（fimage 里是整套 FreeImage 源码，约 35MB），构建机要装 NDK 与 CMake 3.22.1。
   - `settings.gradle.kts` 与 `device/build.gradle.kts` 已写成“目录在才加入”，不用改。
3. 把 `SdkGainschaSdk.kt.draft` 改名为 `SdkGainschaSdk.kt` 放进 `device/src/main/kotlin/com/jidan/receiving/device/gainscha/`，用 IDE 补全缺的 import，逐个调用对照 aar 里的真实签名改到能编译。
4. 接线：`app/src/real/.../registry/DeviceRegistry.kt` 里把 `GainschaSdkMissing()` 换成 `SdkGainschaSdk(context)`。`context` 要从 `AppContainer`（有 `appContext`）经 `DeviceFactory.createPrinter` 传进来；`DeviceFactory` 现在没有这个参数，加上即可，模拟版同步加一个不用的参数。
5. 验收：`cd android && ./gradlew :device:test :app:assembleRealDebug`，装到手机上走 H-2（`T-01` 蓝牙连接方式、标签版式、状态位、一票一控是否可用）。打印时在 logcat 过滤标签 `GainschaPrinter`，对照 TSPL 手册检查指令文本。

## 实测时要看的几处

- `GainschaCodes.NO_REPLY_IS_SUCCESS`：一票一控没有答复时按成功算。机型不支持时才需要关心；改法见该常量的注释。
- `GainschaCodes.issueOf`：`<ESC>!?` 的位表。手册里 `0x80` 同时写作“其他错误”和第 7 位“过热”，这里按“过热”；卡纸、无碳带并入“缺纸”，因为 `PrinterIssue` 只有四种。
- 位图版（`print.render=bitmap`）自己把图转成 1 位点阵，用 TSPL 的 `BITMAP` 指令发，没有走 SDK 的 `Printer.print(Bitmap, …)`，也不依赖 `fimage`。若实测出图反色或偏位，先看 `TsplBytes.bitmapJob` 里“0 为黑点”的约定。
- 打印机状态每 3 秒问一次（`GainschaPrinter.POLL_MS`），发送前再问一次。
