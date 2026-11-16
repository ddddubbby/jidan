// 佳博 SDK2 的真实现：GainschaSdk 薄接口对着 sdk2.aar（2.0.3）的真实签名写成。
//
// 【未经实物验证】没有真打印机，只在编译层面核对过签名；蓝牙连接是否可靠（T-01）、一票一控对这台机型是否有答复、
// 状态位的真实含义，都留给 H-2。
//
// 依据：
// - 《佳博 SDK2 2.0.3 API 说明文档》（sdk/ 里的 PDF）：4.1、4.2（连接与连接监听）、4.4（断开）、5.2（只搜蓝牙）、
//   6.1.3（发送并等回复）、9.2–9.5（一票一控与状态码）、附录 B（中文用 GB18030）。
// - 官方示例工程的类（不入库）：`PrinterConnectActivity`（搜索、`Printer.connect`、连接监听的调用顺序）、
//   `MainActivity`（`printWithOrderTracking` 与状态码 0x20–0x26 的处理、TSPL 示例）。
// - sdk2.aar 的类（`javap` 看签名）。与 API 文档不一致、以 aar 为准的地方：
//   * 包路径：`BluetoothPrinterDevice`、`PrintStatus` 在 `com.gainscha.sdk2.model`；
//   * 文档 9.3 的 `printWithStatusQuery(data, queryDelay, queryTimeout, cb)` 在 aar 里叫
//     `printWithOrderTracking(data, timeoutMs, cb)`，没有“查询延迟”参数（默认超时 5 秒）；
//   * `PrintStatus` 多一个状态码 `STATUS_CUTTER_JAM = 0x26`（卡切刀），文档 9.5 没列；
//   * `BluetoothPrinterDevice` 带 `getBluetoothDevice()`（取地址）与 `getRssi()`（信号），文档没写；
//     文档 4.1.2 的 `new BluetoothPrinterDevice(bluetoothDevice)` 在 aar 里要多一个 rssi 参数；
//   * 文档 8.1 的 `PrinterState.isOverheat()` 在 aar 里没有，第 7 位是 `isOtherError()`；状态询问仍发 `<ESC>!?`（SDK 对 TSC 发的也是这三个字节）；
//   * SDK 自带 `PrinterContentProvider`，启动时把应用 Context 放进 `PrinterContentProvider.context`，搜索靠它。
// - SDK 自带的 `Tspl` 类把文本转 GB18030（aar 里能看到），与这里 `TsplBytes.encode` 用的编码一致。
package com.jidan.receiving.device.gainscha

