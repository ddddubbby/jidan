package com.jidan.receiving.core

/** 秤回调的读数：重量原值（不做扣皮换算）与秤给的稳定标志（`scale.stable-by = app` 时不看它）。 */
data class ScaleReading(val raw: Jin, val stable: Boolean)

/** 每箱皮重。未设皮重时状态机的 `tare` 为 null。 */
data class Tare(val unit: Jin, val name: String)

/** 称重框的状态（v7 `APP-03`）：灰色＝空秤或未设皮重，跳动＝读数未稳定，绿色＝已记录，琥珀色＝待拿下。 */
enum class WeighPhase {
    /** 未设皮重，不记。 */
    LOCKED,
    /** 读数未稳定（含还没收到读数）。 */
    UNSTABLE,
    /** 空秤。 */
    EMPTY,
    /** 刚记完。 */
    RECORDED,
    /** 记完后箱子还在秤上，等拿下。 */
    HOLD,
    /** 识别不准，等磅秤员点选箱数。 */
    ASK,
    /** 稳定、可以记；`weigh.record-by = button` 时等按“记录”。 */
    READY,
}

/** 一次称重的结果。一次放 k 箱时，净重与皮重都是 k 箱合计，由 [toBoxes] 均摊成 k 条流水。 */
data class Weighing(
    val k: Int,
    /** 秤台总重，含 k 箱皮重。 */
    val platform: Jin,
    val tareUnit: Jin,
    val tareTotal: Jin,
    val net: Jin,
) {
    /** 新增一次称重的 k 条流水，序号由 [SplitBoxes.append] 编。[group] 由调用方给（手工加的箱也共用同一个计数）。 */
    fun toBoxes(group: Int, tareName: String): List<Box> =
        SplitBoxes.split(net, k, tareTotal, tareName, group, gross = platform, unit = tareUnit)

    /** 重称：新读数替换 [old] 这一箱，序号与所在组不变，手录记号与打印标记清掉（原型 `record`）。 */
    fun replacing(old: Box, tareName: String): Box =
        old.copy(
            net = net, tare = tareUnit, tareName = tareName, gross = platform, unit = tareUnit,
            manual = false, raw = null, printed = false,
        )
}

/** 状态机记下一次称重时的结果。 */
sealed interface WeighOutcome {
    val weighing: Weighing

    /** 新增一次称重。 */
    data class Recorded(override val weighing: Weighing) : WeighOutcome

    /** 重称：替换第 [seq] 箱。 */
    data class Replaced(val seq: Int, override val weighing: Weighing) : WeighOutcome
}

/**
 * 称重状态机（开发规格 9.2，原型 `canRecord`、`record`、`tick`）：
 * 未设皮重不记；读数稳定且净重达到 `weigh.empty-below-jin` 才记（`weigh.record-by = button` 时改为等按钮）；
 * 记完须等秤面稳定清空才记下一次；识别不准时暂停；重称替换指定箱。
 *
 * 纯逻辑：不碰设备、不持有流水。读数与时间由调用方喂进来，记下的结果由返回值交出，
 * 调用方（P3-4 的 `Session`）自己把它加进流水。
 *
 * 箱数识别由 [detect] 注入，默认是 [DetectK]；这里不再实现一份。
 *
 * @param standardNet 标准净含量 `W0`
 */
