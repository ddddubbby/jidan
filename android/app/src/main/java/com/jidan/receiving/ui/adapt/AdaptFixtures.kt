package com.jidan.receiving.ui.adapt

/**
 * APP 适配走查夹具（v7 4.4-6、开发规格第 10 章）。
 * 与 lab/app-sim.html 最坏情况户名、100 箱压力对齐；Fake 路径，未经实物验证。
 */
object AdaptFixtures {
    /** 原型 WORST 合作社名（26 字；v7 举例「如 27 字」），不得截断。 */
    const val WORST_FARMER_NAME = "山西省晋中市太谷区鑫源生态养殖专业合作社（第二分社）"

    const val WORST_NAME_MIN_LEN = 26

    const val STRESS_BOX_COUNT = 100

    /** 李四 10 箱净重（kg），循环灌入压力箱。 */
    val SAMPLE_NETS_KG: DoubleArray = doubleArrayOf(
        46.5, 45.8, 46.0, 46.0, 46.0, 45.6, 45.6, 45.6, 45.6, 47.8,
    )

    fun netKgForIndex(i: Int): Double = SAMPLE_NETS_KG[i % SAMPLE_NETS_KG.size]

    fun assertWorstNameUsable(name: String = WORST_FARMER_NAME): Boolean =
        name.length >= WORST_NAME_MIN_LEN && !name.contains('…') && !name.endsWith("...")
}
