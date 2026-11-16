package com.jidan.receiving.core

/** 一次称重换算的结果。 */
data class NetResult(
    /** 秤台上的总重：含 k 箱皮重，未扣皮。就是每箱毛重之和，也是 `DetectK` 的输入。 */
    val platform: Kg,
    /** k 箱合计皮重。 */
    val tareTotal: Kg,
    /** k 箱净重合计。 */
    val net: Kg,
)

/**
 * 秤读数、每箱皮重、箱数 → 净重与毛重。秤读数是否已扣皮（`Q-03`）只在这里处理，别处不再判断。
 *
 * 三种组合：
 * - `scale.tare-by = app`：没有向秤下发皮重，读数就是台面总重（`scale.reading-is-net` 无意义）。
 * - `scale.tare-by = scale`、`scale.reading-is-net = true`：秤已扣掉一份每箱皮重，台面总重 = 读数 + 每箱皮重。
 *   合并称重时秤只扣了一份，其余 k−1 份由 APP 在 [convert] 里补扣（v7 7.2）。
 * - `scale.tare-by = scale`、`scale.reading-is-net = false`：下发了皮重，但读数仍是台面总重。
 */
object NetWeight {

    /** 台面总重。[tareUnit] 是 APP 当前设的每箱皮重，未设皮重时传 0。 */
    fun platform(raw: Kg, tareUnit: Kg, o: WeighOptions): Kg =
        if (o.tareBy == TareBy.SCALE && o.readingIsNet) raw + tareUnit else raw

    fun convert(raw: Kg, tareUnit: Kg, k: Int, o: WeighOptions): NetResult {
        val platform = platform(raw, tareUnit, o)
        val tareTotal = Kg(tareUnit.cents * k)
        return NetResult(platform, tareTotal, platform - tareTotal)
    }
}
