package com.jidan.receiving.ui.sheets

import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jidan.receiving.R
import com.jidan.receiving.databinding.ViewSheetHeadBinding

/**
 * 称重页打开的底部弹层的共同底座（原型 .sheet）：白底、顶上 20 圆角、遮罩 45%，一打开就展开到内容高度，
 * 最高占屏幕 82%，更高的内容在弹层内部滚动，不撑破（v7 4.4-6）。把手与标题栏用 `view_sheet_head`。
 */
abstract class JidanSheet : BottomSheetDialogFragment() {
    override fun getTheme(): Int = R.style.ThemeOverlay_Jidan_BottomSheetDialog

    override fun onStart() {
        super.onStart()
        val behavior = (dialog as? BottomSheetDialog)?.behavior ?: return
        behavior.maxHeight = (resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /** 标题栏：标题、关闭。[closable] 为 false 时不放“关闭”（确认类弹层只有两个答案）。 */
    protected fun bindHead(head: ViewSheetHeadBinding, title: CharSequence, closable: Boolean = true) {
        head.sheetTitle.text = title
        head.sheetClose.visibility = if (closable) View.VISIBLE else View.GONE
        if (closable) head.sheetClose.setOnClickListener { dismiss() }
    }

    companion object {
        const val MAX_HEIGHT_RATIO = 0.82f
    }
}
