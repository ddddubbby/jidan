package com.jidan.receiving.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.databinding.SheetRuleBinding
import com.jidan.receiving.databinding.ViewListRowBinding
import com.jidan.receiving.ui.create.lossText
import com.jidan.receiving.ui.create.packText
import com.jidan.receiving.ui.summary.formatNum
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import com.jidan.receiving.ui.weigh.RuleCandidate
import com.jidan.receiving.ui.weigh.RuleSheetState
import com.jidan.receiving.ui.widgets.NumText
import com.jidan.receiving.ui.widgets.TextGlue
import kotlinx.coroutines.launch

/** 定价规则弹层：调阅、改每斤尾差与每箱损耗（预览后保存到规则）、换成同商品规格的另一条（APP-09，附录 E R-1 行）。 */
class RuleSheet : JidanSheet() {
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
        b.lossMinus.setOnClickListener { vm.adjustDraftLoss(-0.5) }
        b.lossPlus.setOnClickListener { vm.adjustDraftLoss(0.5) }
        b.change.setOnClickListener { vm.toggleChangeRule() }

        vm.loadRuleSheet()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ruleSheet.collect { render(it) }
            }
        }
    }

    private fun render(state: RuleSheetState) {
        val b = binding ?: return
        val scope = "${state.productName} ${state.specName}".trim()
        b.scope.text = scope
        b.scopeValue.text = scope
        b.error.isVisible = state.error.isNotBlank()
        b.error.text = state.error
        b.loading.isVisible = state.loading
        b.boundBlock.isVisible = !state.loading && !state.unbound
        val showCandidates = !state.loading && (state.unbound || state.changing)
        b.candidateBlock.isVisible = showCandidates
        // 有规则时“适用”是表里的一行；选规则时它是列表上方的一句说明
        b.scope.isVisible = state.unbound

        if (!state.unbound) {
            bindHead(b.head, getString(R.string.rule_sheet_title))
            b.ruleName.text = state.boundRuleName.orEmpty()
            b.stdNet.text = state.standardNetJin?.let { TextGlue.withUnit(formatNum(it), "斤/箱") } ?: "—"
            b.basePrice.text = when {
                state.basePrice == null -> getString(R.string.summary_no_base)
                else -> TextGlue.withUnit(formatNum(state.basePrice), "元/箱")
            }
            b.coefValue.text = TextGlue.withUnit(formatNum(state.displayCoef), "元")
            b.lossValue.text = lossLabel(state.displayLoss)
            val preview = state.trialUnitPrice
            b.previewRow.isVisible = preview != null
            if (preview != null) {
                val before = if (state.dirty) state.boundUnitPrice?.let { formatNum(it) } else null
                b.previewPrice.text = previewText(before, formatNum(preview))
            }
            b.dirtyActions.isVisible = state.dirty
            b.boundActions.isVisible = !state.dirty
            b.save.isEnabled = !state.busy
            b.save.text = getString(if (state.busy) R.string.rule_sheet_saving else R.string.rule_sheet_save)
            b.change.isEnabled = !state.busy
            b.coefMinus.isEnabled = !state.busy
            b.coefPlus.isEnabled = !state.busy
            b.lossMinus.isEnabled = !state.busy
            b.lossPlus.isEnabled = !state.busy
        } else {
            bindHead(b.head, getString(R.string.rule_sheet_pick))
        }

        if (showCandidates) {
            b.candidateEmpty.isVisible = state.candidates.isEmpty()
            b.candidateList.removeAllViews()
            state.candidates.forEach { c ->
                b.candidateList.addView(candidateRow(c, state.busy, b.candidateList))
            }
        }
    }

    /** 本单收购价：改过尾差时“旧价（划掉）→ 新价”，数字 Inter 粗体 20，“元/箱”缩小变灰（原型 .preview）。 */
    private fun previewText(before: String?, after: String): CharSequence {
        val ctx = requireContext()
        val out = SpannableStringBuilder()
        if (before != null) {
            out.append(before)
            val end = out.length
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            out.setSpan(StrikethroughSpan(), 0, end, flags)
            out.setSpan(AbsoluteSizeSpan(17, true), 0, end, flags)
            out.setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.ink_2)), 0, end, flags)
            ResourcesCompat.getFont(ctx, R.font.inter_medium)?.let {
                out.setSpan(NumText.TypefaceSpanCompat(it), 0, end, flags)
            }
            out.append(" → ")
        }
        return out.append(NumText.row(ctx, after, "元/箱"))
    }

    /** 每箱损耗：0 写“不调整”，其余带正负号，如 +0.5 斤、−1 斤。 */
    private fun lossLabel(loss: Double): CharSequence =
        lossText(loss)?.let { TextGlue.withUnit(it.removeSuffix(" 斤"), "斤") } ?: getString(R.string.rule_sheet_no_loss)

    /** 候选规则一行（原型 .row-btn）：名称、打包内容与“每斤尾差”；右边是换上后的本单收购价。点了这张单就换成它。 */
    private fun candidateRow(c: RuleCandidate, busy: Boolean, parent: ViewGroup): View {
        val row = ViewListRowBinding.inflate(layoutInflater, parent, false)
        val name = c.rule.name
        val sub = buildString {
            append(packText(c.rule))
            append("\n")
            append(getString(R.string.rule_sheet_candidate_coef, formatNum(c.rule.coef)))
        }
        row.rowMain.text = SpannableStringBuilder(name).append("\n").append(sub).also {
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            val start = name.length + 1
            it.setSpan(AbsoluteSizeSpan(13, true), start, it.length, flags)
            it.setSpan(ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.ink_2)), start, it.length, flags)
        }
        c.unitPrice?.let {
            row.rowSide.isVisible = true
            row.rowSide.text = getString(R.string.rule_sheet_candidate_price, formatNum(it))
            row.rowSide.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
            row.rowSide.setTypeface(row.rowSide.typeface, Typeface.BOLD)
        }
        row.rowRoot.isEnabled = !busy
        row.rowRoot.setOnClickListener { vm.chooseRule(c.rule.id) }
        return row.root
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "rule"
    }
}
