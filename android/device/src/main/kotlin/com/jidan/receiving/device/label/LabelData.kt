package com.jidan.receiving.device.label

import com.jidan.receiving.core.Kg

/**
 * 一张箱贴要印的字段（v7 5.4）。二维码内容固定为 `批次号#批次序号`。
 * 两种版式都不含均重。
 */
data class LabelData(
    val productName: String,
    val specName: String,
    val net: Kg,
    val farmerName: String,
    val buyDate: String,
    val warehouseName: String,
    val batchNo: String,
    val seq: Int,
) {
    /** 箱码：`批次号#批次序号`（BE-06 / C-1）。 */
    val qrPayload: String get() = "$batchNo#$seq"
}
