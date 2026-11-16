package com.jidan.receiving.device.gainscha

import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.device.PrinterIssue

/**
 * 佳博打印机的结果码、状态码 → APP 契约里的 [PrintOutcome]、[PrinterIssue]。
 * 依据：TSPL 手册“询问打印机状态指令 `<ESC>!?`”；佳博 SDK2 API 文档 9.5“状态码说明”，加上 aar 里 `PrintStatus.STATUS_CUTTER_JAM`（0x26，文档没列，官方示例有处理）。
 * 全部是查表，未经实物验证；真机上哪些位会置起属 H-2。
 */
object GainschaCodes {
    /** `<ESC>!?`：1B 21 3F，即使打印机出错也立即回一个字节，00 为正常待机（TSPL 手册）。 */
    val STATUS_QUERY = byteArrayOf(0x1B, 0x21, 0x3F)

    /**
     * 一票一控没有答复时按成功算。原因：这台机型是否支持一票一控没人验证过；
     * 若按失败算，不支持时每一张都报“失败”，标签其实已打出。发送前仍会先问一次 `<ESC>!?`，
     * 缺纸、开盖这类打印前就有的问题照样能拦住。代价是“已送出”不等于“已打出”，H-2 实测时留意。
     */
    const val NO_REPLY_IS_SUCCESS = true

    const val REASON_NO_PAPER = "缺纸"
    const val REASON_OPEN_COVER = "开盖"
    const val REASON_OVERHEAT = "过热"
    const val REASON_LOST = "打印机丢单，请补打"
    const val REASON_CUTTER_JAM = "卡切刀"

    /**
     * `<ESC>!?` 的回复字节，按位：0 开盖、1 卡纸、2 缺纸、3 无碳带、4 暂停、5 正在打印、6 外盖打开、7 过热。
     * [PrinterIssue] 只有四种：卡纸、无碳带都要人开盖处理耗材，并入 [PrinterIssue.NO_PAPER]；
     * 暂停、正在打印不是故障，算 OK。手册另有“0x80 其他错误”的说法，与第 7 位“过热”同一个值，这里按位表取过热。
     */
    fun issueOf(statusByte: Int): PrinterIssue {
        val b = statusByte and 0xFF
        return when {
            b and 0x01 != 0 || b and 0x40 != 0 -> PrinterIssue.OPEN_COVER
            b and (0x02 or 0x04 or 0x08) != 0 -> PrinterIssue.NO_PAPER
            b and 0x80 != 0 -> PrinterIssue.OVERHEAT
            else -> PrinterIssue.OK
        }
    }

    fun reasonOf(issue: PrinterIssue): String = when (issue) {
        PrinterIssue.OK -> ""
        PrinterIssue.NO_PAPER -> REASON_NO_PAPER
        PrinterIssue.OPEN_COVER -> REASON_OPEN_COVER
        PrinterIssue.OVERHEAT -> REASON_OVERHEAT
    }

    /** SDK `PrintStatus` 的状态码（API 文档 9.5）。 */
    const val TRACK_SUCCESS = 0x20
    const val TRACK_PRINTING = 0x21
    const val TRACK_LOST_ORDER = 0x22
    const val TRACK_PAPER_OUT = 0x23
    const val TRACK_COVER_OPEN = 0x24
    const val TRACK_OVERHEAT = 0x25
    const val TRACK_CUTTER_JAM = 0x26

    /**
     * 一票一控状态码 → 结果。0x21“打印中”说明打印机已收下并在出纸，算成功（再查一次的办法这里不做）。
     * 入参 null 是“没有答复”，由 [NO_REPLY_IS_SUCCESS] 决定。
     */
    fun outcomeOf(trackCode: Int?): PrintOutcome = when (trackCode?.and(0xFF)) {
        null -> if (NO_REPLY_IS_SUCCESS) PrintOutcome.Success else PrintOutcome.Failure("打印机没有答复")
        TRACK_SUCCESS, TRACK_PRINTING -> PrintOutcome.Success
        TRACK_LOST_ORDER -> PrintOutcome.Failure(REASON_LOST)
        TRACK_PAPER_OUT -> PrintOutcome.Failure(REASON_NO_PAPER)
        TRACK_COVER_OPEN -> PrintOutcome.Failure(REASON_OPEN_COVER)
        TRACK_OVERHEAT -> PrintOutcome.Failure(REASON_OVERHEAT)
        TRACK_CUTTER_JAM -> PrintOutcome.Failure(REASON_CUTTER_JAM) // PrinterIssue 没有对应项，不改状态标签
        else -> PrintOutcome.Failure("打印机返回未知状态 0x%02X".format(trackCode and 0xFF))
    }

    /** 一票一控状态码里带出的故障，用来同步打印机状态标签；没有故障返回 null。 */
    fun issueOfTrack(trackCode: Int?): PrinterIssue? = when (trackCode?.and(0xFF)) {
        TRACK_PAPER_OUT -> PrinterIssue.NO_PAPER
        TRACK_COVER_OPEN -> PrinterIssue.OPEN_COVER
        TRACK_OVERHEAT -> PrinterIssue.OVERHEAT
        else -> null
    }
}
