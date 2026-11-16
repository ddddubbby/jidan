package com.jidan.receiving.ui.create

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
        assertEquals(
            listOf(CreateStep.FARMER, CreateStep.PRODUCT, CreateStep.SPEC, CreateStep.WAREHOUSE),
            missingSteps(empty),
        )
        assertEquals(CreateStep.FARMER, activeStep(empty, null))

        val mid = CreatePicks(farmerId = 1, productId = 2)
        assertEquals(listOf(CreateStep.SPEC, CreateStep.WAREHOUSE), missingSteps(mid))
        assertEquals(CreateStep.SPEC, activeStep(mid, null))
        assertEquals(CreateStep.PRODUCT, activeStep(mid, CreateStep.PRODUCT))

        val full = CreatePicks(1, 2, 3, 4)
        assertTrue(missingSteps(full).isEmpty())
        assertNull(activeStep(full, null))
    }

    @Test
    fun autoFillSingleRule() {
        assertEquals(10L to 20L, autoFillFromRules(listOf(10L to 20L)))
        assertEquals(null to null, autoFillFromRules(listOf(10L to 20L, 11L to 21L)))
        assertEquals(null to null, autoFillFromRules(emptyList()))
    }

    @Test
    fun autoFillSpecForProduct() {
        val rules = listOf(1L to 10L, 1L to 11L, 2L to 20L)
        assertNull(autoFillSpecForProduct(rules, 1L))
        assertEquals(20L, autoFillSpecForProduct(rules, 2L))
        assertNull(autoFillSpecForProduct(rules, 9L))
    }
}
