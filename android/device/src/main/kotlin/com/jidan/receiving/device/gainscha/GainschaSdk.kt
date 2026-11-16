package com.jidan.receiving.device.gainscha

import com.jidan.receiving.device.DiscoveredDevice
import java.io.IOException

/**
 * 佳博 SDK2 的薄接口：厂商 SDK 的全部调用只会出现在这个接口的实现里（开发规格 2.2、9.1）。
 * 接口的每个成员对应《佳博 SDK2 2.0.3 API 说明文档》的一处调用（见各成员注释）；
 * 编码、状态码映射、连接状态、重连都在接口之外（[GainschaPrinter]、[GainschaCodes]、`LinkKeeper`）。
 *
 * 开发包里的 `sdk2-release.aar` 不在仓库，所以真实现还没有；缺它时用 [GainschaSdkMissing]。
 * 接上办法见 `android/device/sdk-link/README.md`。未经实物验证。
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
     * 发出一份打印数据，并按“一票一控”取回状态码（API 文档 9.3 `printWithStatusQuery`，状态码见 9.5）。
     * 返回 null 表示没有答复（超时，或这台打印机不支持一票一控）。发送失败抛 [IOException]。
     */
    suspend fun printTracked(data: ByteArray): Int?

    /** 发一条询问指令并等回复，null 为超时；对应 6.1.2 `Printer.print(byte[], PrinterResponse<byte[]>)`。 */
    suspend fun ask(command: ByteArray): ByteArray?
}

/** 没放入佳博开发包时的实现：搜不到、连不上，不假装成功。 */
class GainschaSdkMissing : GainschaSdk {
    override var onDisconnected: (() -> Unit)? = null
    override suspend fun search(): List<DiscoveredDevice> = emptyList()
    override suspend fun connect(id: String): Boolean = false
    override fun disconnect() {}
    override suspend fun printTracked(data: ByteArray): Int? = throw IOException(MISSING)
    override suspend fun ask(command: ByteArray): ByteArray? = null

    companion object {
        const val MISSING = "未接入佳博 SDK（缺 sdk2-release.aar），见 android/device/sdk-link/README.md"
    }
}
