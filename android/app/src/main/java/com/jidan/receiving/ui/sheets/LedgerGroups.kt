package com.jidan.receiving.ui.sheets

import com.jidan.receiving.core.WeightGroup
import com.jidan.receiving.ui.widgets.TextGlue

/** 流水弹层的同重归类：种类超过 [FOLD_OVER] 种时先只列箱数最多的 [FOLD_HEAD] 种，可展开看全部（APP-06，原型 sheetLog）。 */
data class LedgerGroupsView(
    val shown: List<WeightGroup>,
    /** 折叠时没列出来的种类数，用于按钮文字；不需要折叠时为 0。 */
    val hidden: Int,
    /** 是否需要“展开/收起”按钮。 */
    val foldable: Boolean,
)

const val FOLD_OVER = 8
const val FOLD_HEAD = 6

/** [groups] 已按箱数多的在前排好（[com.jidan.receiving.core.SameWeight.groups]）。 */
fun ledgerGroupsView(groups: List<WeightGroup>, expanded: Boolean): LedgerGroupsView {
    val foldable = groups.size > FOLD_OVER
    val shown = if (foldable && !expanded) groups.take(FOLD_HEAD) else groups
    return LedgerGroupsView(shown, hidden = if (foldable) groups.size - FOLD_HEAD else 0, foldable = foldable)
}

/**
 * 流水弹层里一个归类的显示文字：“45.6斤-4箱”整个不许从中间断开（否则会把“箱”单独挤到下一行）。
 * 去掉零宽连接符后与 [WeightGroup.label] 一致；回传备注仍用 [WeightGroup.label] 原文，不带这些字符。
 */
fun WeightGroup.displayLabel(): String = TextGlue.noBreak(label())