class WeighingMachine(
    private val options: WeighOptions,
    private val standardNet: Jin,
    private val detect: (gross: Jin, tareUnit: Jin, standardNet: Jin, tolerancePct: Int, maxK: Int) -> DetectResult =
        DetectK::detect,
) {
    var tare: Tare? = null
        private set

    /** 当前识别（或选定）的箱数，称重框显示“净重（k 箱）”。 */
    var k: Int = 1
        private set

    /** 识别不准时给的两个箱数（小在前）；非 null 时暂停自动记录。 */
    var candidates: Pair<Int, Int>? = null
        private set

    /** 正在重称的箱序号。 */
    var reweighSeq: Int? = null
        private set

    private val stability = Stability(options.stableWindowMs, options.stableTolerance)
    private var reading: ScaleReading? = null
    private var stable = false

    // 记完要等秤面稳定清空才再记；刚启动时先当作还没清空，免得秤上已有箱子时重复记
    private var armed = false
    private var recorded = false

    private val tareUnit get() = tare?.unit ?: Jin.ZERO
    private val platform get() = reading?.let { NetWeight.platform(it.raw, tareUnit, options) }
    private val loaded get() = platform?.let { it - tareUnit >= options.emptyBelow } ?: false

    private val canRecord
        get() = tare != null && stable && armed && loaded && candidates == null

    val phase: WeighPhase
        get() = when {
            tare == null -> WeighPhase.LOCKED
            !stable -> WeighPhase.UNSTABLE
            !loaded -> WeighPhase.EMPTY
            recorded -> WeighPhase.RECORDED
            !armed -> WeighPhase.HOLD
            candidates != null -> WeighPhase.ASK
            else -> WeighPhase.READY
        }

    /** 此刻台面总重（未扣皮，按当前皮重还原）；没有读数（刚换皮重、秤断开）时为 null。称空箱按它去皮。 */
    val platformNow: Jin? get() = platform

    /** 此刻读数是否稳定。 */
    val isStable: Boolean get() = stable

    /** 称重框显示的净重（k 箱）；没有读数时为 null。 */
    val displayNet: Jin?
        get() = platform?.let { it - Jin(tareUnit.cents * k) }

    /** `weigh.record-by = button` 时，“记录”按钮可按。 */
    val canPress get() = options.recordBy == RecordBy.BUTTON && canRecord

    /**
     * 设（或换）皮重。换皮重后之前的读数按旧皮重换算，作废，等下一个读数。
     * [armed] 不变：已经记过的箱子还在秤上时，换皮重不会让它再记一次。
     */
    fun setTare(t: Tare?) {
        tare = t
        reading = null
        stable = false
        stability.reset()
        k = 1
        candidates = null
    }

    /** 秤断开或重连：之前的读数作废；重连后秤上若已有箱子，要先拿下再放。 */
    fun resetScale() {
        reading = null
        stable = false
        armed = false
        recorded = false
        stability.reset()
        k = 1
        candidates = null
    }

    /** 收到一个读数。[nowMs] 是单调递增的毫秒数，只在 `scale.stable-by = app` 时用。记下了就返回结果。 */
    fun onReading(r: ScaleReading, nowMs: Long): WeighOutcome? {
        reading = r
        stable = if (options.stableBy == StableBy.SDK) r.stable else stability.update(r.raw, nowMs)
        return evaluate()
    }

    /**
     * 没有新读数时的定时检查。`scale.stable-by = app` 时读数不变秤就不回调，
     * 靠它在窗口走满时判定稳定。`scale.stable-by = sdk` 时什么也不变。
     */
    fun onTick(nowMs: Long): WeighOutcome? {
        if (reading == null || options.stableBy != StableBy.APP) return null
        stable = stability.check(nowMs)
        return evaluate()
    }

    /** `weigh.record-by = button` 时按“记录”。条件不满足时什么也不做。 */
    fun press(): WeighOutcome? = if (canPress) record() else null

    /** 识别不准时，点选 [chosen] 箱（必须是 [candidates] 之一）并记录。 */
    fun choose(chosen: Int): WeighOutcome? {
        val c = candidates ?: return null
        if (chosen != c.first && chosen != c.second) return null
        if (tare == null || !stable || !armed || !loaded) return null
        k = chosen
        candidates = null
        return record()
    }

    /** 重称第 [seq] 箱：下一次记录替换它，按 1 箱算。 */
    fun startReweigh(seq: Int) {
        reweighSeq = seq
        k = 1
        candidates = null
    }

    fun cancelReweigh() {
        reweighSeq = null
    }

    private fun evaluate(): WeighOutcome? {
        if (reading == null) return null
        // 台面稳定空了，才允许记下一次
        if (stable && !loaded) {
            armed = true
            recorded = false
            k = 1
            candidates = null
        }
        if (tare != null && stable && armed && loaded) detectK()
        return if (options.recordBy == RecordBy.AUTO && canRecord) record() else null
    }

    private fun detectK() {
        if (reweighSeq != null || options.mergeDetect == MergeDetect.OFF) {
            k = 1
            candidates = null
            return
        }
        val d = detect(platform!!, tareUnit, standardNet, options.mergeTolerancePct, options.mergeMaxK)
        k = d.k
        candidates = d.candidates
    }

    private fun record(): WeighOutcome {
        val c = NetWeight.convert(reading!!.raw, tareUnit, k, options)
        val w = Weighing(k, c.platform, tareUnit, c.tareTotal, c.net)
        armed = false
        recorded = true
        val seq = reweighSeq
        reweighSeq = null
        return if (seq != null) WeighOutcome.Replaced(seq, w) else WeighOutcome.Recorded(w)
    }
}
