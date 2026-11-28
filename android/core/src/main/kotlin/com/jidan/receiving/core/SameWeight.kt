package com.jidan.receiving.core

/** 同重归类的一组：每箱净重与箱数。 */
data class WeightGroup(val weight: Jin, val boxes: Int) {
    /** 流水页与回传备注里的写法：`45.6斤-4箱`。 */
    fun label() = "${weight.format()}斤-${boxes}箱"
}

object SameWeight {
    /**
     * 按记录值（含手录值）归并，不另行舍入。
     * 箱数多的在前；箱数相同的按首次出现的箱序号（v7 6.3，开发规格 5.3），不是按重量排。
     * [weights] 按箱序号排好。
     */
    fun groups(weights: List<Jin>): List<WeightGroup> {
        val counts = LinkedHashMap<Jin, Int>()   // 保持首次出现的顺序
        weights.forEach { counts.merge(it, 1, Int::plus) }
        // 稳定排序：箱数并列时保持首次出现的顺序
        return counts.map { (w, n) -> WeightGroup(w, n) }.sortedByDescending { it.boxes }
    }
}
