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
import com.jidan.receiving.databinding.SheetRuleBinding
import com.jidan.receiving.ui.summary.formatMoney
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import com.jidan.receiving.ui.weigh.RuleSheetState
import kotlinx.coroutines.launch

/** 定价规则弹层：调阅、改系数预览与保存、解绑、更换（APP-09）。 */
class RuleSheet : BottomSheetDialogFragment() {
    private var binding: SheetRuleBinding? = null
    private val vm: ReceivingViewModel by activityViewModels {
        ReceivingViewModel.Factory(requireContext().appContainer())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetRuleBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.coefMinus.setOnClickListener { vm.adjustDraftCoef(-0.1) }
        b.coefPlus.setOnClickListener { vm.adjustDraftCoef(0.1) }
        b.revert.setOnClickListener { vm.revertDraftCoef() }
        b.save.setOnClickListener { vm.saveDraftCoef() }
        b.change.setOnClickListener { vm.toggleChangeRule() }
        b.unbind.setOnClickListener { vm.unbindRule() }

        vm.loadRuleSheet()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ruleSheet.collect { render(it) }
            }
        }
    }

    private fun render(state: RuleSheetState) {
        val b = binding ?: return
        b.scope.text = "${state.productName} ${state.specName}".trim()
        b.error.isVisible = state.error.isNotBlank()
        b.error.text = state.error
        b.loading.isVisible = state.loading
        b.boundBlock.isVisible = !state.loading && !state.unbound
        val showCandidates = !state.loading && (state.unbound || state.changing)
        b.candidateBlock.isVisible = showCandidates

        if (!state.unbound) {
            b.title.text = getString(R.string.rule_sheet_title)
            b.ruleName.text = state.boundRuleName.orEmpty()
            b.stdNet.text = state.standardNetKg?.let { "${formatMoney(it)} kg/箱" } ?: "—"
            b.basePrice.text = when {
                state.basePrice == null -> getString(R.string.summary_no_base)
                else -> "${formatMoney(state.basePrice)} 元/箱"
            }
            b.coefValue.text = "${formatMoney(state.displayCoef)} 元"
            val preview = state.trialUnitPrice
            b.previewRow.isVisible = preview != null
            if (preview != null) {
                b.previewPrice.text = if (state.dirty && state.boundUnitPrice != null) {
                    getString(
                        R.string.rule_sheet_preview_dirty,
                        formatMoney(state.boundUnitPrice),
                        formatMoney(preview),
                    )
                } else {
                    getString(R.string.rule_sheet_preview_value, formatMoney(preview))
                }
            }
            b.dirtyActions.isVisible = state.dirty
            b.boundActions.isVisible = !state.dirty
            b.save.isEnabled = !state.busy
            b.save.text = when {
                state.busy -> getString(R.string.rule_sheet_saving)
                state.bindCount > 1 -> getString(R.string.rule_sheet_save_shared, state.bindCount)
                else -> getString(R.string.rule_sheet_save)
            }
            b.change.isEnabled = !state.busy
            b.unbind.isEnabled = !state.busy
            b.coefMinus.isEnabled = !state.busy
            b.coefPlus.isEnabled = !state.busy
        } else {
            b.title.text = getString(R.string.rule_sheet_pick)
        }

        if (showCandidates) {
            b.candidateEmpty.isVisible = state.candidates.isEmpty()
            b.candidateList.removeAllViews()
            state.candidates.forEach { c ->
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
                btn.isEnabled = !state.busy
                btn.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                val coef = formatMoney(c.rule.coef)
                val price = c.unitPrice?.let { getString(R.string.rule_sheet_candidate_price, formatMoney(it)) }.orEmpty()
                btn.text = buildString {
                    append(c.rule.name)
                    append("\n")
                    append(getString(R.string.rule_sheet_candidate_coef, coef))
                    if (price.isNotBlank()) {
                        append("  ·  ")
                        append(price)
                    }
                    if (c.rule.bindCount > 1) {
                        append("\n")
                        append(getString(R.string.rule_sheet_shared_n, c.rule.bindCount))
                    }
                }
                btn.setOnClickListener { vm.bindRule(c.rule.id) }
                b.candidateList.addView(btn)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "rule"
    }
}
