package com.jidan.receiving.core

enum class TareBy { SCALE, APP }
enum class StableBy { SDK, APP }
enum class RecordBy { AUTO, BUTTON }
enum class MergeDetect { AUTO, OFF }

/**
 * `scale.*`、`weigh.*` 各选项的取值（开发规格 4.3、9.2）。默认值就是 v7 的做法。
 * `:core` 自己不读配置、不联网：`:app` 从 `bootstrap` 取到后用 [of] 解析，缺项或认不出的取值用默认值。
 */
data class WeighOptions(
    /** `scale.tare-by`：皮重下发到秤，还是 APP 自己扣。 */
    val tareBy: TareBy = TareBy.SCALE,
    /**
     * `scale.reading-is-net`：秤读数是否已扣皮（`Q-03`，待真机确认）。只在 [tareBy] 为 SCALE 时有意义。
     * 默认 true：原型与 v7 7.2 都按“设皮后回调的是已扣皮读数”假定。
     */
    val readingIsNet: Boolean = true,
    /** `scale.stable-by`：用秤给的稳定标志，还是读数在一段时间内几乎不动就算稳定。 */
    val stableBy: StableBy = StableBy.SDK,
    /** `scale.stable-window-ms`，只在 [stableBy] 为 APP 时用。 */
    val stableWindowMs: Long = 800,
    /** `scale.stable-tolerance-kg`，只在 [stableBy] 为 APP 时用。 */
    val stableTolerance: Kg = Kg(5),
    /** `weigh.empty-below-kg`：净重低于它视为空秤。 */
    val emptyBelow: Kg = Kg(500),
    /** `weigh.merge-tolerance-pct` */
    val mergeTolerancePct: Int = 10,
    /** `weigh.merge-max-k` */
    val mergeMaxK: Int = 8,
    /** `weigh.record-by`：稳定后自动记录，还是按“记录”才记。 */
    val recordBy: RecordBy = RecordBy.AUTO,
    /** `weigh.merge-detect`：按重量识别箱数，还是每次按 1 箱记。 */
    val mergeDetect: MergeDetect = MergeDetect.AUTO,
) {
    companion object {
        /** [get] 按选项键取文本值（如 `"scale.tare-by"`），没有则返回 null。 */
        fun of(get: (String) -> String?): WeighOptions {
            val d = WeighOptions()
            fun <T> pick(key: String, default: T, parse: (String) -> T?): T =
                get(key)?.trim()?.let { v -> runCatching { parse(v) }.getOrNull() } ?: default
            return WeighOptions(
                tareBy = pick("scale.tare-by", d.tareBy) { enumOf<TareBy>(it) },
                readingIsNet = pick("scale.reading-is-net", d.readingIsNet) { it.lowercase().toBooleanStrictOrNull() },
                stableBy = pick("scale.stable-by", d.stableBy) { enumOf<StableBy>(it) },
                stableWindowMs = pick("scale.stable-window-ms", d.stableWindowMs) { it.toLong() },
                stableTolerance = pick("scale.stable-tolerance-kg", d.stableTolerance) { Kg.parse(it) },
                emptyBelow = pick("weigh.empty-below-kg", d.emptyBelow) { Kg.parse(it) },
                mergeTolerancePct = pick("weigh.merge-tolerance-pct", d.mergeTolerancePct) { it.toInt() },
                mergeMaxK = pick("weigh.merge-max-k", d.mergeMaxK) { it.toInt() },
                recordBy = pick("weigh.record-by", d.recordBy) { enumOf<RecordBy>(it) },
                mergeDetect = pick("weigh.merge-detect", d.mergeDetect) { enumOf<MergeDetect>(it) },
            )
        }

        private inline fun <reified E : Enum<E>> enumOf(v: String): E? =
            enumValues<E>().firstOrNull { it.name.equals(v, ignoreCase = true) }
    }
}
