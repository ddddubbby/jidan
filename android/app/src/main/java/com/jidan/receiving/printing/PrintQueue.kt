package com.jidan.receiving.printing

import com.jidan.receiving.core.Box
import com.jidan.receiving.core.Session
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrinterIssue
import com.jidan.receiving.device.label.LabelContent
import com.jidan.receiving.device.label.LabelData
import com.jidan.receiving.device.label.LabelGeometry
import com.jidan.receiving.device.label.LabelRenderer
import com.jidan.receiving.device.label.LabelTemplate
import com.jidan.receiving.device.label.RenderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class PrintJobState(
    val done: Int,
    val total: Int,
)

data class LabelPreview(
    val data: LabelData,
    val content: LabelContent,
    val text: String,
)

/**
 * 打印队列（开发规格 9.3 / APP-07）。
 * `print.enabled=off` 时整队不工作；失败即停，未打出的保持未打印。
 *
 * 队列里没有序号清单：工作协程每次取流水里序号最小的未打印箱来打，打完再取，直到没有或失败。
 * 所以任何入队动作都只是“叫醒工作协程”；已经在打时不取消、不重来，新增或作废的箱自然排到后面（先进先出）。
 * 这样删箱、改箱数后序号顺延也不会让队列里记的序号对不上。
 */
class PrintQueue(
    private val printer: LabelPrinter,
    private val scope: CoroutineScope,
    private val options: () -> AppOptions,
    private val session: () -> Session?,
    private val updateSession: ((Session) -> Session) -> Unit,
) {
    private val _job = MutableStateFlow<PrintJobState?>(null)
    val job: StateFlow<PrintJobState?> = _job.asStateFlow()

    private val _preview = MutableStateFlow<LabelPreview?>(null)
    val preview: StateFlow<LabelPreview?> = _preview.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private var worker: Job? = null

    fun enabled(): Boolean = options().get("print.enabled").equals("on", ignoreCase = true)

    fun timingPerBox(): Boolean = timingIsPerBox(options())

    /** 逐箱：记完一批箱后入队。 */
    fun enqueueNew(seqs: List<Int>) {
        if (seqs.isEmpty() || !enabled() || !timingPerBox()) return
        wake()
    }

    /**
     * 重称 / 手录后该箱原标签作废：清打印标记。逐箱模式立即重打；
     * 完成后一次打印的模式留到再次确认计量完毕时重打（APP-07）。
     */
    fun enqueueReprint(seq: Int) {
        if (!enabled()) return
        updateSession { it.markPrinted(seq, false) }
        if (timingPerBox()) wake()
    }

    /**
     * 删箱、改箱数后，序号变了的箱已被清掉打印标记（见 `SplitBoxes.renumber`）。
     * 逐箱模式立即重打它们；完成后一次打印的模式留到再次确认。
     */
    fun enqueueRenumbered() {
        if (!enabled() || !timingPerBox()) return
        wake()
    }

    /** 确认计量后：逐箱时补打未打出的；完成后一次打印时打全部（确认前没有箱被打过，调整过的箱已清标记）。 */
    fun enqueueAfterConfirm() {
        if (!enabled()) return
        wake()
    }

    /** 一键补打全部未打印。 */
    fun reprintUnprinted() {
        if (!enabled()) return
        wake()
    }

    fun reprintOne(seq: Int) {
        if (!enabled()) return
        updateSession { it.markPrinted(seq, false) }
        wake()
    }

    /** 放弃整单或再收一单时才停；提交不停（尚未打完的接着打）。 */
    fun stop() {
        worker?.cancel()
        worker = null
        _job.value = null
        _lastError.value = ""
        _preview.value = null
    }

    fun unprintedCount(): Int =
        if (!enabled()) 0 else session()?.unprinted?.size ?: 0

    /** 此刻打印机挡在哪（未连接、缺纸、开盖、过热）；能打返回 null。看当前状态，不看上一次的失败。 */
    fun blocker(): String? = printerReady()

    private fun wake() {
        if (worker?.isActive == true) return
        if (session()?.unprinted.isNullOrEmpty()) return
        worker = scope.launch { runQueue() }
    }

    private suspend fun runQueue() {
        _lastError.value = ""
        var done = 0
        while (true) {
            val s = session() ?: break
            val box = s.unprinted.firstOrNull() ?: break
            _job.value = PrintJobState(done, done + s.unprinted.size)
            val ready = printerReady()
            if (ready != null) {
                _lastError.value = ready
                break
            }
            val data = labelData(s, box)
            val tpl = template()
            val mode = LabelRenderer.parseMode(options().get("print.render"))
            val o = options()
            val geo = LabelGeometry.parse(o.get("print.dpi"), o.get("print.offset-x-mm"), o.get("print.offset-y-mm"))
            val payload = LabelRenderer.render(data, tpl, mode, geo)
            _preview.value = LabelPreview(data, tpl.content, LabelRenderer.previewText(data, tpl.content))
            when (val outcome = printer.print(payload)) {
                is PrintOutcome.Success -> {
                    // 打印途中该箱若被删、被改（序号或内容变了），出来的是旧标签，不能把新箱记成已打
                    updateSession { cur ->
                        if (cur.boxes.firstOrNull { it.seq == box.seq } == box) cur.markPrinted(box.seq, true) else cur
                    }
                    done += 1
                    delay(60)
                }
                is PrintOutcome.Failure -> {
                    _lastError.value = outcome.reason
                    break
                }
            }
        }
        _job.value = null
        worker = null
    }

    private fun printerReady(): String? {
        if (printer.linkStatus.value != LinkStatus.CONNECTED) return "打印机未连接"
        return when (printer.printerStatus.value) {
            PrinterIssue.OK -> null
            PrinterIssue.NO_PAPER -> "缺纸"
            PrinterIssue.OPEN_COVER -> "开盖"
            PrinterIssue.OVERHEAT -> "过热"
        }
    }

    private fun template(): LabelTemplate {
        val o = options()
        val content = LabelTemplate.parseContent(o.get("label.content"))
        val w = o.get("label.width-mm").toFloatOrNull() ?: 60f
        val h = o.get("label.height-mm").toFloatOrNull() ?: 40f
        val g = o.get("label.gap-mm").toFloatOrNull() ?: 2f
        return LabelTemplate.of(content, w, h, g)
    }

    private fun labelData(s: Session, box: Box): LabelData = LabelData(
        productName = s.product.name,
        specName = s.spec.name,
        net = box.net,
        farmerName = s.farmer.name,
        // 收购日期取批次号里的日期，跨天补打也不变（批次号 `SG20261116-1`）
        buyDate = LabelData.buyDateOf(s.batchNo)
            ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.M.d")),
        warehouseName = s.warehouse.name,
        batchNo = s.batchNo,
        seq = box.seq,
    )

    companion object {
        fun timingIsPerBox(options: AppOptions): Boolean =
            !options.get("print.timing").equals("after-confirm", ignoreCase = true)
    }
}
