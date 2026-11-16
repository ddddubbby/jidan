package com.jidan.receiving.device.gainscha

import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrinterIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GainschaCodesTest {
    /** TSPL 手册 `<ESC>!?` 返回值表的每一行。 */
    @Test
    fun statusByteTableFromManual() {
        val expected = mapOf(
            0x00 to PrinterIssue.OK, // 正常待机
            0x01 to PrinterIssue.OPEN_COVER, // 开盖
            0x02 to PrinterIssue.NO_PAPER, // 卡纸：并入缺纸
            0x03 to PrinterIssue.OPEN_COVER, // 卡纸、开盖
            0x04 to PrinterIssue.NO_PAPER, // 缺纸
            0x05 to PrinterIssue.OPEN_COVER, // 缺纸、开盖：先让人关盖
            0x08 to PrinterIssue.NO_PAPER, // 无碳带：并入缺纸
            0x09 to PrinterIssue.OPEN_COVER,
            0x0A to PrinterIssue.NO_PAPER,
            0x0B to PrinterIssue.OPEN_COVER,
            0x0C to PrinterIssue.NO_PAPER,
            0x0D to PrinterIssue.OPEN_COVER,
            0x10 to PrinterIssue.OK, // 暂停打印：不是故障
            0x20 to PrinterIssue.OK, // 正在打印
            0x40 to PrinterIssue.OPEN_COVER, // 位表：外盖打开
            0x80 to PrinterIssue.OVERHEAT, // 位表：过热
        )
        for ((b, issue) in expected) {
            assertEquals(issue, GainschaCodes.issueOf(b), "状态字节 0x%02X".format(b))
        }
    }

    @Test
    fun statusQueryIsEscBangQuestion() {
        assertEquals(listOf(0x1B, 0x21, 0x3F), GainschaCodes.STATUS_QUERY.map { it.toInt() })
    }

    /** SDK API 文档 9.5 状态码表。 */
    @Test
    fun trackCodesMapToOutcome() {
        assertEquals(PrintOutcome.Success, GainschaCodes.outcomeOf(0x20))
        assertEquals(PrintOutcome.Success, GainschaCodes.outcomeOf(0x21)) // 打印中：已收下
        assertEquals(PrintOutcome.Failure("打印机丢单，请补打"), GainschaCodes.outcomeOf(0x22))
        assertEquals(PrintOutcome.Failure("缺纸"), GainschaCodes.outcomeOf(0x23))
        assertEquals(PrintOutcome.Failure("开盖"), GainschaCodes.outcomeOf(0x24))
        assertEquals(PrintOutcome.Failure("过热"), GainschaCodes.outcomeOf(0x25))
        assertEquals(PrintOutcome.Failure("卡切刀"), GainschaCodes.outcomeOf(0x26)) // aar 的 STATUS_CUTTER_JAM
        val unknown = GainschaCodes.outcomeOf(0x7F) as PrintOutcome.Failure
        assertTrue(unknown.reason.contains("0x7F"), unknown.reason)
    }

    @Test
    fun noReplyFollowsNamedConstant() {
        // 默认按成功算（见 NO_REPLY_IS_SUCCESS 的说明）；改常量时这条要跟着改
        assertTrue(GainschaCodes.NO_REPLY_IS_SUCCESS)
        assertEquals(PrintOutcome.Success, GainschaCodes.outcomeOf(null))
    }

    @Test
    fun trackFaultsSyncStatusTag() {
        assertEquals(PrinterIssue.NO_PAPER, GainschaCodes.issueOfTrack(0x23))
        assertEquals(PrinterIssue.OPEN_COVER, GainschaCodes.issueOfTrack(0x24))
        assertEquals(PrinterIssue.OVERHEAT, GainschaCodes.issueOfTrack(0x25))
        assertNull(GainschaCodes.issueOfTrack(0x20))
        assertNull(GainschaCodes.issueOfTrack(0x22))
        assertNull(GainschaCodes.issueOfTrack(null))
    }

    /** 失败原因的文字要与 PrintQueue.printerReady 一致，补打按钮与提示才不出现两种说法。 */
    @Test
    fun reasonsMatchPrintQueueWording() {
        assertEquals("缺纸", GainschaCodes.reasonOf(PrinterIssue.NO_PAPER))
        assertEquals("开盖", GainschaCodes.reasonOf(PrinterIssue.OPEN_COVER))
        assertEquals("过热", GainschaCodes.reasonOf(PrinterIssue.OVERHEAT))
    }
}
