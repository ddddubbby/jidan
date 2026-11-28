package com.jidan.receiving.data

import com.jidan.receiving.core.Session

/**
 * 计价预览请求。日期取批次号里的收购日期：提交按它计价，预览也按它，
 * 草稿隔天继续时汇总页金额才与入库金额一致（F1-3）。批次号格式不对时不带日期，后端按当日。
 * [ruleId] 缺省用这张单选的规则；这张单还没有规则时返回 null（不调后端，界面直接让选规则）。
 */
fun quoteRequestOf(
    s: Session,
    ruleId: Long? = null,
    trialCoef: Double? = null,
    trialLossJin: Double? = null,
): QuoteRequest? {
    val id = ruleId ?: s.rule?.id ?: return null
    return QuoteRequest(
        ruleId = id,
        date = BatchNo.dateOf(s.batchNo),
        boxNetJin = s.boxes.map { it.net.toDouble() },
        trialCoef = trialCoef,
        trialLossJin = trialLossJin,
    )
}
