package com.jidan.receiving.device.gainscha

import com.jidan.receiving.device.DiscoveredDevice
import java.io.IOException

/**
 * 佳博 SDK2 的薄接口：厂商 SDK 的全部调用只会出现在这个接口的实现里（开发规格 2.2、9.1）。
 * 接口的每个成员对应《佳博 SDK2 2.0.3 API 说明文档》的一处调用（见各成员注释；方法名以 aar 为准的地方见 [SdkGainschaSdk] 文件头）；
 * 编码、状态码映射、连接状态、重连都在接口之外（[GainschaPrinter]、[GainschaCodes]、`LinkKeeper`）。
 *
 * 真实现是 [SdkGainschaSdk]（对着 `android/libs/sdk2.aar` 的真实签名写成，未经实物验证）；
 * 单测里用脚本化的假实现。
 */
interface GainschaSdk {
    /** 对方断开（关机、走远）时由实现回调；对应 API 文档 4.2 的 `ConnectionListener.onPrinterDisconnect`。 */
    var onDisconnected: (() -> Unit)?

    /** 搜蓝牙打印机，搜完返回；对应 5.2 `PrinterFinder.searchPrinters(ConnectType.BLUETOOTH, …)`。 */
    suspend fun search(): List<DiscoveredDevice>

    /** 连接并等到结果，true 为已连上；对应 4.1 `Printer.connect` 与 `onPrinterConnected`/`onPrinterConnectFail`。 */
    suspend fun connect(id: String): Boolean

    /** 对应 4.4 `Printer.disconnect()`。 */
    fun disconnect()

    /**
     * 发出一份打印数据，并按“一票一控”取回状态码（API 文档 9.3；aar 里叫 `printWithOrderTracking`，状态码见 9.5）。
     * 返回 null 表示没有答复（超时，或这台打印机不支持一票一控）。发送失败抛 [IOException]。
     */
    suspend fun printTracked(data: ByteArray): Int?

    /** 发一条询问指令并等回复，null 为超时；对应 6.1.2 `Printer.print(byte[], PrinterResponse<byte[]>)`。 */
    suspend fun ask(command: ByteArray): ByteArray?
}
