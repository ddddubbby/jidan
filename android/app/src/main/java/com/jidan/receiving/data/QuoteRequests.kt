package com.jidan.receiving.data

import com.jidan.receiving.core.Session

/**
 * 计价预览请求。日期取批次号里的收购日期：提交按它计价，预览也按它，
 * 草稿隔天继续时汇总页金额才与入库金额一致（F1-3）。批次号格式不对时不带日期，后端按当日。
 */
fun quoteRequestOf(s: Session, trialRuleId: Long? = null, trialCoef: Double? = null): QuoteRequest =
    QuoteRequest(
        vendorId = s.farmer.id,
        productId = s.product.id,
        specId = s.spec.id,
        date = BatchNo.dateOf(s.batchNo),
        boxNetKg = s.boxes.map { it.net.toDouble() },
        trialRuleId = trialRuleId,
        trialCoef = trialCoef,
    )
