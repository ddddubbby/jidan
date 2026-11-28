package com.jidan.receiving.ui.weigh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jidan.receiving.AppContainer
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.core.Box
import com.jidan.receiving.core.EmptyBoxState
import com.jidan.receiving.core.EmptyBoxTare
import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.RecordBy
import com.jidan.receiving.core.RoundAvg
import com.jidan.receiving.core.RoundRule
import com.jidan.receiving.core.Session
import com.jidan.receiving.core.Tare
import com.jidan.receiving.core.WeighOutcome
import com.jidan.receiving.core.WeighPhase
import com.jidan.receiving.core.WeighingMachine
import com.jidan.receiving.core.sum
import com.jidan.receiving.data.ApiError
import com.jidan.receiving.data.QuoteResult
import com.jidan.receiving.data.RuleViewDto
import com.jidan.receiving.data.SubmitBoxRequest
import com.jidan.receiving.data.SubmitRequest
import com.jidan.receiving.data.SubmittedDto
import com.jidan.receiving.data.TareDto
import com.jidan.receiving.data.quoteRequestOf
import com.jidan.receiving.data.userMessage
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.printing.PrintJobState
import com.jidan.receiving.printing.PrintQueue
import com.jidan.receiving.ui.adapt.AdaptFixtures
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

/**
 * 定价规则弹层（APP-09 / P7-3）。规则不绑农户：这里看这张单选的规则、改每斤尾差与每箱损耗、
 * 换成同商品规格的另一条（换了不动已称的箱，附录 E R-1 行）。
 */
