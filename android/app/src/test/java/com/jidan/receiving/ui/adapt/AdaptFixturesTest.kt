package com.jidan.receiving.ui.adapt

import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptFixturesTest {
    @Test
    fun worstNameMeetsV7Length() {
        assertTrue(AdaptFixtures.WORST_FARMER_NAME.length >= AdaptFixtures.WORST_NAME_MIN_LEN)
        assertTrue(AdaptFixtures.assertWorstNameUsable())
    }

    @Test
    fun seed100BoxesViaSession() {
        var s = Session(
            batchNo = "SG20261116-001",
            farmer = Pick(1, AdaptFixtures.WORST_FARMER_NAME),
            product = Pick(2, "粉壳鲜鸡蛋（散养）"),
            spec = Pick(3, "360枚/箱"),
            warehouse = Pick(4, "太谷 A&B 冷链周转仓（东区 2 号库）"),
        )
        repeat(AdaptFixtures.STRESS_BOX_COUNT) { i ->
            s = s.addManual(Jin.of(AdaptFixtures.netJinForIndex(i)))
        }
        assertEquals(100, s.boxes.size)
        assertEquals(AdaptFixtures.WORST_FARMER_NAME, s.farmer.name)
        // 累计净重应与样例循环一致（10 箱一组）
        val ten = AdaptFixtures.SAMPLE_NETS_JIN.sum()
        assertEquals(ten * 10, s.totalNet.toDouble(), 0.01)
    }
}