import android.bluetooth.BluetoothManager
import android.content.Context
import com.gainscha.sdk2.ConnectType
import com.gainscha.sdk2.ConnectionListener
import com.gainscha.sdk2.Printer
import com.gainscha.sdk2.PrinterContentProvider
import com.gainscha.sdk2.PrinterFinder
import com.gainscha.sdk2.PrinterResponse
import com.gainscha.sdk2.model.BluetoothPrinterDevice
import com.gainscha.sdk2.model.PrintStatus
import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LanstarMapping
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class SdkGainschaSdk(context: Context) : GainschaSdk {
    override var onDisconnected: (() -> Unit)? = null

    private val appContext = context.applicationContext
    private val found = LinkedHashMap<String, BluetoothPrinterDevice>()

    @Volatile private var printer: Printer? = null
    @Volatile private var connecting: CompletableDeferred<Boolean>? = null

    private val listener = object : ConnectionListener {
        override fun onPrinterConnected(p: Printer) {
            printer = p
            p.setAutoReConnect(true, AUTO_RECONNECT_TIMES) // v7 7.3 写明的做法；SDK 重连不上时再由 LinkKeeper 接着连
            connecting?.complete(true)
        }

        override fun onPrinterConnectFail(p: Printer) {
            connecting?.complete(false)
        }

        override fun onPrinterDisconnect(p: Printer) {
            // 自己调 disconnect() 时已先清了 printer，这里不当作“丢失”
            if (printer === p) {
                printer = null
                onDisconnected?.invoke()
            }
        }
    }

    init {
        // SDK 靠自带的 ContentProvider 拿 Context；万一没跑到（多进程、被合并掉）就补上
        if (PrinterContentProvider.context == null) PrinterContentProvider.context = appContext
        Printer.addConnectionListener(listener)
    }

    // API 文档 5.2：只搜蓝牙（经典蓝牙，SDK 内部是 startDiscovery）
    override suspend fun search(): List<DiscoveredDevice> {
        synchronized(found) { found.clear() }
        val finder = PrinterFinder()
        val done = CompletableDeferred<Unit>()
        finder.searchPrinters(
            ConnectType.BLUETOOTH,
            object : PrinterFinder.SimpleSearchPrinterResultListener() {
                override fun onSearchBluetoothPrinter(device: BluetoothPrinterDevice) {
                    synchronized(found) { found[device.bluetoothDevice.address] = device }
                }

                override fun onSearchCompleted() {
                    done.complete(Unit)
                }
            },
        )
        // 蓝牙没开时 SDK 只弹开启请求、不回 onSearchCompleted，所以要有个上限，到点就交出已搜到的
        try {
            withTimeoutOrNull(SEARCH_LIMIT_MS) { done.await() }
        } finally {
            finder.stopSearchDevice()
        }
        return synchronized(found) {
            found.values.map {
                DiscoveredDevice(it.bluetoothDevice.address, it.printerName ?: MODEL, LanstarMapping.rssiBars(it.rssi))
            }
        }
    }

    // API 文档 4.1、4.2：Printer.connect 是静态方法，结果在 ConnectionListener 里
    override suspend fun connect(id: String): Boolean {
        // SDK 自己重连上了而 GainschaPrinter 还不知道（LOST）：不用再连一遍
        val cur = printer
        if (cur != null && cur.isConnected && (cur.printerDevice as? BluetoothPrinterDevice)?.bluetoothDevice?.address == id) return true
        val device = synchronized(found) { found[id] } ?: remembered(id) ?: return false
        disconnect()
        val wait = CompletableDeferred<Boolean>()
        connecting = wait
        Printer.connect(device)
        return withTimeoutOrNull(CONNECT_LIMIT_MS) { wait.await() } ?: false
    }

    /** 上次配对的设备没在这一轮搜索里时（刚启动 APP），按地址直接取。 */
    private fun remembered(address: String): BluetoothPrinterDevice? = try {
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        BluetoothPrinterDevice(adapter.getRemoteDevice(address), -1)
    } catch (e: IllegalArgumentException) {
        null // 地址格式不对
    }

    // API 文档 4.4
    override fun disconnect() {
        val p = printer
        printer = null
        p?.disconnect()
    }

    // API 文档 9.2–9.5：打印并按“一票一控”取回状态码。aar 里的方法名是 printWithOrderTracking，超时 3 秒（文档建议 3–5 秒）
    override suspend fun printTracked(data: ByteArray): Int? {
        val p = printer ?: throw IOException("打印机未连接")
        return suspendCancellableCoroutine { cont ->
            val once = AtomicBoolean()
            try {
                p.printWithOrderTracking(data, TRACK_TIMEOUT_MS, PrinterResponse<PrintStatus> { status ->
                    if (once.compareAndSet(false, true)) cont.resume(status?.let { it.statusCode.toInt() and 0xFF })
                })
            } catch (e: IllegalStateException) {
                throw IOException("打印机未就绪：${e.message}", e) // 一票一控没初始化
            }
        }
    }

    // API 文档 6.1.3：发送并等回复，超时回调里是 null
    override suspend fun ask(command: ByteArray): ByteArray? {
        val p = printer ?: return null
        return suspendCancellableCoroutine { cont ->
            val once = AtomicBoolean()
            p.print(command, ASK_TIMEOUT_MS, PrinterResponse<ByteArray> { reply ->
                if (once.compareAndSet(false, true)) cont.resume(reply)
            })
        }
    }

    companion object {
        const val MODEL = "GP-M32B"
        const val AUTO_RECONNECT_TIMES = 3
        const val SEARCH_LIMIT_MS = 20_000L
        const val CONNECT_LIMIT_MS = 15_000L
        const val TRACK_TIMEOUT_MS = 3000L
        const val ASK_TIMEOUT_MS = 1000
    }
}
