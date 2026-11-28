package com.jidan.receiving.device.label

import com.jidan.receiving.core.Jin

/**
 * 一张箱贴要印的字段（v7 5.4）。二维码内容固定为 `批次号#批次序号`。
 * 两种版式都不含均重。
 */
data class LabelData(
    val productName: String,
    val specName: String,
    val net: Jin,
    val farmerName: String,
    val buyDate: String,
    val warehouseName: String,
    val batchNo: String,
    val seq: Int,
) {
    /** 箱码：`批次号#批次序号`（BE-06 / C-1）。 */
    val qrPayload: String get() = "$batchNo#$seq"

    companion object {
        private val BATCH_DATE = Regex("^SG(\\d{4})(\\d{2})(\\d{2})-")

        /** 批次号 `SG20261116-1` 里的收购日期，写成 `2026.11.16`；批次号不合格式返回 null。 */
        fun buyDateOf(batchNo: String): String? {
            val m = BATCH_DATE.find(batchNo) ?: return null
            val (y, mo, d) = m.destructured
            return "$y.${mo.toInt()}.${d.toInt()}"
        }
    }
}
