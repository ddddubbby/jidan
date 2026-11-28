package com.jidan.receiving.printing

import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.Session
import com.jidan.receiving.core.Tare
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrintPayload
import com.jidan.receiving.device.PrinterIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 每张标签的打印都停在门口，由测试放行，这样能在“正打到一半”时做别的事。 */
private class GatedPrinter : LabelPrinter {
    override val linkStatus: MutableStateFlow<LinkStatus> = MutableStateFlow(LinkStatus.CONNECTED)
    override val printerStatus: MutableStateFlow<PrinterIssue> = MutableStateFlow(PrinterIssue.OK)
    override val pairedId: StateFlow<String?> = MutableStateFlow(null)

    val printed = mutableListOf<String>()
    val arrived = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

    override suspend fun search(): List<DiscoveredDevice> = emptyList()
    override suspend fun connect(id: String) = Unit
    override suspend fun disconnect() = Unit

    override suspend fun print(payload: PrintPayload): PrintOutcome {
        val gate = CompletableDeferred<Unit>()
        arrived.send(gate)
        gate.await()
        printed += (payload as PrintPayload.Text).tspl
        return PrintOutcome.Success
    }
}

class PrintQueueTest {
    private fun session(boxes: Int): Session {
        var s = Session(
            batchNo = "SG20261116-1",
            farmer = Pick(1, "李四"), product = Pick(2, "粉蛋"), spec = Pick(3, "360枚/箱"), warehouse = Pick(4, "一号库"),
            tare = Tare(Jin.of(2.0), "2号箱"),
        )
        repeat(boxes) { s = s.addManual(Jin.of(46.0 + it)) }
        return s
    }

    private fun Session.allPrinted() = boxes.fold(this) { acc, b -> acc.markPrinted(b.seq) }

    private suspend fun PrintQueue.awaitIdle() = withTimeout(5000) {
        while (job.value != null) delay(10)
    }

    private fun CompletableDeferred<Unit>.open() = complete(Unit)

    @Test
    fun newBoxJoinsTheQueueWithoutCancellingTheLabelBeingPrinted() = runBlocking {
        val printer = GatedPrinter()
        var session: Session? = session(1)
        val q = PrintQueue(printer, this, { AppOptions.defaults() }, { session }, { f -> session = session?.let(f) })

        q.enqueueNew(listOf(1))
        val first = printer.arrived.receive() // 第 1 张正在打
        session = session!!.addManual(Jin.of(47.0))
        q.enqueueNew(listOf(2)) // 此时入队不能把正在打的第 1 张取消重来
        first.open()
        printer.arrived.receive().open()
        q.awaitIdle()

        assertEquals(2, printer.printed.size)
        assertTrue(printer.printed[0].contains("第1箱") && printer.printed[1].contains("第2箱"))
        assertTrue(session!!.boxes.all { it.printed })
    }

    @Test
    fun deletingABoxReprintsTheBoxesWhoseSeqShifted() = runBlocking {
        val printer = GatedPrinter()
        var session: Session? = session(3).allPrinted()
        val q = PrintQueue(printer, this, { AppOptions.defaults() }, { session }, { f -> session = session?.let(f) })

        session = session!!.delete(1) // 原第 2、3 箱变成第 1、2 箱，印着旧序号的标签作废
        assertEquals(listOf(1, 2), session!!.unprinted.map { it.seq })
        q.enqueueRenumbered()
        printer.arrived.receive().open()
        printer.arrived.receive().open()
        q.awaitIdle()

        assertEquals(2, printer.printed.size)
        assertTrue(printer.printed[0].contains("第1箱") && printer.printed[0].contains("SG20261116-1#1"))
        assertTrue(printer.printed[1].contains("第2箱") && printer.printed[1].contains("SG20261116-1#2"))
        assertTrue(session!!.boxes.all { it.printed })
    }

