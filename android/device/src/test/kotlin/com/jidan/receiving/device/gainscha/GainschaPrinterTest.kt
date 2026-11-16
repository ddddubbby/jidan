package com.jidan.receiving.device.gainscha

import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrintPayload
import com.jidan.receiving.device.PrinterIssue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 对着脚本化的 [GainschaSdk] 验证适配器自己的逻辑；SDK 一层的真实表现未经实物验证。 */
@OptIn(ExperimentalCoroutinesApi::class)
class GainschaPrinterTest {
    private class ScriptedSdk : GainschaSdk {
        override var onDisconnected: (() -> Unit)? = null
        var connectResult = true
        var statusByte: Int? = 0x00
        var trackCode: Int? = 0x20
        var sendError: IOException? = null
        val sent = mutableListOf<ByteArray>()
        var asks = 0

        override suspend fun search() = listOf(DiscoveredDevice("AA:01", "GP-M32B", 3))
        override suspend fun connect(id: String) = connectResult
        override fun disconnect() {}
        override suspend fun printTracked(data: ByteArray): Int? {
            sendError?.let { throw it }
            sent += data
            return trackCode
        }
        override suspend fun ask(command: ByteArray): ByteArray? {
            asks++
            assertContentEquals(GainschaCodes.STATUS_QUERY, command)
            return statusByte?.let { byteArrayOf(it.toByte()) }
        }
    }

    private val logs = mutableListOf<String>()

    private fun TestScope.printer(sdk: ScriptedSdk, paired: String? = null) =
        GainschaPrinter(sdk, backgroundScope, paired, log = { logs += it })

    @Test
    fun connectThenPrintSendsGbBytesAndLogsTspl() = runTest {
        val sdk = ScriptedSdk()
        val p = printer(sdk)
        p.connect("AA:01")
        assertEquals(LinkStatus.CONNECTED, p.linkStatus.value)
        assertEquals("AA:01", p.pairedId.value)

        val tspl = "SIZE 60 mm,40 mm\r\nCLS\r\nTEXT 24,24,\"TSS24.BF2\",0,1,1,\"收蛋\"\r\nPRINT 1\r\n"
        assertEquals(PrintOutcome.Success, p.print(PrintPayload.Text(tspl)))
        assertContentEquals(TsplBytes.encode(tspl), sdk.sent.single())
        assertTrue(logs.any { it.contains("TSS24.BF2") && it.contains("PRINT 1") }, "指令文本要打到日志")
    }

    @Test
    fun printWhileDisconnectedFailsWithoutSending() = runTest {
        val sdk = ScriptedSdk()
        val p = printer(sdk)
        assertEquals(PrintOutcome.Failure("打印机未连接"), p.print(PrintPayload.Text("CLS\r\n")))
        assertTrue(sdk.sent.isEmpty())
    }

    @Test
    fun paperOutIsCaughtBeforeSending() = runTest {
        val sdk = ScriptedSdk()
        val p = printer(sdk)
        p.connect("AA:01")
        sdk.statusByte = 0x04
        assertEquals(PrintOutcome.Failure("缺纸"), p.print(PrintPayload.Text("CLS\r\n")))
        assertEquals(PrinterIssue.NO_PAPER, p.printerStatus.value)
        assertTrue(sdk.sent.isEmpty(), "缺纸时不白发")
        // 装好纸后恢复
        sdk.statusByte = 0x00
        assertEquals(PrintOutcome.Success, p.print(PrintPayload.Text("CLS\r\n")))
        assertEquals(PrinterIssue.OK, p.printerStatus.value)
    }

    @Test
    fun trackedFaultCodeFailsAndShowsOnStatusTag() = runTest {
        val sdk = ScriptedSdk()
        val p = printer(sdk)
        p.connect("AA:01")
        sdk.trackCode = 0x24
        assertEquals(PrintOutcome.Failure("开盖"), p.print(PrintPayload.Text("CLS\r\n")))
        assertEquals(PrinterIssue.OPEN_COVER, p.printerStatus.value)
    }

    @Test
    fun noTrackReplyCountsAsSuccess() = runTest {
        val sdk = ScriptedSdk().also { it.trackCode = null }
        val p = printer(sdk)
        p.connect("AA:01")
        assertEquals(PrintOutcome.Success, p.print(PrintPayload.Text("CLS\r\n")))
    }

    @Test
    fun sendErrorFailsAndLinkBecomesLost() = runTest {
        val sdk = ScriptedSdk()
        val p = printer(sdk)
        p.connect("AA:01")
        sdk.sendError = IOException("broken pipe")
        val r = p.print(PrintPayload.Text("CLS\r\n")) as PrintOutcome.Failure
        assertTrue(r.reason.contains("broken pipe"))
        assertEquals(LinkStatus.LOST, p.linkStatus.value)
    }

    @Test
    fun sdkDisconnectCallbackMakesLost() = runTest {
        val sdk = ScriptedSdk()
        val p = printer(sdk)
        p.connect("AA:01")
        sdk.onDisconnected!!.invoke()
        assertEquals(LinkStatus.LOST, p.linkStatus.value)
    }

    @Test
    fun failedReconnectOfPairedDeviceStaysLostSoLinkKeeperRetries() = runTest {
        val sdk = ScriptedSdk().also { it.connectResult = false }
        val p = printer(sdk, paired = "AA:01")
        p.connect("AA:01")
        assertEquals(LinkStatus.LOST, p.linkStatus.value)
        // 新选的另一台连不上：回到未连接
        p.connect("BB:02")
        assertEquals(LinkStatus.DISCONNECTED, p.linkStatus.value)
        assertEquals("AA:01", p.pairedId.value)
    }

    @Test
    fun pollingKeepsStatusTagFresh() = runTest {
        val sdk = ScriptedSdk()
        val p = printer(sdk)
        p.connect("AA:01")
        runCurrent()
        assertEquals(PrinterIssue.OK, p.printerStatus.value)
        sdk.statusByte = 0x01
        advanceTimeBy(GainschaPrinter.POLL_MS + 1)
        runCurrent()
        assertEquals(PrinterIssue.OPEN_COVER, p.printerStatus.value)
        sdk.statusByte = null // 没回复：保持原状
        advanceTimeBy(GainschaPrinter.POLL_MS + 1)
        runCurrent()
        assertEquals(PrinterIssue.OPEN_COVER, p.printerStatus.value)
        p.disconnect()
        val asks = sdk.asks
        advanceTimeBy(GainschaPrinter.POLL_MS * 3)
        runCurrent()
        assertEquals(asks, sdk.asks, "断开后不再轮询")
        assertEquals(PrinterIssue.OK, p.printerStatus.value)
    }

    @Test
    fun failedConnectNeverPretendsToPrint() = runTest {
        val p = printer(ScriptedSdk().apply { connectResult = false }, paired = "AA:01")
        p.connect("AA:01")
        assertEquals(LinkStatus.LOST, p.linkStatus.value)
        assertEquals(PrintOutcome.Failure("打印机未连接"), p.print(PrintPayload.Text("CLS\r\n")))
    }
}
