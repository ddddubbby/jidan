package com.jidan.receiving.ui.summary

import com.jidan.receiving.data.QuoteResult
import com.jidan.receiving.ui.weigh.SummaryUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 汇总页主按钮：缺价时默认拦截，allow 时可提交。 */
class SubmitGateTest {
    private fun canSubmit(state: SummaryUiState): Boolean {
        val q = state.quote ?: return false
        if (state.submitting || state.loading || state.refreshing) return false
        if (!state.allowWithoutPrice && (q.noBasePrice || q.noRule)) return false
        return true
    }

    @Test
    fun blockMissingByDefault() {
        val q = QuoteResult(issues = listOf("NO_RULE"))
        assertFalse(canSubmit(SummaryUiState(quote = q, allowWithoutPrice = false)))
    }

    @Test
    fun allowWithoutPriceCanSubmit() {
        val q = QuoteResult(boxCount = 1, issues = listOf("NO_RULE", "NO_BASE_PRICE"))
        assertTrue(canSubmit(SummaryUiState(quote = q, allowWithoutPrice = true)))
    }

    @Test
    fun pricedCanSubmit() {
        val q = QuoteResult(boxCount = 10, unitPrice = 181.0, amount = 1810.0)
        assertTrue(canSubmit(SummaryUiState(quote = q)))
    }
}
