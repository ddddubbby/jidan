package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Box
import com.jidan.receiving.core.WeighPhase

/** 称重框的底色（原型 `.scale[data-state]`）：灰＝平常，绿＝刚记完，黄＝待拿下或要选几箱。 */
enum class CardTone { IDLE, OK, WARN }

fun WeighPhase.cardTone(): CardTone = when (this) {
    WeighPhase.RECORDED -> CardTone.OK
    WeighPhase.HOLD, WeighPhase.ASK -> CardTone.WARN
    else -> CardTone.IDLE
}

/** 大数字的颜色（原型 `.scale[data-state] #weight`）：墨色、空秤或读数未稳定时的浅灰、未设皮重时淡出（30%）。 */
enum class NumberTone { INK, PALE, FADED }

fun WeighPhase.numberTone(): NumberTone = when (this) {
    WeighPhase.LOCKED -> NumberTone.FADED
    WeighPhase.UNSTABLE, WeighPhase.EMPTY -> NumberTone.PALE
    else -> NumberTone.INK
}

/** 刚记下的那次称重占哪几箱（最后一组）；一箱写“第 3 箱”，k 箱写“第 3–5 箱”（原型 seqRange）。没有箱返回 null。 */
fun recordedRange(boxes: List<Box>): IntRange? {
    val last = boxes.lastOrNull() ?: return null
    val grp = boxes.filter { it.group == last.group }
    return grp.first().seq..grp.last().seq
}

/** 重称成功后提示是“第 N 箱已重称”，称重框右上角相应写“已重称”而不是“已记录”；取不出箱号返回 null。 */
fun reweighedSeq(eventText: String): Int? =
    Regex("""第 ?(\d+)[\s ]*箱已重称""").find(eventText)?.groupValues?.get(1)?.toIntOrNull()

fun seqRangeText(r: IntRange): String =
    if (r.first == r.last) "第 ${r.first} 箱" else "第 ${r.first}–${r.last} 箱"

/**
 * 称重框右上角的状态文字里“已记录”的那一种：`✓ 第 1 箱已记录`、`✓ 第 3–5 箱已记录`、`✓ 第 2 箱已重称`。
 * 重称的箱不一定是最后一组，所以以提示里的箱号为准。
 */
fun recordedLabel(boxes: List<Box>, eventText: String): String {
    val re = reweighedSeq(eventText)
    if (re != null) return "✓ 第 $re 箱已重称"
    val r = recordedRange(boxes) ?: return "✓ 已记录"
    return "✓ ${seqRangeText(r)}已记录"
}

/**
 * 这条提示是否还要单独显示一行：正常记录的反馈就是称重框变绿加提示音，不再另写一句（原型 record()）；
 * 点了“重称”时称重框里已有一行“重称 第 N 箱 · 原 …”，也不重复。其余（重称完成、删除、改箱数）照常显示。
 */
fun showEventLine(text: String): Boolean =
    text.isNotBlank() && !text.startsWith("已记录") && !text.startsWith("重称第")

/** 最近几箱里每行右边的打印标记：已打 ✓、没打出来写“未打印”、不到打的时候（完成后一次打印、还没确认）什么也不写。 */
enum class PrintMark { NONE, DONE, PENDING }

fun printMark(printEnabled: Boolean, warnable: Boolean, box: Box): PrintMark = when {
    !printEnabled -> PrintMark.NONE
    box.printed -> PrintMark.DONE
    warnable -> PrintMark.PENDING
    else -> PrintMark.NONE
}
