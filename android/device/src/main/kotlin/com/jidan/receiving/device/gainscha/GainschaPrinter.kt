package com.jidan.receiving.device.gainscha

import android.util.Log
import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrintPayload
import com.jidan.receiving.device.PrinterIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * 佳博 GP-M32B 适配器（型号键 `gainscha-gp-m32b`）。
 *
 * 做的事：把 [PrintPayload] 的两种产物拼成发给打印机的字节，经 [GainschaSdk] 发出，
 * 再把状态码映射成 [PrintOutcome]、[PrinterIssue]。
 * - 文字指令（print.render=text）：TSPL 转 GB 码，[TsplBytes.encode]；
 * - 位图（print.render=bitmap）：PNG 转 1 位点阵，拼成 TSPL 的 BITMAP 指令，不依赖打印机字库与编码。
 * 每次发送前把 TSPL 指令文本打到 logcat（标签 [TAG]），供人工对照 TSPL 手册查语法（P8-2 验收）。
 *
 * 没做的事：与 SDK 的实际调用在 [GainschaSdk] 的实现（[SdkGainschaSdk]）里。
 *
 * 未经实物验证：没有真打印机，SDK 一层只在编译层面对过 aar 的签名。蓝牙连接方式（T-01）、状态位、一票一控是否可用，都留给 H-2。
 */
class GainschaPrinter(
    private val sdk: GainschaSdk,
    private val scope: CoroutineScope,
    initialPairedId: String? = null,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val pollMs: Long = POLL_MS,
) : LabelPrinter {
    private val _link = MutableStateFlow(LinkStatus.DISCONNECTED)
    private val _status = MutableStateFlow(PrinterIssue.OK)
    private val _paired = MutableStateFlow(initialPairedId)

    override val linkStatus: StateFlow<LinkStatus> = _link.asStateFlow()
    override val printerStatus: StateFlow<PrinterIssue> = _status.asStateFlow()
    override val pairedId: StateFlow<String?> = _paired.asStateFlow()

    /** 发送与状态询问共用一条蓝牙流，回复不能串。 */
    private val wire = Mutex()
    private var pollJob: Job? = null

    init {
        sdk.onDisconnected = {
            if (_link.value == LinkStatus.CONNECTED) lose()
        }
    }

    override suspend fun search(): List<DiscoveredDevice> =
        try {
            sdk.search()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("搜索失败：${e.message}")
            emptyList()
        }

    override suspend fun connect(id: String) {
        pollJob?.cancel()
        _link.value = LinkStatus.CONNECTING
        val ok = try {
            sdk.connect(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("连接失败：${e.message}")
            false
        }
        if (ok) {
            _paired.value = id
            _link.value = LinkStatus.CONNECTED
            startPolling()
        } else {
            // 已配对的设备连不上算“丢失”，LinkKeeper 会隔一会儿再连；新选的设备连不上就回到未连接
            _link.value = if (id == _paired.value) LinkStatus.LOST else LinkStatus.DISCONNECTED
        }
    }

    override suspend fun disconnect() {
        pollJob?.cancel()
        sdk.disconnect()
        _status.value = PrinterIssue.OK
        _link.value = LinkStatus.DISCONNECTED
    }

    override suspend fun print(payload: PrintPayload): PrintOutcome {
        if (_link.value != LinkStatus.CONNECTED) return PrintOutcome.Failure("打印机未连接")
        val bytes = try {
            build(payload)
        } catch (e: Exception) {
            return PrintOutcome.Failure("标签生成失败：${e.message}")
        }
        return wire.withLock {
            // 先问一次状态：缺纸、开盖在发送前就能拦住，不白发一份
            refreshStatus()
            if (_status.value != PrinterIssue.OK) {
                return@withLock PrintOutcome.Failure(GainschaCodes.reasonOf(_status.value))
            }
            val code = try {
                sdk.printTracked(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log("发送失败：${e.message}")
                lose()
                return@withLock PrintOutcome.Failure("发送失败：${e.message}")
            }
            log("一票一控状态码：${code?.let { "0x%02X".format(it and 0xFF) } ?: "无答复"}")
            GainschaCodes.issueOfTrack(code)?.let { _status.value = it }
            GainschaCodes.outcomeOf(code)
        }
    }

    /** 拼出要发的字节，并把指令文本打到日志。 */
    private fun build(payload: PrintPayload): ByteArray = when (payload) {
        is PrintPayload.Text -> {
            val bytes = TsplBytes.encode(payload.tspl)
            log("TSPL 文字指令（${bytes.size} 字节，GB18030）：\r\n${payload.tspl}")
            bytes
        }
        is PrintPayload.Bitmap -> {
            val built = TsplBytes.bitmapJob(PngRaster.decode(payload.png), payload.gapMm, payload.dotsPerMm)
            log("TSPL 位图指令（${built.bytes.size} 字节）：\r\n${built.logText}")
            built.bytes
        }
    }

    private fun lose() {
        pollJob?.cancel()
        _link.value = LinkStatus.LOST
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                wire.withLock { refreshStatus() }
                delay(pollMs)
            }
        }
    }

    /** 问 `<ESC>!?` 并更新状态标签；没有回复就保持原状。 */
    private suspend fun refreshStatus() {
        val reply = try {
            sdk.ask(GainschaCodes.STATUS_QUERY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val b = reply?.firstOrNull() ?: return
        _status.value = GainschaCodes.issueOf(b.toInt())
    }

    companion object {
        const val TAG = "GainschaPrinter"
        const val POLL_MS = 3000L
    }
}
