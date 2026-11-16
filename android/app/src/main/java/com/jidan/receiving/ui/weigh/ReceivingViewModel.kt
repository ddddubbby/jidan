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
import com.jidan.receiving.data.ApiError
import com.jidan.receiving.data.QuoteRequest
import com.jidan.receiving.data.QuoteResult
import com.jidan.receiving.data.RuleViewDto
import com.jidan.receiving.data.SubmitBoxRequest
import com.jidan.receiving.data.SubmitRequest
import com.jidan.receiving.data.SubmittedDto
import com.jidan.receiving.data.TareDto
import com.jidan.receiving.data.userMessage
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.printing.PrintJobState
import com.jidan.receiving.printing.PrintQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class ReceivingNav {
    data object ToSummary : ReceivingNav()
    data object BackToWeigh : ReceivingNav()
    data object ToDone : ReceivingNav()
    data object ToCreate : ReceivingNav()
}

data class RuleCandidate(
    val rule: RuleViewDto,
    val unitPrice: Double? = null,
)

/** 定价规则弹层（APP-09 / P7-3）。 */
data class RuleSheetState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String = "",
    val productName: String = "",
    val specName: String = "",
    val standardNetKg: Double? = null,
    val basePrice: Double? = null,
    val boundRuleId: Long? = null,
    val boundRuleName: String? = null,
    val boundCoef: Double? = null,
    val bindCount: Int = 0,
    val draftCoef: Double? = null,
    val trialUnitPrice: Double? = null,
    val boundUnitPrice: Double? = null,
    val changing: Boolean = false,
    val candidates: List<RuleCandidate> = emptyList(),
) {
    val unbound: Boolean get() = boundRuleId == null
    val displayCoef: Double? get() = draftCoef ?: boundCoef
    val dirty: Boolean
        get() {
            val d = draftCoef ?: return false
            val b = boundCoef ?: return false
            return abs(d - b) > 1e-9
        }
}

data class SummaryUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val m0Retried: Boolean = false,
    val quote: QuoteResult? = null,
    val error: String = "",
    val farmerName: String = "",
    val batchNo: String = "",
    val warehouseName: String = "",
    val productName: String = "",
    val specName: String = "",
    val unprinted: Int = 0,
    val printEnabled: Boolean = true,
    val printError: String = "",
    val submitting: Boolean = false,
    val submitError: Boolean = false,
    /** `submit.without-price = allow` 时缺价也可提交，金额显示「待定价」。 */
    val allowWithoutPrice: Boolean = false,
)

