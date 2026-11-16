package com.jidan.receiving.ui.sheets

import com.jidan.receiving.core.WeightGroup

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
