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

    fun timingPerBox(): Boolean =
        !options().get("print.timing").equals("after-confirm", ignoreCase = true)

    /** 逐箱：记完一批箱后入队。 */
    fun enqueueNew(seqs: List<Int>) {
        if (!enabled() || !timingPerBox()) return
        enqueue(seqs)
    }

    /** 重称 / 手录后立即重打该箱（逐箱模式）。 */
    fun enqueueReprint(seq: Int) {
        if (!enabled() || !timingPerBox()) return
        updateSession { it.markPrinted(seq, false) }
        enqueue(listOf(seq))
    }

    /** 确认计量后：完成后一次打印时打全部未打印；逐箱时只补打未打出的。 */
    fun enqueueAfterConfirm() {
        if (!enabled()) return
        val s = session() ?: return
        val seqs = if (timingPerBox()) {
            s.unprinted.map { it.seq }
        } else {
            s.boxes.map { it.seq }
        }
        if (seqs.isEmpty()) return
        enqueue(seqs)
    }

    /** 一键补打全部未打印。 */
    fun reprintUnprinted() {
        if (!enabled()) return
        val seqs = session()?.unprinted?.map { it.seq }.orEmpty()
        if (seqs.isEmpty()) return
        enqueue(seqs)
    }

    fun reprintOne(seq: Int) {
        if (!enabled()) return
        updateSession { it.markPrinted(seq, false) }
        enqueue(listOf(seq))
    }

    fun stop() {
        worker?.cancel()
        worker = null
        _job.value = null
    }

    fun unprintedCount(): Int =
        if (!enabled()) 0 else session()?.unprinted?.size ?: 0

    private fun enqueue(seqs: List<Int>) {
        if (seqs.isEmpty()) return
        // 已有任务时把新 seq 接到队尾：简化为取消后重跑「当前未打印 ∪ 请求」
        val pending = LinkedHashSet<Int>()
        session()?.unprinted?.mapTo(pending) { it.seq }
        pending.addAll(seqs)
        val ordered = session()?.boxes?.map { it.seq }?.filter { it in pending }.orEmpty()
        if (ordered.isEmpty()) return
        stop()
        worker = scope.launch { runQueue(ordered) }
    }

    private suspend fun runQueue(seqs: List<Int>) {
        _lastError.value = ""
        val total = seqs.size
        var done = 0
        _job.value = PrintJobState(done, total)
        for (seq in seqs) {
            val s = session() ?: break
            val box = try {
                s.box(seq)
            } catch (_: Throwable) {
                continue
            }
            if (box.printed) {
                done += 1
                _job.value = PrintJobState(done, total)
                continue
            }
            val ready = printerReady()
            if (ready != null) {
                _lastError.value = ready
                break
            }
            val data = labelData(s, box)
            val tpl = template()
            val mode = LabelRenderer.parseMode(options().get("print.render"))
            val payload = LabelRenderer.render(data, tpl, mode)
            _preview.value = LabelPreview(data, tpl.content, LabelRenderer.previewText(data, tpl.content))
            when (val outcome = printer.print(payload)) {
                is PrintOutcome.Success -> {
                    updateSession { it.markPrinted(seq, true) }
                    done += 1
                    _job.value = PrintJobState(done, total)
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
        buyDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.M.d")),
        warehouseName = s.warehouse.name,
        batchNo = s.batchNo,
        seq = box.seq,
    )

    companion object {
        fun timingIsPerBox(options: AppOptions): Boolean =
            !options.get("print.timing").equals("after-confirm", ignoreCase = true)
    }
}
