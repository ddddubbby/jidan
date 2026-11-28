package com.jidan.receiving.core

/**
 * 一条流水（一箱）。一次称重放 k 箱时，k 条流水共用一个 [group]，删除、改箱数都按组整体处理。
 * 手录、原读数、打印标记也先放在这里；`Session`（P3-4）直接用它，不另造一份。
 *
 * [gross] 是该次称重秤上的总重（含 k 箱皮重），[unit] 是每箱皮重；手工加的箱没有这两项。
 */
data class Box(
    val seq: Int,
    val group: Int,
    val net: Jin,
    val tare: Jin,
    val tareName: String,
    val gross: Jin? = null,
    val unit: Jin? = null,
    val manual: Boolean = false,
    val raw: Jin? = null,
    val printed: Boolean = false,
)

/** 一次称重均摊成 k 条流水，以及改箱数、整组删除、序号顺延（v7 `APP-05`，原型 `makeBoxes`、`renumber`）。 */
object SplitBoxes {

    /**
     * 把 [net]（k 箱净重合计）与 [tare]（k 箱皮重合计）均摊成 [k] 条流水，序号先填 0，加进列表时由 [append] 编号。
     * 每箱按 0.01斤 四舍五入，零头记入最后一箱。
     */
    fun split(
        net: Jin,
        k: Int,
        tare: Jin,
        tareName: String,
        group: Int,
        gross: Jin? = null,
        unit: Jin? = null,
        manual: Boolean = false,
    ): List<Box> {
        val perNet = halfUp(net.cents, k)
        val perTare = halfUp(tare.cents, k)
        return List(k) { i ->
            val last = i == k - 1
            Box(
                seq = 0, group = group,
                net = Jin(if (last) net.cents - perNet * (k - 1) else perNet),
                tare = Jin(if (last) tare.cents - perTare * (k - 1) else perTare),
                tareName = tareName, gross = gross, unit = unit, manual = manual,
            )
        }
    }

    /** 加到末尾并重新编号。 */
    fun append(boxes: List<Box>, added: List<Box>): List<Box> = renumber(boxes + added)

    /**
     * 序号从 1 起连续。序号变了的箱，已经贴出去的标签上印的箱序号与二维码（`批次号#序号`）就不对了，
     * 所以打印标记一并清掉，等重打；序号没变的箱不动。
     */
    fun renumber(boxes: List<Box>): List<Box> = boxes.mapIndexed { i, b ->
        if (b.seq == i + 1) b else b.copy(seq = i + 1, printed = false)
    }

    /** 删除第 [seq] 箱所在的整组，后面的序号顺延。 */
    fun deleteGroup(boxes: List<Box>, seq: Int): List<Box> {
        val group = boxes.first { it.seq == seq }.group
        return renumber(boxes.filter { it.group != group })
    }

    /**
     * 识别错了：第 [seq] 箱所在的那次称重改按 [k] 箱重新均摊，其余箱的位置不变，序号顺延。
     * 用该次称重的总重与每箱皮重重新算，所以组里之前手录的值会被秤的读数取代，新箱的打印标记清零。
     * 只用于秤称出来的组（有 [Box.gross] 与 [Box.unit]）；手工加的箱恒为 1 箱，不会走到这里。
     */
    fun regroup(boxes: List<Box>, seq: Int, k: Int): List<Box> {
        val group = boxes.first { it.seq == seq }.group
        val old = boxes.filter { it.group == group }
        if (k == old.size) return boxes
        val first = old.first()
        val gross = first.gross!!
        val tare = Jin(first.unit!!.cents * k)
        val fresh = split(
            net = gross - tare, k = k, tare = tare, tareName = first.tareName,
            group = group, gross = gross, unit = first.unit,
        )
        val at = boxes.indexOf(first)
        return renumber(boxes.subList(0, at) + fresh + boxes.subList(at + old.size, boxes.size))
    }

    /** 整数四舍五入，.5 进 1（同原型的 `Math.round`）。 */
    private fun halfUp(n: Int, k: Int) = Math.floorDiv(2 * n + k, 2 * k)
}
