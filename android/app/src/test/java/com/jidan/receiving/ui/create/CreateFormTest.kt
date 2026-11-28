package com.jidan.receiving.ui.create

import com.jidan.receiving.data.RuleViewDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateFormTest {
    @Test
    fun digitsTailKeepsFour() {
        assertEquals("1234", digitsTail("abc12345"))
        assertEquals("12", digitsTail("12"))
        assertEquals("", digitsTail("xx"))
    }

    @Test
    fun missingAndActiveOrder() {
        val empty = CreatePicks()
        assertEquals(listOf(CreateStep.FARMER, CreateStep.RULE, CreateStep.WAREHOUSE), missingSteps(empty))
        assertEquals(CreateStep.FARMER, activeStep(empty, null))

        val mid = CreatePicks(farmerId = 1)
        assertEquals(listOf(CreateStep.RULE, CreateStep.WAREHOUSE), missingSteps(mid))
        assertEquals(CreateStep.RULE, activeStep(mid, null))
        assertEquals(CreateStep.FARMER, activeStep(mid, CreateStep.FARMER))

        val full = CreatePicks(1, 2, 3)
        assertTrue(missingSteps(full).isEmpty())
        assertNull(activeStep(full, null))
    }

    private fun rule(id: Long, name: String, loss: Double? = 0.0) = RuleViewDto(
        id = id, name = name, kind = "BUY", productId = 1, productName = "粉蛋", specId = 2, specName = "360枚/箱",
        tareBoxId = 3, tareName = "2号包装箱", tareJin = 4.0, lossJin = loss, coef = 0.5,
    )

    @Test
    fun packTextAndLoss() {
        assertEquals("粉蛋 360枚/箱 · 2号包装箱 4 斤", packText(rule(1, "a")))
        assertEquals("粉蛋 360枚/箱 · 2号包装箱 4 斤 · 每箱 −1 斤", packText(rule(1, "a", -1.0)))
        assertEquals("+0.5 斤", lossText(0.5))
        assertNull(lossText(0.0))
    }

}
