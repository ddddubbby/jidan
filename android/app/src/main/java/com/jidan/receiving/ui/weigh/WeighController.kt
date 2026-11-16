package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.ScaleReading
import com.jidan.receiving.core.Session
import com.jidan.receiving.core.StableBy
import com.jidan.receiving.core.Tare
import com.jidan.receiving.core.TareBy
import com.jidan.receiving.core.WeighOptions
import com.jidan.receiving.core.WeighOutcome
import com.jidan.receiving.core.WeighingMachine
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.data.OrderHeader
import com.jidan.receiving.data.TareDto
import com.jidan.receiving.data.userMessage
import com.jidan.receiving.device.DeviceScaleReading
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.Scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次 bootstrap 取回的东西：已生效的选项，和各规格的标准净含量 W0（kg）。 */
data class BootstrapSnapshot(
    val options: AppOptions,
    val stdNetKgBySpec: Map<Long, Double> = emptyMap(),
)

/** 称重控制器要用的外部能力；`:app` 用 [com.jidan.receiving.AppContainer] 实现，单测用假的。 */
interface WeighHost {
    /** 取 `GET /app/bootstrap` 并让新选项生效；失败抛出。 */
    suspend fun loadBootstrap(): BootstrapSnapshot

    suspend fun nextBatchNo(): String

    suspend fun tares(): List<TareDto>

    var lastTareId: Long?
}

/**
 * 称重这一单的“秤这一侧”（开发规格 9.2、9.4）：[Session]、[WeighingMachine]、秤的连接与皮重。
 * 不依赖 Android，ViewModel 只管界面、草稿落盘与打印。
 *
 * - 开始（新建单或恢复草稿）先取一次 bootstrap，成功后才建状态机：选项下一次建单生效（4.4）。
 *   取不到、或取不到 W0，都不进入可称重状态，由 [startError] 说明原因，界面给“重试”。不做本地缓存，也没有默认 W0。
 * - `scale.tare-by = scale` 时，每次秤转为已连接且有皮重都重新下发；下发失败则状态机保持未设皮重，
 *   [tareError] 写失败原因，界面按钮直达 [retryTare]。
 * - `scale.tare-by = scale` 且这一单还没有皮重时，秤里可能还留着上一单下发的数字皮：开始称重与每次转为已连接
 *   都先 `clearTare()`，清成功前读数不喂给状态机（否则“称空箱”按的是已被旧皮重扣过的读数）。
 *   清失败同样写 [tareError]，[retryTare] 重试清皮。`scale.tare-by = app` 时不调（9.3 契约）。
 */
