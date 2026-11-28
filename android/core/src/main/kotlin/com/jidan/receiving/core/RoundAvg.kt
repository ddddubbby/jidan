package com.jidan.receiving.core

/** 均重取整规则，对应系统设置 `round`（v7 2.2）。 */
enum class RoundRule(val wire: String) {
    /** 四舍五入（默认）：小数部分 >= 0.5 进 1。 */
    HALF("half"),

    /** 抹零：舍去全部小数。 */
    FLOOR("floor"),

    /** 进位：有小数即进 1。 */
    CEIL("ceil");

    companion object {
        val DEFAULT = HALF

        fun fromWire(wire: String): RoundRule = entries.first { it.wire == wire }
    }
}

object RoundAvg {
    /**
     * 本批次累计净重 ÷ 箱数，按规则取整到整数 斤。
     * 只用于称重页的实时均重；计价以后端 `PricingService` 为准，两边由 `fixtures/pricing.json` 保证一致。
     * 全程整数运算，正好 .5 的情况不受浮点误差影响。
     */
    fun of(total: Jin, boxCount: Int, rule: RoundRule = RoundRule.DEFAULT): Jin {
        val t = total.cents
        val d = boxCount * 100
        val whole = when (rule) {
            RoundRule.FLOOR -> Math.floorDiv(t, d)
            RoundRule.CEIL -> Math.floorDiv(t + d - 1, d)
            RoundRule.HALF -> Math.floorDiv(2 * t + d, 2 * d)
        }
        return Jin(whole * 100)
    }
}
