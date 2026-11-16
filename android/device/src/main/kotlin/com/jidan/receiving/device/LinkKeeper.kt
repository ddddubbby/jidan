package com.jidan.receiving.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 记住上次设备；登录后自动连；丢失后回到附近自动重连（开发规格 9.3，原型 syncLinks/startLinks）。
 * Scale 与 LabelPrinter 各挂一份，或本类同时管两台。
 */
class LinkKeeper(
    private val scale: Scale,
    private val printer: LabelPrinter,
    private val scope: CoroutineScope,
    private val isScaleReachable: () -> Boolean,
    private val isPrinterReachable: () -> Boolean,
    private val persistScaleId: (String?) -> Unit,
    private val persistPrinterId: (String?) -> Unit,
    /** 蓝牙已开且已授权才去连（APP-01）；默认总是可以，模拟版与单测不受影响。 */
    private val canConnect: () -> Boolean = { true },
) {
    private var gen = 0
    private var loopJob: Job? = null
    private var scaleReconnect: Job? = null
    private var printerReconnect: Job? = null

    /** 登录时因蓝牙没开 / 没授权而没连成的设备；等条件满足后补连一次。 */
    private var pendingScale = false
    private var pendingPrinter = false

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        stop()
        val g = ++gen
        _running.value = true
        pendingScale = !scale.pairedId.value.isNullOrBlank()
        pendingPrinter = !printer.pairedId.value.isNullOrBlank()
        scope.launch { connectPending(g) }
        loopJob = scope.launch {
            while (isActive && gen == g) {
                tick(g)
                delay(500)
            }
        }
    }

    fun stop() {
        gen++
        loopJob?.cancel()
        scaleReconnect?.cancel()
        printerReconnect?.cancel()
        loopJob = null
        scaleReconnect = null
        printerReconnect = null
        _running.value = false
    }

    fun onScaleConnected(id: String) {
        persistScaleId(id)
    }

    fun onPrinterConnected(id: String) {
        persistPrinterId(id)
    }

    fun unpairScale() {
        persistScaleId(null)
        scaleReconnect?.cancel()
        scope.launch { runCatching { scale.disconnect() } }
    }

    fun unpairPrinter() {
        persistPrinterId(null)
        printerReconnect?.cancel()
        scope.launch { runCatching { printer.disconnect() } }
    }

    /** 把记住的设备连上；蓝牙没开 / 没授权时留着，tick 里等到条件满足再来。 */
    private suspend fun connectPending(g: Int) {
        if (gen != g || !canConnect()) return
        val sid = scale.pairedId.value
        if (pendingScale && !sid.isNullOrBlank()) {
            pendingScale = false
            if (scale.linkStatus.value != LinkStatus.CONNECTED && scale.linkStatus.value != LinkStatus.CONNECTING) {
                runCatching { scale.connect(sid) }
            }
        }
        val pid = printer.pairedId.value
        if (pendingPrinter && !pid.isNullOrBlank()) {
            pendingPrinter = false
            if (printer.linkStatus.value != LinkStatus.CONNECTED && printer.linkStatus.value != LinkStatus.CONNECTING) {
                runCatching { printer.connect(pid) }
            }
        }
    }

    private fun tick(g: Int) {
        if (gen != g) return
        if ((pendingScale || pendingPrinter) && canConnect()) scope.launch { connectPending(g) }
        // Scale
        val ss = scale.linkStatus.value
        if (ss == LinkStatus.CONNECTED && !isScaleReachable()) {
            // Fake 侧应把状态打成 LOST；这里只调度重连逻辑的触发点
            scheduleScaleReconnect(g)
        } else if (ss == LinkStatus.LOST && isScaleReachable()) {
            scheduleScaleReconnect(g)
        }
        // Printer
        val ps = printer.linkStatus.value
        if (ps == LinkStatus.CONNECTED && !isPrinterReachable()) {
            schedulePrinterReconnect(g)
        } else if (ps == LinkStatus.LOST && isPrinterReachable()) {
            schedulePrinterReconnect(g)
        }
    }

    private fun scheduleScaleReconnect(g: Int) {
        if (scaleReconnect?.isActive == true) return
        val id = scale.pairedId.value ?: return
        scaleReconnect = scope.launch {
            delay(RECONNECT_MS)
            if (gen != g) return@launch
            if (!isScaleReachable()) return@launch
            val st = scale.linkStatus.value
            if (st == LinkStatus.CONNECTED || st == LinkStatus.CONNECTING) return@launch
            runCatching { scale.connect(id) }
        }
    }

    private fun schedulePrinterReconnect(g: Int) {
        if (printerReconnect?.isActive == true) return
        val id = printer.pairedId.value ?: return
        printerReconnect = scope.launch {
            delay(RECONNECT_MS)
            if (gen != g) return@launch
            if (!isPrinterReachable()) return@launch
            val st = printer.linkStatus.value
            if (st == LinkStatus.CONNECTED || st == LinkStatus.CONNECTING) return@launch
            runCatching { printer.connect(id) }
        }
    }

    companion object {
        const val RECONNECT_MS = 1500L
    }
}
