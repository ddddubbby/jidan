package com.jidan.receiving.data

/** 批次号 `SG20261115-2`：中间八位是收购日期（v7 5.5，后端 `BatchNo` 同一规则）。 */
object BatchNo {
    private val PATTERN = Regex("^SG(\\d{4})(\\d{2})(\\d{2})-[1-9]\\d*$")

    /** 批次号里的收购日期，格式 `yyyy-MM-dd`（接口的 date 字段）；格式不对返回 null。 */
    fun dateOf(batchNo: String): String? {
        val m = PATTERN.matchEntire(batchNo) ?: return null
        val (y, mo, d) = m.destructured
        return "$y-$mo-$d"
    }
}
