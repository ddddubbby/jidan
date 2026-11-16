package com.jidan.receiving.device

import kotlinx.coroutines.flow.StateFlow

/** 标签打印机契约（开发规格 9.3）。 */
interface LabelPrinter {
    val linkStatus: StateFlow<LinkStatus>
    val printerStatus: StateFlow<PrinterIssue>
    val pairedId: StateFlow<String?>

    suspend fun search(): List<DiscoveredDevice>
    suspend fun connect(id: String)
    suspend fun disconnect()

    /** 挂起直到出结果。 */
    suspend fun print(payload: PrintPayload): PrintOutcome
}
