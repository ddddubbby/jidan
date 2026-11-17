package com.jidan.receiving.ui.sheets

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.core.Box
import com.jidan.receiving.core.SameWeight
import com.jidan.receiving.databinding.SheetLedgerBinding
import com.jidan.receiving.databinding.ViewLedgerRowBinding
import com.jidan.receiving.printing.ReprintAction
import com.jidan.receiving.printing.reprintAction
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import com.jidan.receiving.ui.weigh.WeighUiState
import com.jidan.receiving.ui.widgets.NumText
import com.jidan.receiving.ui.widgets.TextGlue
import kotlinx.coroutines.launch

class LedgerSheet : JidanSheet() {
    private var binding: SheetLedgerBinding? = null
    private var lastState: WeighUiState? = null
    private var showAllGroups = false
    private val vm: ReceivingViewModel by activityViewModels {
        ReceivingViewModel.Factory(requireContext().appContainer())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetLedgerBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        bindHead(b.head, getString(R.string.ledger_title))
        b.manualAdd.setOnClickListener {
            dismiss()
            BoxSheet.newAdd().show(parentFragmentManager, BoxSheet.TAG)
        }
        b.groupsToggle.setOnClickListener {
            showAllGroups = !showAllGroups
            lastState?.let { render(b, it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { state ->
                    lastState = state
                    render(b, state)
                }
            }
        }
    }

    private fun render(b: SheetLedgerBinding, state: WeighUiState) {
        b.head.sheetTitle.text = getString(R.string.ledger_title_n, state.count)
        // 确认计量后与提交后流水只读：不能手工加、重称、手录、删除，只能打印（APP-08）
        b.manualAdd.isVisible = state.showManualAdd

        val groups = SameWeight.groups(state.boxes.map { it.net })
        val groupsView = ledgerGroupsView(groups, showAllGroups)
        b.groups.removeAllViews()
        groupsView.shown.forEach { g ->
            val tag = layoutInflater.inflate(R.layout.view_group_tag, b.groups, false) as android.widget.TextView
            tag.text = g.displayLabel()
            b.groups.addView(tag)
        }
        b.groupsToggle.isVisible = groupsView.foldable
        b.groupsToggle.text = if (showAllGroups) {
            getString(R.string.ledger_groups_less)
        } else {
            getString(R.string.ledger_groups_more, groupsView.hidden)
        }

        // 每箱皮重都一样时写在上面一行；不一样的在各箱净重下面写各自的皮重
        val sameTare = state.boxes.isNotEmpty() && state.boxes.all { it.tare == state.boxes[0].tare }
        b.tareSame.isVisible = sameTare
        if (sameTare) {
            val first = state.boxes[0]
            b.tareSame.text = buildString {
                append("每箱皮重 ").append(TextGlue.withUnit(first.tare.format(), "kg"))
                if (first.tareName.isNotBlank()) append("（").append(first.tareName).append("）")
            }
        }

        // 不用打印时（print.enabled = off）整列标签都不出现
        b.colLabel.isVisible = state.printEnabled
        b.rows.removeAllViews()
        state.boxes.forEach { box -> b.rows.addView(row(b.rows, box, state, sameTare)) }
    }

    private fun row(parent: ViewGroup, box: Box, state: WeighUiState, sameTare: Boolean): View {
        val r = ViewLedgerRowBinding.inflate(layoutInflater, parent, false)
        val ctx = requireContext()
        r.ledgerSeq.text = getString(R.string.ledger_row_seq, box.seq)
        r.ledgerNet.text = NumText.row(ctx, box.net.format(), "kg")
        r.ledgerManual.isVisible = box.manual
        val sub = buildList {
            if (box.manual && box.raw != null) add("原 ${TextGlue.withUnit(box.raw!!.format(), "kg")}")
            if (!sameTare) add("皮重 ${TextGlue.withUnit(box.tare.format(), "kg")}")
        }
        r.ledgerSub.isVisible = sub.isNotEmpty()
        r.ledgerSub.text = sub.joinToString("\n")
        if (state.locked) {
            r.ledgerRow.isClickable = false
            r.ledgerRow.isFocusable = false
            r.ledgerRow.background = null
        } else {
            r.ledgerRow.contentDescription = getString(R.string.ledger_row_desc, box.seq)
            r.ledgerRow.setOnClickListener {
                dismiss()
                BoxSheet.newFor(box.seq).show(parentFragmentManager, BoxSheet.TAG)
            }
        }
        bindLabelCell(r, box, state)
        return r.root
    }

    /**
     * 标签这一格（原型 .log 最后一列）。每箱都能打，不限次数（v7 第 9 章第 8 条）：
     * 已打出的 ✓ 加“再打”（标签贴坏、污损）；没打出来的“补打”；完成后一次打印而还没确认的是“待打印”，不到打的时候。
     * 打印机此刻有问题时按钮不变，下面写原因，点了去设备弹层处理。确认计量后、完成页同样可用。
     */
    private fun bindLabelCell(r: ViewLedgerRowBinding, box: Box, state: WeighUiState) {
        val action = reprintAction(state.printEnabled, 1, state.printBlock)
        r.ledgerLabel.isVisible = state.printEnabled
        if (!state.printEnabled) return
        val warnable = state.printPerBox || state.locked
        r.ledgerState.isVisible = false
        r.ledgerPrint.isVisible = false
        r.ledgerReason.isVisible = false
        if (!box.printed && !warnable) {
            r.ledgerState.isVisible = true
            r.ledgerState.text = getString(R.string.ledger_label_pending)
            return
        }
        if (box.printed) {
            r.ledgerState.isVisible = true
            r.ledgerState.text = "✓"
            r.ledgerState.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_2))
        }
        r.ledgerPrint.isVisible = action != null
        r.ledgerPrint.text = getString(if (box.printed) R.string.print_again else R.string.ledger_reprint)
        // 没打出来的按黄底黄边（原型 .log .unprinted），已打出的是安静的文字按钮
        if (!box.printed) {
            val ctx = requireContext()
            r.ledgerPrint.setTextColor(ContextCompat.getColor(ctx, R.color.warn_ink))
            r.ledgerPrint.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.warn_bg))
            r.ledgerPrint.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.warn_ink))
            r.ledgerPrint.strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_control)
        }
        when (action) {
            null -> Unit
            ReprintAction.Print -> r.ledgerPrint.setOnClickListener { vm.reprintOne(box.seq) }
            is ReprintAction.FixPrinter -> {
                r.ledgerReason.isVisible = true
                r.ledgerReason.text = TextGlue.noBreak(action.reason)
                r.ledgerPrint.setOnClickListener {
                    DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG)
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        lastState = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ledger"
    }
}
