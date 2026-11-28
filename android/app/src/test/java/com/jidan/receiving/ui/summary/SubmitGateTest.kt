package com.jidan.receiving.ui.summary

import com.jidan.receiving.data.QuoteResult
import com.jidan.receiving.ui.weigh.SummaryUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 汇总页主按钮：测的是生产代码 `SummaryUiState.canSubmit`（ViewModel 提交前与按钮状态共用）。 */
class SubmitGateTest {
    @Test
    fun blockMissingByDefault() {
        val q = QuoteResult(issues = listOf("NO_RULE"))
        assertFalse(SummaryUiState(quote = q, allowWithoutPrice = false).canSubmit)
    }

    @Test
    fun allowWithoutPriceCanSubmit() {
        val q = QuoteResult(boxCount = 1, issues = listOf("NO_BASE_PRICE"))
        assertTrue(SummaryUiState(quote = q, allowWithoutPrice = true).canSubmit)
    }

    @Test
    fun noRuleCannotSubmitEvenWhenMissingPriceIsAllowed() {
        // 单据必须带所选规则（附录 E R-1 行），没选规则时先选，不能先交
        val q = QuoteResult(boxCount = 1, issues = listOf("NO_RULE", "NO_BASE_PRICE"))
        assertFalse(SummaryUiState(quote = q, allowWithoutPrice = true).canSubmit)
    }

    @Test
    fun pricedCanSubmit() {
        val q = QuoteResult(boxCount = 10, unitPrice = 181.0, amount = 1810.0)
        assertTrue(SummaryUiState(quote = q).canSubmit)
    }

    @Test
    fun noQuoteCannotSubmitEvenWhenMissingPriceIsAllowed() {
        assertFalse(SummaryUiState(quote = null).canSubmit)
        assertFalse(SummaryUiState(quote = null, allowWithoutPrice = true).canSubmit)
    }

    @Test
    fun busyStatesCannotSubmit() {
        val q = QuoteResult(boxCount = 10, unitPrice = 181.0, amount = 1810.0)
        assertFalse(SummaryUiState(quote = q, submitting = true).canSubmit)
        assertFalse(SummaryUiState(quote = q, loading = true).canSubmit)
        assertFalse(SummaryUiState(quote = q, refreshing = true).canSubmit)
    }
}
