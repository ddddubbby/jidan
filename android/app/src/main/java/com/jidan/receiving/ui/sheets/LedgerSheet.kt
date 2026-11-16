package com.jidan.receiving.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.core.SameWeight
import com.jidan.receiving.databinding.SheetLedgerBinding
import com.jidan.receiving.printing.reprintAction
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import com.jidan.receiving.ui.weigh.WeighUiState
import com.jidan.receiving.ui.widgets.bindReprint
import kotlinx.coroutines.launch

class LedgerSheet : BottomSheetDialogFragment() {
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
        b.title.text = getString(R.string.ledger_title_n, state.count)
        // 确认计量后与提交后流水只读：不能手工加、重称、手录、删除，只能打印（APP-08）
        b.manualAdd.isVisible = state.showManualAdd
        val groups = SameWeight.groups(state.boxes.map { it.net })
        val groupsView = ledgerGroupsView(groups, showAllGroups)
        b.groups.text = groupsView.shown.joinToString("  ") { it.label() }
        b.groupsToggle.isVisible = groupsView.foldable
        b.groupsToggle.text = if (showAllGroups) {
            getString(R.string.ledger_groups_less)
        } else {
            getString(R.string.ledger_groups_more, groupsView.hidden)
        }
        b.rows.removeAllViews()
        state.boxes.forEach { box ->
            val btn = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
            btn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = 6 }
            btn.isAllCaps = false
            btn.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            btn.text = buildString {
                append(getString(R.string.weigh_box, box.seq))
                append("  ")
                append(box.net.format())
                append(" kg")
                if (box.manual) append(" · ").append(getString(R.string.weigh_manual_tag))
                if (box.manual && box.raw != null) {
                    append("\n原 ").append(box.raw!!.format()).append(" kg")
                }
            }
            if (state.locked) {
                btn.isClickable = false
                btn.isFocusable = false
            } else {
                btn.setOnClickListener {
                    dismiss()
                    BoxSheet.newFor(box.seq).show(parentFragmentManager, BoxSheet.TAG)
                }
            }
            b.rows.addView(btn)

            // 每箱都能打，不限次数（v7 第 9 章第 8 条）：没打出来的写“补打”，已打出的写“再打”（标签贴坏、污损）。
            // 确认计量后、完成页同样可用。
            val print = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
            print.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            print.isAllCaps = false
            print.bindReprint(
                action = reprintAction(state.printEnabled, 1, state.printBlock),
                unprinted = 1,
                onPrint = { vm.reprintOne(box.seq) },
                onFixPrinter = {
                    DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG)
                },
                again = box.printed,
            )
            if (print.isVisible) b.rows.addView(print)
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