class WeighController(
    private val scope: CoroutineScope,
    private val scale: Scale,
    private val host: WeighHost,
    private val isLocked: () -> Boolean,
    private val onChange: () -> Unit,
    private val onOutcome: (WeighOutcome) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 100,
) {
    var session: Session? = null

    var machine: WeighingMachine? = null
        private set

    /** 这一单用的称重选项：开始时取到的，不随之后的 bootstrap 变。 */
    var options: WeighOptions = WeighOptions()
        private set

    /** 进入称重失败的原因；空串是没有失败。 */
    var startError: String = ""
        private set

    /** 皮重没能下发到秤的原因；空串是没有失败。 */
    var tareError: String = ""
        private set

    var tares: List<TareDto> = emptyList()
        private set

    var scaleLost: Boolean = false
        private set

    private var wantedTare: Tare? = null

    /** 当前这条连接上，秤已经收到 [wantedTare]。 */
    private var tareSynced = false

    /** 当前这条连接上，秤里上一单留下的皮重已清掉（只在 [needsClear] 时用）。 */
    private var clearSynced = false

    private val tareLock = Mutex()
    private var jobs: List<Job> = emptyList()

    private class StartFailure(message: String) : Exception(message)

    /**
     * 取 bootstrap、定 W0、建状态机、开始听秤。[restore] 非空是恢复草稿，否则按 [header] 开新单。
     * 成功返回 true；失败返回 false，原因在 [startError]，此时没有状态机，也不听秤。
     */
    suspend fun start(restore: Session?, header: OrderHeader): Boolean {
        startError = ""
        try {
            val boot = host.loadBootstrap()
            val opts = boot.options.weighOptions()
            val list = runCatching { host.tares() }.getOrDefault(emptyList())
            val s = if (restore != null) {
                restore.copy(stdNet = restore.stdNet ?: boot.stdNetOf(restore.spec))
            } else {
                val farmer = header.farmer
                val product = header.product
                val spec = header.spec
                val warehouse = header.warehouse
                if (farmer == null || product == null || spec == null || warehouse == null) {
                    throw StartFailure("建单信息不全")
                }
                val w0 = header.stdNetCents?.let(::Kg) ?: boot.stdNetOf(spec)
                Session(
                    batchNo = host.nextBatchNo(),
                    farmer = farmer,
                    product = product,
                    spec = spec,
                    warehouse = warehouse,
                    stdNet = w0,
                )
                // 新开一单不自动套用上一单皮重：换了包装箱而没注意，整批皮重都错（v7 APP-04）；磅秤员点了才设
            }
            unbind()
            session = s
            options = opts
            tares = list
            tareError = ""
            tareSynced = false
            clearSynced = false
            scaleLost = false
            wantedTare = s.tare
            val m = WeighingMachine(opts, s.stdNet!!)
            machine = m
            // 秤已连上且要下发皮重时，等 bind 里看到“已连接”再下发；否则先放着，连上时会再发
            val viaScale = opts.tareBy == TareBy.SCALE
            if (!viaScale || scale.linkStatus.value != LinkStatus.CONNECTED) {
                s.tare?.let { m.setTare(it) }
            }
            bind()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            startError = if (e is StartFailure) e.message.orEmpty() else e.userMessage()
            return false
        }
    }

    /** 换皮重（或第一次选）。记下想要的皮重，`scale.tare-by = scale` 且秤已连接时立刻下发。 */
    suspend fun chooseTare(t: Tare, id: Long?) {
        wantedTare = t
        session = session?.withTare(t)
        if (id != null) host.lastTareId = id
        pushTare()
    }

    /** 皮重下发失败后重发（界面“重新设皮重”按钮）。 */
    suspend fun retryTare() {
        pushTare()
    }

    /** 停止听秤，留下 [session]（提交后完成页还要补打）。 */
    fun detach() {
        unbind()
        machine = null
        wantedTare = null
        tareSynced = false
        clearSynced = false
    }

    /** 回到没有单的状态。 */
    fun reset() {
        detach()
        session = null
        startError = ""
        tareError = ""
        tares = emptyList()
        scaleLost = false
    }

    private fun BootstrapSnapshot.stdNetOf(spec: Pick): Kg {
        val kg = stdNetKgBySpec[spec.id]
            ?: throw StartFailure("取不到规格「${spec.name}」的标准净含量，没法识别箱数")
        return Kg.of(kg)
    }

    /** 这一单还没有皮重、皮重由秤负责：秤里可能留着上一单的数字皮，要先清掉。 */
    private fun needsClear(): Boolean = options.tareBy == TareBy.SCALE && wantedTare == null

    /** 把 [wantedTare] 交给状态机，`scale.tare-by = scale` 且秤已连接时先下发到秤；还没有皮重时改为清掉秤里的旧皮重。 */
    private suspend fun pushTare() {
        tareLock.withLock {
            val m = machine ?: return
            val t = wantedTare
            if (t == null) {
                clearStaleTare()
                return
            }
            if (options.tareBy != TareBy.SCALE || scale.linkStatus.value != LinkStatus.CONNECTED) {
                // 不用下发，或秤没连着：先放着，秤连上时 bind 里会再下发一次
                m.setTare(t)
                tareSynced = false
                tareError = ""
                onChange()
                return
            }
            // 下发期间、下发失败后都是未设皮重：不记箱
            tareSynced = false
            m.setTare(null)
            onChange()
            try {
                scale.setDigitalTare(t.unit)
                m.setTare(t)
                tareSynced = true
                tareError = ""
            } catch (e: TimeoutCancellationException) {
                tareError = "秤没有响应"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                tareError = e.message?.takeIf { it.isNotBlank() } ?: "秤没有接受皮重"
            }
            onChange()
        }
    }

    /**
     * 清掉秤里上一单留下的皮重（同一台秤连着收第二单，秤里仍是上一单下发的数字皮）。
     * 成功前读数不喂状态机；成功后把秤此刻的读数补喂一次，因为清皮若没让读数变化，秤不会再回调。
     * 在 [tareLock] 里调用。
     */
    private suspend fun clearStaleTare() {
        if (!needsClear() || scale.linkStatus.value != LinkStatus.CONNECTED) return
        clearSynced = false
        try {
            scale.clearTare()
            clearSynced = true
            tareError = ""
            scale.readings.value?.let(::feed)
        } catch (e: TimeoutCancellationException) {
            tareError = "秤没有响应"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            tareError = e.message?.takeIf { it.isNotBlank() } ?: "秤没有清掉上一单的皮重"
        }
        onChange()
    }

    /** 读数交给状态机；锁定（已确认计量）、没有状态机、皮重还没确认到秤上时丢弃。 */
    private fun feed(r: DeviceScaleReading) {
        if (isLocked()) return
        val m = machine ?: return
        // 皮重还没确认下到这条连接上时，读数不是已扣皮的，不能喂给状态机
        if (options.tareBy == TareBy.SCALE && m.tare != null && !tareSynced) return
        // 这一单还没有皮重：秤里上一单的皮重没清掉前，读数是被旧皮重扣过的
        if (needsClear() && !clearSynced) return
        val outcome = m.onReading(ScaleReading(r.raw, r.stable), now())
        if (outcome != null) onOutcome(outcome)
        onChange()
    }

    private fun bind() {
        val link = scope.launch {
            scale.linkStatus.collect { st ->
                val connected = st == LinkStatus.CONNECTED
                scaleLost = st == LinkStatus.LOST ||
                    (st == LinkStatus.CONNECTING && (session?.boxes?.isNotEmpty() == true))
                if (!connected) {
                    tareSynced = false
                    clearSynced = false
                    machine?.resetScale()
                }
                onChange()
                // 秤重启或换了实例就没有皮重：每次转为已连接都重发；还没有皮重时改为清掉秤里的旧皮重
                if (connected) pushTare()
            }
        }
        val readings = scope.launch {
            scale.readings.collect { r ->
                if (r != null) feed(r)
            }
        }
        val tick = scope.launch {
            while (isActive) {
                delay(tickMs)
                if (isLocked()) continue
                val outcome = machine?.onTick(now())
                if (outcome != null) {
                    onOutcome(outcome)
                    onChange()
                } else if (options.stableBy == StableBy.APP) {
                    onChange()
                }
            }
        }
        jobs = listOf(link, readings, tick)
    }

    private fun unbind() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }
}
