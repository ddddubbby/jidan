package com.jidan.receiving.data

import com.google.gson.Gson
import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 计价预览按批次号里的收购日期（F1-3）：草稿隔天继续，汇总页金额与提交时一致。 */
class QuoteRequestTest {
    private fun session(batchNo: String) = Session(
        batchNo = batchNo,
        farmer = Pick(1, "李四"), product = Pick(2, "粉蛋"), spec = Pick(3, "360枚/箱"), warehouse = Pick(4, "一号库"),
        rule = Pick(7, "粉蛋李四收购定价方案"),
    ).addManual(Jin.of(46.0)).addManual(Jin.of(45.5))

    @Test
    fun dateInRequestEqualsDateInBatchNo() {
        val req = quoteRequestOf(session("SG20261111-2"))!!
        assertEquals("2026-11-11", req.date)
        assertEquals(listOf(46.0, 45.5), req.boxNetJin)
        assertEquals(7L, req.ruleId)
    }

    @Test
    fun dateIsInTheRequestBody() {
        // 请求体按 Retrofit 的 Gson 转换器同样的方式写出
        val body = Gson().toJson(quoteRequestOf(session("SG20261111-2"), trialCoef = 1.5))
        assertTrue("\"date\":\"2026-11-11\"" in body, body)
        assertTrue("\"trialCoef\":1.5" in body, body)
    }

    @Test
    fun otherRuleAndTrialPassThrough() {
        val req = quoteRequestOf(session("SG20261116-12"), ruleId = 9, trialLossJin = -1.0)!!
        assertEquals(9L, req.ruleId)
        assertNull(req.trialCoef)
        assertEquals(-1.0, req.trialLossJin)
        assertEquals("2026-11-16", req.date)
    }

    @Test
    fun draftWithoutRuleHasNoRequest() {
        // 改版前的草稿没有规则：不调后端，汇总页直接让选规则
        assertNull(quoteRequestOf(session("SG20261116-12").copy(rule = null)))
    }

    @Test
    fun batchNoOfOtherShapeFallsBackToServerToday() {
        assertNull(BatchNo.dateOf("SG-1"))
        assertNull(BatchNo.dateOf("XX20261111-2"))
        assertNull(BatchNo.dateOf("SG20261111-0"))
        assertNull(quoteRequestOf(session("SG-1"))!!.date)
    }
}
