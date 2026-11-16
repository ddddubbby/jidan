package com.jidan.receiving.ui.weigh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSheetStateTest {
    @Test
    fun dirtyWhenDraftDiffers() {
        val clean = RuleSheetState(boundRuleId = 1, boundCoef = 1.0, draftCoef = null)
        assertFalse(clean.dirty)
        val dirty = clean.copy(draftCoef = 1.1)
        assertTrue(dirty.dirty)
        assertTrue(dirty.displayCoef == 1.1)
    }

    @Test
    fun unboundWithoutRuleId() {
        assertTrue(RuleSheetState().unbound)
        assertFalse(RuleSheetState(boundRuleId = 2).unbound)
    }
}
