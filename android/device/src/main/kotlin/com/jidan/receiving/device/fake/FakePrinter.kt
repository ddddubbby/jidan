package com.jidan.receiving.device.fake

import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrintPayload
import com.jidan.receiving.device.PrinterIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 模拟打印机。未经实物验证。 */
class FakePrinter(
    private val scope: CoroutineScope,
    initialPairedId: String? = MINE_ID,
) : LabelPrinter {
    private val _link = MutableStateFlow(LinkStatus.DISCONNECTED)
    private val _status = MutableStateFlow(PrinterIssue.OK)
    private val _paired = MutableStateFlow(initialPairedId)

    override val linkStatus: StateFlow<LinkStatus> = _link.asStateFlow()
    override val printerStatus: StateFlow<PrinterIssue> = _status.asStateFlow()
    override val pairedId: StateFlow<String?> = _paired.asStateFlow()

    var reachable: Boolean = true
        set(value) {
            field = value
            if (!value && _link.value == LinkStatus.CONNECTED) {
                _link.value = LinkStatus.LOST
            }
        }

    var lastPrinted: PrintPayload? = null
        private set

    private var connectJob: Job? = null

    val catalog: List<DiscoveredDevice> = listOf(
        DiscoveredDevice(MINE_ID, "GP-M32B", 3),
        DiscoveredDevice("9E11", "GP-M32B", 2),
    )

    fun setIssue(issue: PrinterIssue) {
        _status.value = issue
    }

    override suspend fun search(): List<DiscoveredDevice> =
        if (reachable) catalog else emptyList()

    override suspend fun connect(id: String) {
        connectJob?.cancel()
        if (!reachable || catalog.none { it.id == id }) {
            _link.value = LinkStatus.DISCONNECTED
            return
        }
        _paired.value = id
        _link.value = LinkStatus.CONNECTING
        connectJob = scope.launch {
            delay(FakeScale.CONNECT_MS)
            if (!reachable) {
                _link.value = LinkStatus.LOST
                return@launch
            }
            _link.value = LinkStatus.CONNECTED
        }
        connectJob?.join()
    }

    override suspend fun disconnect() {
        connectJob?.cancel()
        _link.value = LinkStatus.DISCONNECTED
    }

    override suspend fun print(payload: PrintPayload): PrintOutcome {
        if (_link.value != LinkStatus.CONNECTED) {
            return PrintOutcome.Failure("打印机未连接")
        }
        if (_status.value != PrinterIssue.OK) {
            val reason = when (_status.value) {
                PrinterIssue.NO_PAPER -> "缺纸"
                PrinterIssue.OPEN_COVER -> "开盖"
                PrinterIssue.OVERHEAT -> "过热"
                PrinterIssue.OK -> ""
            }
            return PrintOutcome.Failure(reason)
        }
        delay(300)
        lastPrinted = payload
        return PrintOutcome.Success
    }

    companion object {
        const val MINE_ID = "7C20"
    }
}
