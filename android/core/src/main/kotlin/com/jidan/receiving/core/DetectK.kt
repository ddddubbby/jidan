package com.jidan.receiving.core

/**
 * 箱数识别的结果。
 * [candidates] 非空表示识别不准：相邻两个箱数下每箱净重都在标准净含量的容差内，
 * 小在前；此时 [k] 仍是更接近的那个，是否暂停等人选由称重状态机决定。
 */
data class DetectResult(
    val k: Int,
    val tareTotal: Jin,
    val netTotal: Jin,
    val candidates: Pair<Int, Int>?,
) {
    val ambiguous get() = candidates != null
}

object DetectK {
    /**
     * 按重量识别箱数（v7 `APP-05`，原型 `detectK`）：取使每箱净重最接近 [standardNet]（`W0`）的 k，k 从 1 到 [maxK]；
     * 两个 k 一样近时取小的。
     *
     * [gross] 是秤上总重，含每箱皮重、未扣皮；[tareUnit] 是每箱皮重。读数是否已被秤扣过皮（`Q-03`）
     * 由 `NetWeight` 先换算，这里不管。
     * 秤面是否有箱子（净重不足 5斤 视为空秤）也不在这里判断，由称重状态机处理；空秤时调用方不会来识别。
     * [tolerancePct]、[maxK] 对应选项 `weigh.merge-tolerance-pct`、`weigh.merge-max-k`。
     */
    fun detect(gross: Jin, tareUnit: Jin, standardNet: Jin, tolerancePct: Int, maxK: Int): DetectResult {
        // 第 k 箱的每箱净重偏差 = |gross − k·(tare + W0)| ÷ k。全用整数：比较时交叉相乘，避免除不尽
        val g = gross.cents.toLong()
        val step = (tareUnit.cents + standardNet.cents).toLong()
        fun num(k: Int) = Math.abs(g - k * step)

        var best = 1
        for (k in 2..maxK) if (num(k) * best < num(best) * k) best = k

        // 容差：每箱偏差 ≤ W0 × pct%
        fun within(k: Int) = k in 1..maxK && num(k) * 100 <= tolerancePct.toLong() * standardNet.cents * k
        val neighbor = listOf(best - 1, best + 1).firstOrNull { within(it) }
        val candidates = neighbor?.let { Pair(minOf(best, it), maxOf(best, it)) }

        val tareTotal = Jin(tareUnit.cents * best)
        return DetectResult(best, tareTotal, gross - tareTotal, candidates)
    }
}
