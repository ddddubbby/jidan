package com.jidan.receiving.registry

import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrintPayload
import com.jidan.receiving.device.PrinterIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实机版占位打印机（P6-2）。P8-2 换成 GainschaPrinter。
 * 未经实物验证。
 */
class PlaceholderPrinter(
    private val modelKey: String = "gainscha-gp-m32b",
) : LabelPrinter {
    private val _link = MutableStateFlow(LinkStatus.DISCONNECTED)
    private val _status = MutableStateFlow(PrinterIssue.OK)
    private val _paired = MutableStateFlow<String?>(null)

    override val linkStatus: StateFlow<LinkStatus> = _link.asStateFlow()
    override val printerStatus: StateFlow<PrinterIssue> = _status.asStateFlow()
    override val pairedId: StateFlow<String?> = _paired.asStateFlow()

    override suspend fun search(): List<DiscoveredDevice> = emptyList()
    override suspend fun connect(id: String) {
        _paired.value = id
        _link.value = LinkStatus.DISCONNECTED
    }
    override suspend fun disconnect() {
        _link.value = LinkStatus.DISCONNECTED
    }
    override suspend fun print(payload: PrintPayload): PrintOutcome =
        PrintOutcome.Failure("占位打印机，未经实物验证（P8-2）")

    override fun toString(): String = "PlaceholderPrinter($modelKey)"
}