data class DoneUiState(
    val farmerName: String = "",
    val productName: String = "",
    val specName: String = "",
    val batchNo: String = "",
    val boxCount: Int = 0,
    val totalNetKg: Double? = null,
    val avgKg: Double? = null,
    val unitPrice: Double? = null,
    val amount: Double? = null,
    val pendingPrice: Boolean = false,
    val unprinted: Int = 0,
    val printEnabled: Boolean = true,
    val printError: String = "",
    /** 防连点：提交与「再收一单」同位置（原型 500ms）。 */
    val readyAgainAt: Long = 0L,
)

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
    val printEnabled: Boolean = true,
    val printJob: PrintJobState? = null,
    val unprinted: Int = 0,
    val printError: String = "",
    val labelPreview: String = "",
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

    private val _summary = MutableStateFlow(SummaryUiState())
    val summary: StateFlow<SummaryUiState> = _summary.asStateFlow()

    private val _ruleSheet = MutableStateFlow(RuleSheetState())
    val ruleSheet: StateFlow<RuleSheetState> = _ruleSheet.asStateFlow()

    private val _done = MutableStateFlow(DoneUiState())
    val done: StateFlow<DoneUiState> = _done.asStateFlow()

    private val _nav = MutableSharedFlow<ReceivingNav>(extraBufferCapacity = 1)
    val nav: SharedFlow<ReceivingNav> = _nav.asSharedFlow()

    private var session: Session? = null
    private var machine: WeighingMachine? = null
    private var tickJob: Job? = null
    private var eventClearJob: Job? = null
    private var scaleBound = false
    private var confirmed = false

    val printQueue = PrintQueue(
        printer = container.printer,
        scope = viewModelScope,
        options = { container.options.value },
        session = { session },
        updateSession = { transform ->
            session = session?.let(transform)
            persist()
            publish()
        },
    )

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
        printQueue.enqueueReprint(seq)
    }

    fun addManual(netKg: Double) {
        val before = session?.boxes?.map { it.seq }.orEmpty().toSet()
        val s = session ?: return
        session = s.addManual(Kg.of(netKg))
        flash("已手工加一箱", ok = true)
        persist()
        publish()
        val added = session?.boxes?.map { it.seq }.orEmpty().filter { it !in before }
        printQueue.enqueueNew(added)
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
        val group = s.box(seq).group
        session = s.regroup(seq, k)
        flash("已改成 $k 箱", ok = true)
        persist()
        publish()
        val fresh = session?.boxes?.filter { it.group == group }?.map { it.seq }.orEmpty()
        printQueue.enqueueNew(fresh)
    }

    fun reprintUnprinted() = printQueue.reprintUnprinted()

    fun reprintOne(seq: Int) = printQueue.reprintOne(seq)

    /** 确认计量完毕：完成后一次打印入队，拉计价，进汇总页（APP-08）。 */
    fun onConfirmWeighing() {
        if (session?.boxes.isNullOrEmpty()) return
        confirmed = true
        printQueue.enqueueAfterConfirm()
        viewModelScope.launch {
            refreshQuote(resetM0Retried = true)
            _nav.emit(ReceivingNav.ToSummary)
        }
    }

    /** 返回计量：解锁流水继续称（原型 `back`）。 */
    fun backToWeigh() {
        confirmed = false
        viewModelScope.launch { _nav.emit(ReceivingNav.BackToWeigh) }
    }

    /** 提交单据（APP-10）。失败保留流水；登录失效由 MainActivity 回登录，草稿不丢。 */
    fun submitOrder() {
        val s = session ?: return
        if (_summary.value.submitting) return
        val allow = container.options.value.get("submit.without-price").equals("allow", ignoreCase = true)
        val q = _summary.value.quote
        if (!allow && q != null && (q.noBasePrice || q.noRule)) return
        viewModelScope.launch {
            _summary.update { it.copy(submitting = true, submitError = false, error = "") }
            try {
                val groups = s.boxes.groupingBy { it.group }.eachCount()
                val submitted = container.orders.submit(
                    SubmitRequest(
                        batchNo = s.batchNo,
                        vendorId = s.farmer.id,
                        productId = s.product.id,
                        specId = s.spec.id,
                        warehouseId = s.warehouse.id,
                        boxes = s.boxes.map { b ->
                            SubmitBoxRequest(
                                seq = b.seq,
                                netKg = b.net.toDouble(),
                                grossKg = b.gross?.toDouble(),
                                tareKg = b.tare.toDouble(),
                                tareName = b.tareName.ifBlank { null },
                                groupNo = b.group,
                                groupK = groups[b.group],
                                manual = b.manual,
                                rawKg = b.raw?.toDouble(),
                                printed = b.printed,
                            )
                        },
                    ),
                )
                finishSubmit(s, submitted, q)
            } catch (e: Throwable) {
                if (e is ApiError && (e.code == "UNAUTHENTICATED" || e.status == 401)) {
                    container.resumeSummaryAfterLogin = true
                }
                _summary.update {
                    it.copy(
                        submitting = false,
                        submitError = true,
                        error = e.userMessage().ifBlank { "提交失败：连不上服务器" },
                    )
                }
            }
        }
    }

    /** 再收一单：清完成态，回建单页。 */
    fun again() {
        val readyAt = _done.value.readyAgainAt
        if (System.currentTimeMillis() < readyAt) return
        printQueue.stop()
        session = null
        machine = null
        tickJob?.cancel()
        scaleBound = false
        confirmed = false
        container.orderDraft.clear()
        container.sessionStore.clear()
        _ui.value = WeighUiState()
        _summary.value = SummaryUiState()
        _ruleSheet.value = RuleSheetState()
        _done.value = DoneUiState()
        viewModelScope.launch { _nav.emit(ReceivingNav.ToCreate) }
    }

    private fun finishSubmit(s: Session, submitted: SubmittedDto, quote: QuoteResult?) {
        val pending = submitted.submittedAmount == null && submitted.submittedPrice == null
        val unprinted = printQueue.unprintedCount()
        val printEnabled = printQueue.enabled()
        val printError = printQueue.lastError.value
        _done.value = DoneUiState(
            farmerName = s.farmer.name,
            productName = s.product.name,
            specName = s.spec.name,
            batchNo = submitted.batchNo ?: s.batchNo,
            boxCount = submitted.boxCount.takeIf { it > 0 } ?: s.boxes.size,
            totalNetKg = submitted.totalNetKg ?: quote?.totalKg,
            avgKg = submitted.avgKg ?: quote?.avgKg,
            unitPrice = submitted.submittedPrice ?: quote?.unitPrice,
            amount = submitted.submittedAmount ?: quote?.amount,
            pendingPrice = pending,
            unprinted = unprinted,
            printEnabled = printEnabled,
            printError = printError,
            readyAgainAt = System.currentTimeMillis() + 500,
        )
        // 提交成功清草稿；完成页只用 DoneUiState
        printQueue.stop()
        container.sessionStore.clear()
        container.orderDraft.clear()
        session = null
        machine = null
        tickJob?.cancel()
        scaleBound = false
        confirmed = false
        _ui.value = WeighUiState()
        _summary.update { it.copy(submitting = false, submitError = false) }
        viewModelScope.launch { _nav.emit(ReceivingNav.ToDone) }
    }

    /** 重新拉当日基准价再计价（原型 `refreshM0`）。 */
    fun refreshBasePrice() {
        viewModelScope.launch {
            _summary.update { it.copy(refreshing = true, error = "") }
            runCatching { container.catalog.basePriceBySpec() }
            refreshQuote(resetM0Retried = false)
            val stillMissing = _summary.value.quote?.noBasePrice == true
            _summary.update { it.copy(refreshing = false, m0Retried = stillMissing) }
        }
    }

    /** 规则弹层改绑或改系数后重算。 */
    fun reloadQuote() {
        viewModelScope.launch { refreshQuote(resetM0Retried = false) }
    }

    /** 打开规则弹层时加载候选与本单试算价。 */
    fun loadRuleSheet() {
        viewModelScope.launch { refreshRuleSheet(keepChanging = false) }
    }

    fun toggleChangeRule() {
        _ruleSheet.update { it.copy(changing = !it.changing, error = "") }
    }

    fun adjustDraftCoef(delta: Double) {
        val cur = _ruleSheet.value.displayCoef ?: return
        val next = max(0.0, ((cur + delta) * 10).roundToInt() / 10.0)
        _ruleSheet.update { it.copy(draftCoef = next, error = "") }
        viewModelScope.launch { previewTrialCoef(next) }
    }

    fun revertDraftCoef() {
        _ruleSheet.update {
            it.copy(draftCoef = null, trialUnitPrice = it.boundUnitPrice, error = "")
        }
    }

    fun saveDraftCoef() {
        val state = _ruleSheet.value
        val ruleId = state.boundRuleId ?: return
        val coef = state.draftCoef ?: return
        viewModelScope.launch {
            _ruleSheet.update { it.copy(busy = true, error = "") }
            try {
                container.catalog.updateRuleCoef(ruleId, coef)
                refreshQuote(resetM0Retried = false)
                refreshRuleSheet(keepChanging = false)
            } catch (e: Throwable) {
                _ruleSheet.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    fun unbindRule() {
        val s = session ?: return
        viewModelScope.launch {
            _ruleSheet.update { it.copy(busy = true, error = "") }
            try {
                container.catalog.bindFarmerRule(s.farmer.id, s.product.id, s.spec.id, null)
                refreshQuote(resetM0Retried = false)
                refreshRuleSheet(keepChanging = false)
            } catch (e: Throwable) {
                _ruleSheet.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    fun bindRule(ruleId: Long) {
        val s = session ?: return
        viewModelScope.launch {
            _ruleSheet.update { it.copy(busy = true, error = "") }
            try {
                container.catalog.bindFarmerRule(s.farmer.id, s.product.id, s.spec.id, ruleId)
                refreshQuote(resetM0Retried = false)
                refreshRuleSheet(keepChanging = false)
            } catch (e: Throwable) {
                _ruleSheet.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    private suspend fun previewTrialCoef(coef: Double) {
        val s = session ?: return
        try {
            val q = container.orders.quote(
                QuoteRequest(
                    vendorId = s.farmer.id,
                    productId = s.product.id,
                    specId = s.spec.id,
                    boxNetKg = s.boxes.map { it.net.toDouble() },
                    trialCoef = coef,
                ),
            )
            _ruleSheet.update { it.copy(trialUnitPrice = q.unitPrice) }
        } catch (_: Throwable) {
            // 预览失败不挡改系数；保存时再报错
        }
    }

    private suspend fun refreshRuleSheet(keepChanging: Boolean) {
        val s = session ?: return
        val changing = if (keepChanging) _ruleSheet.value.changing else false
        _ruleSheet.update {
            it.copy(
                loading = true,
                busy = false,
                error = "",
                changing = changing,
                draftCoef = null,
                productName = s.product.name,
                specName = s.spec.name,
            )
        }
        try {
            if (_summary.value.quote == null) {
                refreshQuote(resetM0Retried = false)
            }
            val quote = _summary.value.quote
            val rules = container.catalog.buyRules(s.product.id, s.spec.id)
            val boundId = quote?.ruleId
            val bound = rules.firstOrNull { it.id == boundId }
                ?: rules.firstOrNull { it.id == quote?.ruleId }
            val others = rules.filter { it.id != boundId }
            val priced = others.map { r ->
                val unit = runCatching {
                    container.orders.quote(
                        QuoteRequest(
                            vendorId = s.farmer.id,
                            productId = s.product.id,
                            specId = s.spec.id,
                            boxNetKg = s.boxes.map { it.net.toDouble() },
                            trialRuleId = r.id,
                        ),
                    ).unitPrice
                }.getOrNull()
                RuleCandidate(r, unit)
            }
            _ruleSheet.update {
                it.copy(
                    loading = false,
                    busy = false,
                    standardNetKg = quote?.standardNetKg,
                    basePrice = quote?.basePrice,
                    boundRuleId = bound?.id ?: boundId,
                    boundRuleName = bound?.name ?: quote?.ruleName,
                    boundCoef = bound?.coef ?: quote?.coefficient,
                    bindCount = bound?.bindCount ?: 0,
                    draftCoef = null,
                    trialUnitPrice = quote?.unitPrice,
                    boundUnitPrice = quote?.unitPrice,
                    candidates = priced,
                    changing = changing && boundId != null,
                )
            }
        } catch (e: Throwable) {
            _ruleSheet.update { it.copy(loading = false, busy = false, error = e.userMessage()) }
        }
    }

    private suspend fun refreshQuote(resetM0Retried: Boolean) {
        val s = session ?: return
        val allow = container.options.value.get("submit.without-price").equals("allow", ignoreCase = true)
        _summary.update {
            it.copy(
                loading = true,
                error = "",
                submitError = false,
                allowWithoutPrice = allow,
                m0Retried = if (resetM0Retried) false else it.m0Retried,
                farmerName = s.farmer.name,
                batchNo = s.batchNo,
                warehouseName = s.warehouse.name,
                productName = s.product.name,
                specName = s.spec.name,
                unprinted = printQueue.unprintedCount(),
                printEnabled = printQueue.enabled(),
                printError = printQueue.lastError.value,
            )
        }
        try {
            val quote = container.orders.quote(
                QuoteRequest(
                    vendorId = s.farmer.id,
                    productId = s.product.id,
                    specId = s.spec.id,
                    boxNetKg = s.boxes.map { it.net.toDouble() },
                ),
            )
            _summary.update {
                it.copy(
                    loading = false,
                    quote = quote,
                    unprinted = printQueue.unprintedCount(),
                    printEnabled = printQueue.enabled(),
                    printError = printQueue.lastError.value,
                )
            }
        } catch (e: Throwable) {
            _summary.update {
                it.copy(loading = false, error = e.userMessage(), quote = null)
            }
        }
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
        printQueue.stop()
        container.sessionStore.clear()
        clear()
    }

    private fun bindScale() {
        if (scaleBound) return
        scaleBound = true
        viewModelScope.launch {
            printQueue.job.collect { publish() }
        }
        viewModelScope.launch {
            printQueue.preview.collect { publish() }
        }
        viewModelScope.launch {
            printQueue.lastError.collect { publish() }
        }
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
        printQueue.stop()
        session = null
        machine = null
        tickJob?.cancel()
        scaleBound = false
        confirmed = false
        container.orderDraft.clear()
        _ui.value = WeighUiState()
        _summary.value = SummaryUiState()
        _ruleSheet.value = RuleSheetState()
        _done.value = DoneUiState()
    }

    private fun applyOutcome(outcome: WeighOutcome?) {
        if (outcome == null) return
        val beforeSeqs = session?.boxes?.map { it.seq }.orEmpty().toSet()
        val s = session ?: return
        session = s.apply(outcome)
        persist()
        val text = when (outcome) {
            is WeighOutcome.Recorded -> {
                val added = session?.boxes?.map { it.seq }.orEmpty().filter { it !in beforeSeqs }
                printQueue.enqueueNew(added)
                val k = outcome.weighing.k
                if (k > 1) "已记录 $k 箱" else "已记录"
            }
            is WeighOutcome.Replaced -> {
                printQueue.enqueueReprint(outcome.seq)
                "第 ${outcome.seq} 箱已重称"
            }
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
                printEnabled = printQueue.enabled(),
                printJob = printQueue.job.value,
                unprinted = printQueue.unprintedCount(),
                printError = printQueue.lastError.value,
                labelPreview = printQueue.preview.value?.text.orEmpty(),
            )
        }
        if (confirmed) {
            _summary.update {
                it.copy(
                    unprinted = printQueue.unprintedCount(),
                    printEnabled = printQueue.enabled(),
                    printError = printQueue.lastError.value,
                )
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReceivingViewModel(container) as T
    }
}