    @Test
    fun renumberedBoxesWaitForConfirmWhenTimingIsAfterConfirm() = runBlocking {
        val printer = GatedPrinter()
        var session: Session? = session(3).allPrinted().delete(1)
        val opts = AppOptions.from(mapOf("print.timing" to "after-confirm"))
        val q = PrintQueue(printer, this, { opts }, { session }, { f -> session = session?.let(f) })

        q.enqueueRenumbered()
        delay(100)
        assertTrue(printer.arrived.tryReceive().isFailure)
        assertEquals(2, session!!.unprinted.size)

        q.enqueueAfterConfirm()
        printer.arrived.receive().open()
        printer.arrived.receive().open()
        q.awaitIdle()
        assertEquals(2, printer.printed.size)
    }

    @Test
    fun aLabelFinishedForABoxThatMovedDoesNotMarkTheBoxNowAtThatSeq() = runBlocking {
        val printer = GatedPrinter()
        var session: Session? = session(2).markPrinted(1)
        val q = PrintQueue(printer, this, { AppOptions.defaults() }, { session }, { f -> session = session?.let(f) })

        q.reprintUnprinted()
        val inFlight = printer.arrived.receive() // 正打第 2 箱
        session = session!!.delete(1) // 第 2 箱前移成第 1 箱，正在打的是旧标签（印着第 2 箱）
        q.enqueueRenumbered()
        inFlight.open()
        assertEquals(false, session!!.box(1).printed) // 不能把现在的第 1 箱记成已打
        printer.arrived.receive().open()
        q.awaitIdle()

        assertEquals(2, printer.printed.size)
        assertTrue(printer.printed[0].contains("第2箱"))
        assertTrue(printer.printed[1].contains("第1箱"))
        assertTrue(session!!.boxes.all { it.printed })
    }

    @Test
    fun reprintingAnAlreadyPrintedBoxPrintsOneMoreLabelAndLeavesOthersMarked() = runBlocking {
        val printer = GatedPrinter()
        var session: Session? = session(3).allPrinted()
        val q = PrintQueue(printer, this, { AppOptions.defaults() }, { session }, { f -> session = session?.let(f) })

        q.reprintOne(2) // 第 2 箱标签贴坏了，再打一张
        assertEquals(listOf(2), session!!.unprinted.map { it.seq }) // 只有它回到队列，其余箱的标记不动
        printer.arrived.receive().open()
        q.awaitIdle()

        assertEquals(1, printer.printed.size) // 多收到一张
        assertTrue(printer.printed.single().contains("第2箱"))
        assertTrue(session!!.boxes.all { it.printed }) // 打完仍是已打印

        q.reprintOne(2) // 不限次数
        printer.arrived.receive().open()
        q.awaitIdle()
        assertEquals(2, printer.printed.size)
    }

    @Test
    fun blockerFollowsThePrinterNowNotTheLastFailure() = runBlocking {
        val printer = GatedPrinter()
        val session: Session? = session(1)
        val q = PrintQueue(printer, this, { AppOptions.defaults() }, { session }, { })

        printer.printerStatus.value = PrinterIssue.NO_PAPER
        assertEquals("缺纸", q.blocker())
        q.reprintUnprinted()
        delay(100)
        assertEquals("缺纸", q.lastError.value)

        printer.printerStatus.value = PrinterIssue.OK // 换好纸：旧的失败文字还在，但此刻不再被挡
        assertEquals("缺纸", q.lastError.value)
        assertNull(q.blocker())
        printer.linkStatus.value = LinkStatus.LOST
        assertEquals("打印机未连接", q.blocker())
    }

    @Test
    fun labelDateIsTheBatchDateNotToday() = runBlocking {
        val printer = GatedPrinter()
        // 批次号上的日期不是今天：补打跨天的单，标签上仍是收购当天
        var session: Session? = session(1).copy(batchNo = "SG20250102-3")
        val q = PrintQueue(printer, this, { AppOptions.from(mapOf("label.content" to "full")) }, { session }, { f -> session = session?.let(f) })

        q.enqueueNew(listOf(1))
        printer.arrived.receive().open()
        q.awaitIdle()
        assertTrue(printer.printed.single().contains("收购日期：2025.1.2"))
    }
}
