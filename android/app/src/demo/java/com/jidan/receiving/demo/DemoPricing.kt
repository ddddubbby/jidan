package com.jidan.receiving.demo

import com.jidan.receiving.data.QuoteResult
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 【仅演示版】离线计价，口径与后端 `PricingService.compute` 一致（v7 2.2、6.4）：
 * 均重 = 总净重 ÷ 箱数，按 `round.mode` 取整到整数 kg（精确商上取整，默认四舍五入）；
 * 价差 = 系数 × (均重 − W0)，收购价格 = 基准价 + 价差，各自四舍五入到分（HALF_UP）；金额 = 收购价格 × 箱数。
 *
 * 真实系统只在后端计价（开发规格 2.3）；这份只为演示版没有后端时能算出同样的数，不进其他构建。
 * 与后端的一致性由 `fixtures/pricing.json` 的算例在 `DemoPricingTest` 里核对。
 */
object DemoPricing {
    const val NO_RULE = "NO_RULE"
    const val NO_BASE_PRICE = "NO_BASE_PRICE"

    /** `half` / `floor` / `ceil`（系统设置 `round.mode`），认不出的按默认四舍五入。 */
    fun roundingOf(mode: String): Pair<String, RoundingMode> = when (mode.lowercase()) {
        "floor" -> "FLOOR" to RoundingMode.FLOOR
        "ceil" -> "CEIL" to RoundingMode.CEILING
        else -> "HALF" to RoundingMode.HALF_UP
    }

    /**
     * @param basePrice null 表示当日没录基准价
     * @param coef      null 表示没有规则
     */
    fun compute(
        boxNetKg: List<BigDecimal>,
        standardNetKg: BigDecimal,
        basePrice: BigDecimal?,
        ruleId: Long?,
        ruleName: String?,
        coef: BigDecimal?,
        roundMode: String,
    ): QuoteResult {
        val (wire, rounding) = roundingOf(roundMode)
        val issues = buildList {
            if (coef == null) add(NO_RULE)
            if (basePrice == null) add(NO_BASE_PRICE)
        }
        val n = boxNetKg.size
        val total = boxNetKg.fold(BigDecimal.ZERO, BigDecimal::add)
        if (n == 0) {
            return QuoteResult(
                boxCount = 0,
                totalKg = total.toDouble(),
                standardNetKg = standardNetKg.toDouble(),
                basePrice = basePrice?.toDouble(),
                ruleId = ruleId,
                ruleName = ruleName,
                coefficient = coef?.toDouble(),
                roundMode = wire,
                issues = issues,
            )
        }
        val count = BigDecimal.valueOf(n.toLong())
        val rawAvg = total.divide(count, 4, RoundingMode.HALF_UP)
        val avg = total.divide(count, 0, rounding)
        val tail = avg.subtract(standardNetKg)
        val diff = coef?.multiply(tail)?.setScale(2, RoundingMode.HALF_UP)
        val unit = if (diff != null && basePrice != null) basePrice.add(diff).setScale(2, RoundingMode.HALF_UP) else null
        val amount = unit?.multiply(count)?.setScale(2, RoundingMode.HALF_UP)
        return QuoteResult(
            boxCount = n,
            totalKg = total.toDouble(),
            rawAvgKg = rawAvg.toDouble(),
            avgKg = avg.toDouble(),
            standardNetKg = standardNetKg.toDouble(),
            basePrice = basePrice?.toDouble(),
            ruleId = ruleId,
            ruleName = ruleName,
            coefficient = coef?.toDouble(),
            tailKg = tail.toDouble(),
            priceDiff = diff?.toDouble(),
            unitPrice = unit?.toDouble(),
            amount = amount?.toDouble(),
            roundMode = wire,
            issues = issues,
        )
    }
}
