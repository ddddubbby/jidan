package com.jidan.receiving.ui.widgets

import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrinterIssue
import com.jidan.receiving.ui.devices.LinkActionKind
import com.jidan.receiving.ui.devices.rowLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusToneTest {
    @Test
    fun scaleChips() {
        assertEquals(StatusChip("秤已连接", StatusTone.OK), scaleChip(LinkStatus.CONNECTED, false))
        assertEquals(StatusChip("秤电量低", StatusTone.WARN), scaleChip(LinkStatus.CONNECTED, true))
        assertEquals(StatusChip("秤连接中", StatusTone.BUSY), scaleChip(LinkStatus.CONNECTING, false))
        assertEquals(StatusChip("秤未连接", StatusTone.ERR), scaleChip(LinkStatus.DISCONNECTED, false))
        assertEquals(StatusChip("秤断开了", StatusTone.ERR), scaleChip(LinkStatus.CONNECTING, false, lost = true))
    }

    @Test
    fun printerChipsAreNeverRed() {
        assertEquals(StatusChip("打印机就绪", StatusTone.OK), printerChip(LinkStatus.CONNECTED, PrinterIssue.OK))
        assertEquals(StatusChip("打印机缺纸", StatusTone.WARN), printerChip(LinkStatus.CONNECTED, PrinterIssue.NO_PAPER))
        assertEquals(StatusChip("打印机未连接", StatusTone.WARN), printerChip(LinkStatus.DISCONNECTED, PrinterIssue.OK))
        assertEquals(StatusChip("打印机连接中", StatusTone.BUSY), printerChip(LinkStatus.CONNECTING, PrinterIssue.OK))
    }

    @Test
    fun connectedDeviceGetsChangeAsItsOneAction() {
        assertEquals("换一台", LinkActionKind.NONE.rowLabel(isScale = true))
        assertEquals("搜索秤", LinkActionKind.SEARCH.rowLabel(isScale = true))
    }

    @Test
    fun choiceColumnsFollowNameLength() {
        assertEquals(3, ChoiceGrid.cols(listOf("1号包装箱", "2号包装箱", "3号包装箱")))
        assertEquals(1, ChoiceGrid.cols(listOf("一个很长很长的包装箱名字")))
    }
}