data class RuleSheetState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String = "",
    val productName: String = "",
    val specName: String = "",
    val standardNetJin: Double? = null,
    val basePrice: Double? = null,
    val boundRuleId: Long? = null,
    val boundRuleName: String? = null,
    val boundCoef: Double? = null,
    val boundLoss: Double? = null,
    val draftCoef: Double? = null,
    val draftLoss: Double? = null,
    val trialUnitPrice: Double? = null,
    val boundUnitPrice: Double? = null,
    val changing: Boolean = false,
    val candidates: List<RuleCandidate> = emptyList(),
) {
    val unbound: Boolean get() = boundRuleId == null
    val displayCoef: Double? get() = draftCoef ?: boundCoef
    val displayLoss: Double get() = draftLoss ?: boundLoss ?: 0.0
    val dirty: Boolean
        get() {
            val coefChanged = draftCoef != null && boundCoef != null && abs(draftCoef - boundCoef) > 1e-9
            val lossChanged = draftLoss != null && abs(draftLoss - (boundLoss ?: 0.0)) > 1e-9
            return coefChanged || lossChanged
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
    /** 此刻打印机挡在哪（缺纸、未连接…），空串是能打。补打按钮看它，不看 [printError]。 */
    val printBlock: String = "",
    /** 标签还在打：汇总页显示“正在打印 N / M”与进度条；null 是没有在打。 */
    val printJob: PrintJobState? = null,
    val submitting: Boolean = false,
    val submitError: Boolean = false,
    /** `submit.without-price = allow` 时缺价也可提交，金额显示「待定价」。 */
    val allowWithoutPrice: Boolean = false,
) {
    /**
     * 主按钮此刻能不能直接提交：没有计价结果不能；缺规则或缺基准价时只有
     * `submit.without-price = allow` 才行；计价、刷新、提交中也不能。
     */
    val canSubmit: Boolean
        get() {
            val q = quote ?: return false
            if (submitting || loading || refreshing) return false
            // 提交要带规则：没选规则一律不能提交；缺基准价时看 submit.without-price
            if (q.noRule) return false
            return allowWithoutPrice || !q.noBasePrice
        }
}

data class DoneUiState(
    val farmerName: String = "",
    val productName: String = "",
    val specName: String = "",
    val batchNo: String = "",
    val boxCount: Int = 0,
    val totalNetJin: Double? = null,
    val avgJin: Double? = null,
    val unitPrice: Double? = null,
    val amount: Double? = null,
    val pendingPrice: Boolean = false,
    val unprinted: Int = 0,
    val printEnabled: Boolean = true,
    val printError: String = "",
    val printBlock: String = "",
    val printJob: PrintJobState? = null,
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
    val displayNet: Jin? = null,
    val k: Int = 1,
    val candidates: Pair<Int, Int>? = null,
    val tare: Tare? = null,
    /** 皮重热键，上一单用的排首位（不自动选中）。 */
    val tares: List<TareDto> = emptyList(),
    /** 上一单用的皮重热键 id，用来标“上一单用的”；没有或已不在热键里为 null。 */
    val lastTareId: Long? = null,
    val showTarePicker: Boolean = false,
    /** 称重皮（称空箱）按钮的当下状态。 */
    val emptyBox: EmptyBoxState = EmptyBoxState(connected = false, platform = null, stable = false),
    val boxes: List<Box> = emptyList(),
    val reweighSeq: Int? = null,
    val eventText: String = "",
    val eventOk: Boolean = true,
    val recordByButton: Boolean = false,
    val canPressRecord: Boolean = false,
    val scaleConnected: Boolean = false,
    val scaleLost: Boolean = false,
    /** 皮重没能下发到秤的原因；空串是没有。有值时称重框是未设皮重，按钮直达重新设皮重。 */
    val tareError: String = "",
    /** 进入称重失败的原因（取不到最新设置、取不到 W0…）；有值时不能称重，界面给“重试”。 */
    val error: String = "",
    val loading: Boolean = false,
    val printEnabled: Boolean = true,
    val printJob: PrintJobState? = null,
    val unprinted: Int = 0,
    val printError: String = "",
    val printBlock: String = "",
    val labelPreview: String = "",
    /** `print.timing = per-box`（默认）：每记一箱就打；完成后一次打印时，确认前的箱还不到打的时候，不算漏打。 */
    val printPerBox: Boolean = true,
    /** 已确认计量或已提交：流水只读，只能补打（APP-08）。 */
    val locked: Boolean = false,
    /** 选项 `weigh.manual-entry`（on 才允许手工录入净重）。 */
    val manualEntry: Boolean = true,
) {
    /** 单箱弹层的“手工录入”入口：选项允许才出现（单箱弹层本身只在未锁定时能打开）。 */
    val canManualEdit: Boolean get() = manualEntry && !locked

    val count: Int get() = boxes.size

    /** 秤没连上或断开：称重框位置换成连接卡片（原型 screenWeigh）。 */
    val showScaleCard: Boolean get() = !scaleConnected

    /** “手工加一箱”入口（连接卡片与流水弹层共用）：秤不可用时的最后兜底，0 箱也能用（APP-11）。 */
    val showManualAdd: Boolean get() = manualEntry && !locked
    val totalNet: Jin get() = boxes.map { it.net }.sum()
    fun avg(rule: RoundRule): Jin? =
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

/** [ReceivingViewModel.leaveWeigh] 的结果：已经离开可以返回，或要先问一句。 */
enum class LeaveWeigh { Left, NeedConfirm }

/** 称重框要显示的、来自秤这一侧（[Session]、状态机、皮重下发）的那部分界面状态。 */
internal fun WeighUiState.withScaleSide(ctl: WeighController, scaleConnected: Boolean): WeighUiState {
    val s = ctl.session
    val m = ctl.machine
    return copy(
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
        recordByButton = ctl.options.recordBy == RecordBy.BUTTON,
        canPressRecord = m?.canPress == true,
        scaleConnected = scaleConnected,
        scaleLost = ctl.scaleLost,
        tareError = ctl.tareError,
    )
}

/** `weigh.manual-entry`：off 时流水弹层与单箱弹层不出现“手工加一箱”“手工录入”（开发规格 4.3）。 */
fun manualEntryOn(options: AppOptions): Boolean =
    !options.get("weigh.manual-entry").equals("off", ignoreCase = true)

/**
 * Activity 级：持有 [Session] + [WeighingMachine]（开发规格 9.4）。
 * P6-4 称重；P6-5 再接流水弹层与草稿落盘。
 */
class ReceivingViewModel(
    private val container: ReceivingDeps,
    /** 当前时间（毫秒）；测试里换成虚拟时钟。 */
    private val clock: () -> Long = System::currentTimeMillis,
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

    private var eventClearJob: Job? = null
    private var startJob: Job? = null
    private var confirmed = false

    /** 已提交：单子已交给后端，内存里只留箱子供完成页补打；流水只读。 */
    private var submitted = false

    private val locked: Boolean get() = confirmed || submitted

    private val host = object : WeighHost {
        override suspend fun loadBootstrap(): BootstrapSnapshot {
            val b = container.fetchBootstrap()
            return BootstrapSnapshot(container.options.value, b.stdNetBySpec())
        }

        override suspend fun nextBatchNo(): String = container.catalog.nextBatchNo()

        override suspend fun tares(): List<TareDto> = container.catalog.tareHotkeys()

        override var lastTareId: Long?
            get() = container.lastTareId
            set(value) { container.lastTareId = value }
    }

    /** 秤这一侧：Session、状态机、秤连接与皮重下发（F1-3）。 */
    private val ctl = WeighController(
        scope = viewModelScope,
        scale = container.scale,
        host = host,
        isLocked = { locked },
        onChange = { publish() },
        onOutcome = { applyOutcome(it) },
        now = clock,
    )

    private var session: Session?
        get() = ctl.session
        set(value) { ctl.session = value }

    private val machine: WeighingMachine? get() = ctl.machine

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

    init {
        // 打印队列与打印机状态变了就刷新界面：补打按钮看的是打印机此刻的状态
        viewModelScope.launch { printQueue.job.collect { publishIfActive() } }
        viewModelScope.launch { printQueue.preview.collect { publishIfActive() } }
        viewModelScope.launch { printQueue.lastError.collect { publishIfActive() } }
        viewModelScope.launch { container.printer.linkStatus.collect { publishIfActive() } }
        viewModelScope.launch { container.printer.printerStatus.collect { publishIfActive() } }
    }

    val roundRule: RoundRule
        get() = runCatching {
            RoundRule.fromWire(container.options.value.get("round.mode"))
        }.getOrDefault(RoundRule.DEFAULT)

    /**
     * 有本地草稿时恢复；否则从建单 OrderDraft 开新单。两种都先取一次 bootstrap，成功后才建状态机；
     * 取不到时不进入可称重状态，界面给原因和“重试”（再调一次本方法）。
     */
    fun startFromDraft() {
        // 上一单已提交、只为完成页补打留着：开新单前先清掉
        if (submitted) resetAll()
        if (session != null) {
            publish()
            return
        }
        if (startJob?.isActive == true) return
        val saved = container.sessionStore.load()
        val h = container.orderDraft.header.value
        if (saved == null && !h.ready) return
        startJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    loading = true,
                    error = "",
                    farmerName = saved?.farmer?.name ?: h.farmer?.name.orEmpty(),
                    productName = saved?.product?.name ?: h.product?.name.orEmpty(),
                    specName = saved?.spec?.name ?: h.spec?.name.orEmpty(),
                    batchNo = saved?.batchNo.orEmpty(),
                    warehouseName = saved?.warehouse?.name ?: h.warehouse?.name.orEmpty(),
                )
            }
            if (!ctl.start(saved, h)) {
                _ui.update { it.copy(loading = false, ready = false, error = ctl.startError) }
                return@launch
            }
            val s = session ?: return@launch
            if (saved != null) {
                // 把表头写回 OrderDraft，便于返回建单页仍可见
                container.orderDraft.update {
                    it.copy(
                        farmer = s.farmer,
                        product = s.product,
                        spec = s.spec,
                        warehouse = s.warehouse,
                        stdNetCents = s.stdNet?.cents,
                    )
                }
            }
            // 上一单用的排首位但不自动选中：换了包装箱而没注意，整批皮重都错（v7 APP-04）
            val tareStart = TareStart.of(ctl.tares, container.lastTareId)
            _ui.update {
                it.copy(
                    loading = false,
                    error = "",
                    ready = true,
                    tares = tareStart.tares,
                    lastTareId = tareStart.lastTareId,
                    showTarePicker = s.tare == null,
                )
            }
            persist()
            publish()
        }
    }

    fun hasBoxes(): Boolean = (session?.boxes?.isNotEmpty() == true)

    fun editManual(seq: Int, netJin: Double) {
        if (locked || !manualEntryOn(container.options.value)) return
        val s = session ?: return
        session = s.editManual(seq, Jin.of(netJin))
        flash("第 $seq 箱已手录", ok = true)
        persist()
        publish()
        printQueue.enqueueReprint(seq)
    }

    fun addManual(netJin: Double) {
        if (locked || !manualEntryOn(container.options.value)) return
        val before = session?.boxes?.map { it.seq }.orEmpty().toSet()
        val s = session ?: return
        session = s.addManual(Jin.of(netJin))
        flash("已手工加一箱", ok = true)
        persist()
        publish()
        val added = session?.boxes?.map { it.seq }.orEmpty().filter { it !in before }
        printQueue.enqueueNew(added)
    }

    fun deleteBox(seq: Int) {
        if (locked) return
        val s = session ?: return
        session = s.delete(seq)
        flash("已删除", ok = true)
        persist()
        publish()
        // 后面的箱序号前移，已贴出去的旧标签作废；序号变了的箱已清打印标记，逐箱模式下重打
        printQueue.enqueueRenumbered()
    }

    fun regroup(seq: Int, k: Int) {
        if (locked) return
        val s = session ?: return
        if (!s.canRegroup(seq)) return
        session = s.regroup(seq, k)
        flash("已改成 $k 箱", ok = true)
        persist()
        publish()
        printQueue.enqueueRenumbered()
    }

    fun reprintUnprinted() = printQueue.reprintUnprinted()

    fun reprintOne(seq: Int) = printQueue.reprintOne(seq)

    /**
     * 适配走查：灌入 [count] 箱（默认 100），不入打印队列，避免 Fake 打满。
     * 对照原型「最坏 + 100 箱」。
     */
    fun seedAdaptBoxes(count: Int = AdaptFixtures.STRESS_BOX_COUNT) {
        var s = session ?: return
        if (count <= s.boxes.size) return
        val need = count - s.boxes.size
        repeat(need) { i ->
            s = s.addManual(Jin.of(AdaptFixtures.netJinForIndex(s.boxes.size + i)))
        }
        session = s
        persist()
        publish()
        flash("已灌入 ${session?.boxes?.size ?: count} 箱（适配）", ok = true)
    }

    /** 适配走查：把当前农户名换成最坏长名，不截断展示。 */
    fun applyWorstFarmerName() {
        val s = session ?: return
        session = s.copy(farmer = Pick(s.farmer.id, AdaptFixtures.WORST_FARMER_NAME))
        persist()
        publish()
        _summary.update { it.copy(farmerName = AdaptFixtures.WORST_FARMER_NAME) }
        flash("已套用最坏户名", ok = true)
    }

    /** 确认计量完毕：完成后一次打印入队，拉计价，进汇总页（APP-08）。 */
    fun onConfirmWeighing() {
        if (session?.boxes.isNullOrEmpty()) return
        confirmed = true
        // 锁定流水：此后秤上的动静不记箱；解锁后须重新见到空秤才记
        machine?.resetScale()
        printQueue.enqueueAfterConfirm()
        viewModelScope.launch {
            refreshQuote(resetM0Retried = true)
            _nav.emit(ReceivingNav.ToSummary)
        }
    }

    /** 返回计量：解锁流水继续称（原型 `back`）。 */
    fun backToWeigh() {
        confirmed = false
        machine?.resetScale()
        viewModelScope.launch { _nav.emit(ReceivingNav.BackToWeigh) }
    }

    /** 提交单据（APP-10）。失败保留流水；登录失效由 MainActivity 回登录，草稿不丢。 */
    fun submitOrder() {
        val s = session ?: return
        if (submitted || _summary.value.submitting) return
        val q = _summary.value.quote
        if (!_summary.value.canSubmit) return
        val ruleId = s.rule?.id ?: return
        viewModelScope.launch {
            _summary.update { it.copy(submitting = true, submitError = false, error = "") }
            try {
                val groups = s.boxes.groupingBy { it.group }.eachCount()
                val submitted = container.orders.submit(
                    SubmitRequest(
                        batchNo = s.batchNo,
                        vendorId = s.farmer.id,
                        ruleId = ruleId,
                        warehouseId = s.warehouse.id,
                        boxes = s.boxes.map { b ->
                            SubmitBoxRequest(
                                seq = b.seq,
                                netJin = b.net.toDouble(),
                                grossJin = b.gross?.toDouble(),
                                tareJin = b.tare.toDouble(),
                                tareName = b.tareName.ifBlank { null },
                                groupNo = b.group,
                                groupK = groups[b.group],
                                manual = b.manual,
                                rawJin = b.raw?.toDouble(),
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
        if (clock() < readyAt) return
        if (printQueue.job.value != null) return // 标签还在打，打完再收一单
        container.sessionStore.clear()
        resetAll()
        viewModelScope.launch { _nav.emit(ReceivingNav.ToCreate) }
    }

    /** 清空内存里的整张单（含提交后留着补打的箱）与打印队列，回到没有单的状态。 */
    private fun resetAll(keepOrderDraft: Boolean = false) {
        printQueue.stop()
        startJob?.cancel()
        ctl.reset()
        confirmed = false
        submitted = false
        if (!keepOrderDraft) container.orderDraft.clear()
        _ui.value = WeighUiState()
        _summary.value = SummaryUiState()
        _ruleSheet.value = RuleSheetState()
        _done.value = DoneUiState()
    }

    private fun finishSubmit(s: Session, submitted: SubmittedDto, quote: QuoteResult?) {
        val pending = submitted.submittedAmount == null && submitted.submittedPrice == null
        _done.value = DoneUiState(
            farmerName = s.farmer.name,
            productName = s.product.name,
            specName = s.spec.name,
            batchNo = submitted.batchNo ?: s.batchNo,
            boxCount = submitted.boxCount.takeIf { it > 0 } ?: s.boxes.size,
            totalNetJin = submitted.totalNetJin ?: quote?.totalJin,
            avgJin = submitted.avgJin ?: quote?.avgJin,
            unitPrice = submitted.submittedPrice ?: quote?.unitPrice,
            amount = submitted.submittedAmount ?: quote?.amount,
            pendingPrice = pending,
            readyAgainAt = clock() + 500,
        )
        // 提交成功清草稿与建单四项。内存里的箱留着、打印队列不停：没打完的接着打，
        // 完成页还能补打（APP-07；附录 C 第 17 条：提交后的补打不回写后端）。流水从此只读。
        container.sessionStore.clear()
        container.orderDraft.clear()
        ctl.detach()
        confirmed = false
        this.submitted = true
        _summary.update { it.copy(submitting = false, submitError = false) }
        publish()
        viewModelScope.launch { _nav.emit(ReceivingNav.ToDone) }
    }

    /** 重新拉当日基准价再计价（原型 `refreshM0`）。 */
    fun refreshBasePrice() {
        viewModelScope.launch {
            _summary.update { it.copy(refreshing = true, error = "") }
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
        val next = max(0.0, ((cur + delta) * 100).roundToInt() / 100.0)
        _ruleSheet.update { it.copy(draftCoef = next, error = "") }
        viewModelScope.launch { previewTrial() }
    }

    /** 每箱损耗 ±0.5 斤一步，可以减到负数（正数加、负数减）。 */
    fun adjustDraftLoss(delta: Double) {
        if (_ruleSheet.value.unbound) return
        val next = ((_ruleSheet.value.displayLoss + delta) * 100).roundToInt() / 100.0
        _ruleSheet.update { it.copy(draftLoss = next, error = "") }
        viewModelScope.launch { previewTrial() }
    }

    fun revertDraftCoef() {
        _ruleSheet.update {
            it.copy(draftCoef = null, draftLoss = null, trialUnitPrice = it.boundUnitPrice, error = "")
        }
    }

    /** 改动保存到规则：用这条规则的单都按新值算。 */
    fun saveDraftCoef() {
        val state = _ruleSheet.value
        val ruleId = state.boundRuleId ?: return
        if (!state.dirty) return
        viewModelScope.launch {
            _ruleSheet.update { it.copy(busy = true, error = "") }
            try {
                container.catalog.updateRule(ruleId, state.draftCoef, state.draftLoss)
                refreshQuote(resetM0Retried = false)
                refreshRuleSheet(keepChanging = false)
            } catch (e: Throwable) {
                _ruleSheet.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    /** 这张单换成另一条规则（同商品规格）：只改草稿里的规则，提交时带上。 */
    fun chooseRule(ruleId: Long) {
        val s = session ?: return
        val r = _ruleSheet.value.candidates.firstOrNull { it.rule.id == ruleId }?.rule ?: return
        session = s.copy(rule = Pick(r.id, r.name))
        persist()
        viewModelScope.launch {
            _ruleSheet.update { it.copy(busy = true, error = "") }
            refreshQuote(resetM0Retried = false)
            refreshRuleSheet(keepChanging = false)
        }
    }

    private suspend fun previewTrial() {
        val s = session ?: return
        val st = _ruleSheet.value
        val req = quoteRequestOf(s, trialCoef = st.draftCoef, trialLossJin = st.draftLoss) ?: return
        try {
            val q = container.orders.quote(req)
            _ruleSheet.update { it.copy(trialUnitPrice = q.unitPrice) }
        } catch (_: Throwable) {
            // 预览失败不挡改规则；保存时再报错
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
                draftLoss = null,
                productName = s.product.name,
                specName = s.spec.name,
            )
        }
        try {
            if (_summary.value.quote == null) {
                refreshQuote(resetM0Retried = false)
            }
            val quote = _summary.value.quote
            // 只列同商品规格的规则：换了规则也不动已称的箱与标准净含量
            val rules = container.catalog.buyRules(s.product.id, s.spec.id)
            val boundId = s.rule?.id
            val bound = rules.firstOrNull { it.id == boundId }
            val others = rules.filter { it.id != boundId }
            val priced = others.map { r ->
                val unit = runCatching {
                    quoteRequestOf(s, ruleId = r.id)?.let { container.orders.quote(it).unitPrice }
                }.getOrNull()
                RuleCandidate(r, unit)
            }
            _ruleSheet.update {
                it.copy(
                    loading = false,
                    busy = false,
                    standardNetJin = quote?.standardNetJin,
                    basePrice = quote?.basePrice,
                    boundRuleId = boundId,
                    boundRuleName = bound?.name ?: quote?.ruleName ?: s.rule?.name,
                    boundCoef = bound?.coef ?: quote?.coefficient,
                    boundLoss = bound?.lossJin ?: quote?.lossJin,
                    draftCoef = null,
                    draftLoss = null,
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
                printBlock = printBlock(),
                printJob = printQueue.job.value,
            )
        }
        try {
            // 改版前的草稿没有规则：不调后端，直接按“没选规则”显示，主按钮去选规则
            val req = quoteRequestOf(s)
            val quote = if (req == null) {
                QuoteResult(boxCount = s.boxes.size, issues = listOf("NO_RULE"))
            } else {
                container.orders.quote(req)
            }
            _summary.update {
                it.copy(
                    loading = false,
                    quote = quote,
                    unprinted = printQueue.unprintedCount(),
                    printEnabled = printQueue.enabled(),
                    printError = printQueue.lastError.value,
                    printBlock = printBlock(),
                    printJob = printQueue.job.value,
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

    /**
     * 离开称重页（返回键、左上角返回）。对照原型 `leave`：
     * 0 箱等同没有单：清掉内存里的单和本地草稿（不弹确认），建单页四项原样保留、仍可改，
     * 再次“开始收蛋”按建单页当时的四项新开一单（批次号可能跳一个）；
     * 有箱时不动任何东西，返回 [LeaveWeigh.NeedConfirm]，由界面问“放弃这一单 / 继续称”。
     */
    fun leaveWeigh(): LeaveWeigh {
        if (hasBoxes()) return LeaveWeigh.NeedConfirm
        container.sessionStore.clear()
        resetAll(keepOrderDraft = true)
        return LeaveWeigh.Left
    }

    /** 放弃整单：清草稿与内存，回到建单。 */
    fun abandon() {
        printQueue.stop()
        container.sessionStore.clear()
        clear()
    }

    fun openTarePicker() {
        _ui.update { it.copy(showTarePicker = true) }
    }

    fun selectTare(dto: TareDto) {
        viewModelScope.launch {
            ctl.chooseTare(Tare(Jin.of(dto.tareJin), dto.name), dto.id)
            _ui.update { it.copy(showTarePicker = false) }
            persist()
            publish()
        }
    }

    /** 皮重没下发成功时，“重新设皮重”直达：用已选的皮重重发，不用重新挑。 */
    fun retryTare() {
        viewModelScope.launch {
            ctl.retryTare()
            publish()
        }
    }

    /**
     * 称重皮（v7 `APP-04`）：秤上是一只空箱且读数稳定时，按台面读数去皮，皮重名记“空箱”。
     * 走和选热键皮重同一条路（[WeighController.chooseTare]）：`scale.tare-by = scale` 时下发到秤，
     * 下发失败不记；条件不满足时什么也不做。
     */
    fun weighEmptyBox() {
        if (locked) return
        val st = emptyBoxState()
        val platform = st.platform
        if (!st.available || platform == null) return
        viewModelScope.launch {
            ctl.chooseTare(EmptyBoxTare.tareOf(platform), null)
            // 空箱没有热键 id；上一单标记不留在别的箱上
            container.lastTareId = null
            _ui.update { it.copy(showTarePicker = false) }
            persist()
            publish()
        }
    }

    private fun emptyBoxState() = EmptyBoxState(
        connected = container.scale.linkStatus.value == LinkStatus.CONNECTED,
        platform = machine?.platformNow,
        stable = machine?.isStable == true,
    )

    fun pressRecord() {
        if (locked) return
        applyOutcome(machine?.press())
        publish()
    }

    fun chooseK(k: Int) {
        if (locked) return
        applyOutcome(machine?.choose(k))
        publish()
    }

    fun startReweigh(seq: Int) {
        if (locked) return
        machine?.startReweigh(seq)
        flash("重称第 $seq 箱", ok = true)
        publish()
    }

    fun cancelReweigh() {
        machine?.cancelReweigh()
        publish()
    }

    fun clear() = resetAll()

    private fun applyOutcome(outcome: WeighOutcome?) {
        if (outcome == null || locked) return
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
        _ui.update {
            it.withScaleSide(ctl, container.scale.linkStatus.value == LinkStatus.CONNECTED).copy(
                emptyBox = emptyBoxState(),
                printEnabled = printQueue.enabled(),
                printJob = printQueue.job.value,
                unprinted = printQueue.unprintedCount(),
                printError = printQueue.lastError.value,
                printBlock = printBlock(),
                labelPreview = printQueue.preview.value?.text.orEmpty(),
                printPerBox = printQueue.timingPerBox(),
                locked = locked,
                manualEntry = manualEntryOn(container.options.value),
            )
        }
        if (confirmed) {
            _summary.update {
                it.copy(
                    unprinted = printQueue.unprintedCount(),
                    printEnabled = printQueue.enabled(),
                    printError = printQueue.lastError.value,
                    printBlock = printBlock(),
                    printJob = printQueue.job.value,
                )
            }
        }
        if (submitted) {
            _done.update {
                it.copy(
                    unprinted = printQueue.unprintedCount(),
                    printEnabled = printQueue.enabled(),
                    printError = printQueue.lastError.value,
                    printBlock = printBlock(),
                    printJob = printQueue.job.value,
                )
            }
        }
    }

    private fun publishIfActive() {
        if (session != null) publish()
    }

    /** 打印机此刻挡在哪；不用打印或没有未打印的箱就不挡。 */
    private fun printBlock(): String =
        if (printQueue.enabled()) printQueue.blocker().orEmpty() else ""

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReceivingViewModel(container) as T
    }
}
