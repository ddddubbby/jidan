package com.jidan.receiving.ui.weigh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jidan.receiving.AppContainer
import com.jidan.receiving.core.Box
import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.RecordBy
import com.jidan.receiving.core.RoundAvg
import com.jidan.receiving.core.RoundRule
import com.jidan.receiving.core.ScaleReading
import com.jidan.receiving.core.Session
import com.jidan.receiving.core.StableBy
import com.jidan.receiving.core.Tare
import com.jidan.receiving.core.TareBy
import com.jidan.receiving.core.WeighOutcome
import com.jidan.receiving.core.WeighPhase
import com.jidan.receiving.core.WeighingMachine
import com.jidan.receiving.core.sum
import com.jidan.receiving.data.TareDto
import com.jidan.receiving.data.userMessage
import com.jidan.receiving.device.LinkStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WeighUiState(
    val ready: Boolean = false,
    val farmerName: String = "",
    val productName: String = "",
    val specName: String = "",
    val batchNo: String = "",
    val warehouseName: String = "",
    val phase: WeighPhase = WeighPhase.LOCKED,
    val displayNet: Kg? = null,
    val k: Int = 1,
    val candidates: Pair<Int, Int>? = null,
    val tare: Tare? = null,
    val tares: List<TareDto> = emptyList(),
    val showTarePicker: Boolean = false,
    val boxes: List<Box> = emptyList(),
    val reweighSeq: Int? = null,
    val eventText: String = "",
    val eventOk: Boolean = true,
    val recordByButton: Boolean = false,
    val canPressRecord: Boolean = false,
    val scaleConnected: Boolean = false,
    val scaleLost: Boolean = false,
    val error: String = "",
    val loading: Boolean = false,
) {
    val count: Int get() = boxes.size
    val totalNet: Kg get() = boxes.map { it.net }.sum()
    fun avg(rule: RoundRule): Kg? =
        if (count == 0) null else RoundAvg.of(totalNet, count, rule)
    val recent: List<Box>
        get() {
            if (boxes.isEmpty()) return emptyList()
            val lastGroup = boxes.last().group
            val grp = boxes.filter { it.group == lastGroup }
            val n = maxOf(4, grp.size)
            return boxes.takeLast(n).reversed()
        }
}

/**
 * Activity 级：持有 [Session] + [WeighingMachine]（开发规格 9.4）。
 * P6-4 称重；P6-5 再接流水弹层与草稿落盘。
 */
class ReceivingViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _ui = MutableStateFlow(WeighUiState())
    val ui: StateFlow<WeighUiState> = _ui.asStateFlow()

    private var session: Session? = null
    private var machine: WeighingMachine? = null
    private var tickJob: Job? = null
    private var eventClearJob: Job? = null
    private var scaleBound = false

    val roundRule: RoundRule
        get() = runCatching {
            RoundRule.fromWire(container.options.value.get("round.mode"))
        }.getOrDefault(RoundRule.DEFAULT)

    /** 有本地草稿时恢复；否则从建单 OrderDraft 开新单。 */
    fun startFromDraft() {
        if (session != null) {
            publish()
            return
        }
        val saved = container.sessionStore.load()
        if (saved != null) {
            restoreSession(saved)
            return
        }
        val h = container.orderDraft.header.value
        if (!h.ready) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = "") }
            try {
                val batch = container.catalog.nextBatchNo()
                val tares = runCatching { container.catalog.tareHotkeys() }.getOrDefault(emptyList())
                val std = Kg(h.stdNetCents ?: Kg.of(36.0).cents) // 粉蛋默认 36kg，缺字段时兜底
                val opts = container.options.value.weighOptions()
                val s = Session(
                    batchNo = batch,
                    farmer = h.farmer!!,
                    product = h.product!!,
                    spec = h.spec!!,
                    warehouse = h.warehouse!!,
                )
                session = s
                machine = WeighingMachine(opts, std)
                val lastTareId = container.prefs.lastTareId
                val last = tares.firstOrNull { it.id == lastTareId }
                if (last != null) {
                    applyTare(Tare(Kg.of(last.tareKg), last.name), last.id, persistOnly = false)
                }
                _ui.update {
                    it.copy(
                        loading = false,
                        ready = true,
                        farmerName = s.farmer.name,
                        productName = s.product.name,
                        specName = s.spec.name,
                        batchNo = s.batchNo,
                        warehouseName = s.warehouse.name,
                        tares = tares,
                        showTarePicker = last == null,
                        recordByButton = opts.recordBy == RecordBy.BUTTON,
                    )
                }
                persist()
                publish()
                bindScale()
            } catch (e: Throwable) {
                _ui.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    private fun restoreSession(s: Session) {
        viewModelScope.launch {
            val tares = runCatching { container.catalog.tareHotkeys() }.getOrDefault(emptyList())
            val std = Kg(
                container.orderDraft.header.value.stdNetCents
                    ?: s.spec.let { Kg.of(36.0).cents },
            )
            val opts = container.options.value.weighOptions()
            session = s
            machine = WeighingMachine(opts, std).also { m ->
                s.tare?.let { m.setTare(it) }
            }
            // 把表头写回 OrderDraft，便于返回建单页仍可见
            container.orderDraft.update {
                it.copy(
                    farmer = s.farmer,
                    product = s.product,
                    spec = s.spec,
                    warehouse = s.warehouse,
                )
            }
            _ui.update {
                it.copy(
                    loading = false,
                    ready = true,
                    farmerName = s.farmer.name,
                    productName = s.product.name,
                    specName = s.spec.name,
                    batchNo = s.batchNo,
                    warehouseName = s.warehouse.name,
                    tares = tares,
                    showTarePicker = s.tare == null,
                    recordByButton = opts.recordBy == RecordBy.BUTTON,
                )
            }
            publish()
            bindScale()
        }
    }

    fun hasBoxes(): Boolean = (session?.boxes?.isNotEmpty() == true)

    fun editManual(seq: Int, netKg: Double) {
        val s = session ?: return
        session = s.editManual(seq, Kg.of(netKg))
        flash("第 $seq 箱已手录", ok = true)
        persist()
        publish()
    }

    fun addManual(netKg: Double) {
        val s = session ?: return
        session = s.addManual(Kg.of(netKg))
        flash("已手工加一箱", ok = true)
        persist()
        publish()
    }

    fun deleteBox(seq: Int) {
        val s = session ?: return
        session = s.delete(seq)
        flash("已删除", ok = true)
        persist()
        publish()
    }

    fun regroup(seq: Int, k: Int) {
        val s = session ?: return
        if (!s.canRegroup(seq)) return
        session = s.regroup(seq, k)
        flash("已改成 $k 箱", ok = true)
        persist()
        publish()
    }

    fun canRegroup(seq: Int): Boolean = session?.canRegroup(seq) == true

    fun box(seq: Int): Box? = try {
        session?.box(seq)
    } catch (_: Throwable) {
        null
    }

    fun groupOf(seq: Int): List<Box> {
        val s = session ?: return emptyList()
        val g = try {
            s.box(seq).group
        } catch (_: Throwable) {
            return emptyList()
        }
        return s.boxes.filter { it.group == g }
    }

    /** 放弃整单：清草稿与内存，回到建单。 */
    fun abandon() {
        container.sessionStore.clear()
        clear()
    }

    private fun bindScale() {
        if (scaleBound) return
        scaleBound = true
        viewModelScope.launch {
            container.scale.linkStatus.collect { st ->
                val connected = st == LinkStatus.CONNECTED
                val lost = st == LinkStatus.LOST ||
                    (st == LinkStatus.CONNECTING && (session?.boxes?.isNotEmpty() == true))
                if (!connected) machine?.resetScale()
                _ui.update { it.copy(scaleConnected = connected, scaleLost = lost) }
                publish()
            }
        }
        viewModelScope.launch {
            container.scale.readings.collect { r ->
                if (r == null) return@collect
                val outcome = machine?.onReading(ScaleReading(r.raw, r.stable), System.currentTimeMillis())
                applyOutcome(outcome)
                publish()
            }
        }
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                val outcome = machine?.onTick(System.currentTimeMillis())
                if (outcome != null) {
                    applyOutcome(outcome)
                    publish()
                } else if (container.options.value.weighOptions().stableBy == StableBy.APP) {
                    publish()
                }
            }
        }
    }

    fun openTarePicker() {
        _ui.update { it.copy(showTarePicker = true) }
    }

    fun selectTare(dto: TareDto) {
        viewModelScope.launch {
            applyTare(Tare(Kg.of(dto.tareKg), dto.name), dto.id, persistOnly = false)
            _ui.update { it.copy(showTarePicker = false) }
            persist()
            publish()
        }
    }

    private suspend fun applyTare(t: Tare, id: Long?, persistOnly: Boolean) {
        val opts = container.options.value.weighOptions()
        if (!persistOnly && opts.tareBy == TareBy.SCALE) {
            try {
                container.scale.setDigitalTare(t.unit)
            } catch (_: Throwable) {
                // Fake/占位可能未实现；APP 扣皮路径仍可用
            }
        }
        machine?.setTare(t)
        session = session?.withTare(t)
        if (id != null) container.prefs.lastTareId = id
    }

    fun pressRecord() {
        applyOutcome(machine?.press())
        publish()
    }

    fun chooseK(k: Int) {
        applyOutcome(machine?.choose(k))
        publish()
    }

    fun startReweigh(seq: Int) {
        machine?.startReweigh(seq)
        flash("重称第 $seq 箱", ok = true)
        publish()
    }

    fun cancelReweigh() {
        machine?.cancelReweigh()
        publish()
    }

    fun clear() {
        session = null
        machine = null
        tickJob?.cancel()
        scaleBound = false
        container.orderDraft.clear()
        _ui.value = WeighUiState()
    }

    private fun applyOutcome(outcome: WeighOutcome?) {
        if (outcome == null) return
        val s = session ?: return
        session = s.apply(outcome)
        persist()
        val text = when (outcome) {
            is WeighOutcome.Recorded -> {
                val k = outcome.weighing.k
                if (k > 1) "已记录 $k 箱" else "已记录"
            }
            is WeighOutcome.Replaced -> "第 ${outcome.seq} 箱已重称"
        }
        flash(text, ok = true)
    }

    private fun persist() {
        session?.let { container.sessionStore.save(it) }
    }

    private fun flash(text: String, ok: Boolean) {
        _ui.update { it.copy(eventText = text, eventOk = ok) }
        eventClearJob?.cancel()
        eventClearJob = viewModelScope.launch {
            delay(1600)
            _ui.update { it.copy(eventText = "") }
        }
    }

    private fun publish() {
        val s = session
        val m = machine
        val opts = container.options.value.weighOptions()
        _ui.update {
            it.copy(
                ready = s != null,
                farmerName = s?.farmer?.name.orEmpty(),
                productName = s?.product?.name.orEmpty(),
                specName = s?.spec?.name.orEmpty(),
                batchNo = s?.batchNo.orEmpty(),
                warehouseName = s?.warehouse?.name.orEmpty(),
                phase = m?.phase ?: WeighPhase.LOCKED,
                displayNet = m?.displayNet,
                k = m?.k ?: 1,
                candidates = m?.candidates,
                tare = m?.tare ?: s?.tare,
                boxes = s?.boxes.orEmpty(),
                reweighSeq = m?.reweighSeq,
                recordByButton = opts.recordBy == RecordBy.BUTTON,
                canPressRecord = m?.canPress == true,
                scaleConnected = container.scale.linkStatus.value == LinkStatus.CONNECTED,
            )
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReceivingViewModel(container) as T
    }
}
