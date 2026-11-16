package com.jidan.receiving.core

/**
 * `scale.stable-by = app` 时由 APP 判断读数是否稳定：最近 [windowMs] 毫秒内读数的最大值与最小值
 * 相差不超过 [tolerance]，就算稳定。不看秤给的稳定标志。
 */
class Stability(private val windowMs: Long, private val tolerance: Kg) {
    private class Sample(val at: Long, val cents: Int)

    private val samples = ArrayDeque<Sample>()

    /** 收到一个新读数，返回此刻是否稳定。[nowMs] 用单调递增的毫秒数。 */
    fun update(raw: Kg, nowMs: Long): Boolean {
        samples.addLast(Sample(nowMs, raw.cents))
        // 读数一动超出容差，就从最老的丢起，直到剩下的又都在容差内
        while (samples.maxOf { it.cents } - samples.minOf { it.cents } > tolerance.cents) samples.removeFirst()
        return check(nowMs)
    }

    /** 没有新读数时（读数不变秤就不回调）也能判断：最后一个读数一直算还在秤上。 */
    fun check(nowMs: Long): Boolean {
        // 窗口之前的老读数不用留，只留最后一个在窗口开始之前的作起点
        while (samples.size >= 2 && samples[1].at <= nowMs - windowMs) samples.removeFirst()
        return samples.isNotEmpty() && nowMs - samples.first().at >= windowMs
    }

    fun reset() = samples.clear()
}
