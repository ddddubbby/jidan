package com.jidan.receiving.ui.summary

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.core.Kg
import com.jidan.receiving.databinding.FragmentSummaryBinding
import com.jidan.receiving.platform.Feedback
import com.jidan.receiving.printing.reprintAction
import com.jidan.receiving.ui.sheets.DevicesSheet
import com.jidan.receiving.ui.sheets.LedgerSheet
import com.jidan.receiving.ui.sheets.RuleSheet
import com.jidan.receiving.ui.weigh.ReceivingNav
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import com.jidan.receiving.ui.weigh.SummaryUiState
import com.jidan.receiving.ui.widgets.NumText
import com.jidan.receiving.ui.widgets.bindReprint
import com.jidan.receiving.ui.widgets.fitAmount
import kotlinx.coroutines.launch

class SummaryFragment : Fragment() {
    private var binding: FragmentSummaryBinding? = null
    private val vm: ReceivingViewModel by activityViewModels {
        ReceivingViewModel.Factory(requireContext().appContainer())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentSummaryBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.back.setOnClickListener { vm.backToWeigh() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = vm.backToWeigh()
            },
        )
        b.warnBind.setOnClickListener { openRule() }
        b.factRule.setOnClickListener { openRule() }
        b.factLedger.setOnClickListener {
            LedgerSheet().show(parentFragmentManager, LedgerSheet.TAG)
        }
        b.primary.setOnClickListener {
            val s = vm.summary.value
            val q = s.quote
            val blockMissing = !s.allowWithoutPrice
            when {
                s.refreshing || s.loading || s.submitting -> Unit
                q == null -> vm.reloadQuote()
                blockMissing && q.noBasePrice -> vm.refreshBasePrice()
                blockMissing && q.noRule -> openRule()
                else -> vm.submitOrder()
            }
        }

        // 登录失效重登回汇总、或进程恢复后补一次计价
        if (vm.summary.value.quote == null) {
            vm.reloadQuote()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.summary.collect { render(it) }
                }
                launch {
                    vm.nav.collect { ev ->
                        when (ev) {
                            is ReceivingNav.BackToWeigh -> findNavController().navigateUp()
                            is ReceivingNav.ToDone -> findNavController().navigate(R.id.action_summary_to_done)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun openRule() {
        RuleSheet().show(parentFragmentManager, RuleSheet.TAG)
    }

    /** 定价规则一行：没绑定时“未绑定”前放一个琥珀圆点，不用黄底。 */
    private fun setUnbound(b: FragmentSummaryBinding, unbound: Boolean) {
        b.factRuleValue.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (unbound) R.drawable.ic_dot else 0, 0, 0, 0,
        )
    }

    /** “10 箱 · 460.5 kg”：kg 缩小变灰，与均重、称重页同一套（NumText）。 */
    private fun ledgerValue(boxCount: Int, totalKg: Double?): CharSequence {
        val head = getString(R.string.summary_ledger_count, boxCount)
        if (totalKg == null) return "$head · —"
        return SpannableStringBuilder(head).append(" · ")
            .append(NumText.row(requireContext(), Kg.of(totalKg).format(), "kg"))
    }

    private fun render(state: SummaryUiState) {
        val b = binding ?: return
        b.title.text = state.farmerName
        b.subtitle.text = getString(R.string.summary_subtitle, state.batchNo, state.warehouseName)
        b.factProduct.text = "${state.productName} ${state.specName}".trim()

        val q = state.quote
        val missing = q != null && (q.noBasePrice || q.noRule)
        val pendingAllowed = missing && state.allowWithoutPrice

        if (q == null) {
            b.amountRow.isVisible = false
            b.amountCalc.isVisible = false
            b.warnRow.isVisible = false
            setUnbound(b, true)
            b.factFormula.isVisible = false
            b.factLedgerValue.text = "—"
            b.factAvg.text = "—"
        } else {
            val ruleName = q.ruleName?.takeIf { it.isNotBlank() }
            b.factRuleValue.text = ruleName ?: getString(R.string.summary_rule_unbound)
            setUnbound(b, ruleName == null)
            val formula = formulaLine(q)
            b.factFormula.isVisible = formula.isNotBlank()
            b.factFormula.text = formula
            b.factLedgerValue.text = ledgerValue(q.boxCount, q.totalKg)
            b.factAvg.text = q.avgKg?.let { NumText.row(requireContext(), formatNum(it), "kg") } ?: "—"

            when {
                pendingAllowed -> {
                    b.amountRow.isVisible = true
                    b.amountCalc.isVisible = false
                    b.amountValue.text = getString(R.string.summary_pending_price)
                    b.amountValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                    b.amountUnit.isVisible = false
                    b.warnRow.isVisible = true
                    b.warnTag.text = when {
                        q.noBasePrice && state.m0Retried -> getString(R.string.summary_still_no_base)
                        q.noBasePrice -> getString(R.string.summary_no_base)
                        else -> getString(R.string.summary_no_rule)
                    }
                    b.warnBind.isVisible = q.noRule
                }
                q.noBasePrice -> {
                    b.amountRow.isVisible = false
                    b.amountCalc.isVisible = false
                    b.amountUnit.isVisible = true
                    b.warnRow.isVisible = true
                    b.warnTag.text = getString(
                        if (state.m0Retried) R.string.summary_still_no_base else R.string.summary_no_base,
                    )
                    b.warnBind.isVisible = false
                }
                q.noRule -> {
                    b.amountRow.isVisible = false
                    b.amountCalc.isVisible = false
                    b.amountUnit.isVisible = true
                    b.warnRow.isVisible = true
                    b.warnTag.text = getString(R.string.summary_no_rule)
                    b.warnBind.isVisible = true
                }
                else -> {
                    b.amountRow.isVisible = true
                    b.amountCalc.isVisible = true
                    b.amountUnit.isVisible = true
                    val money = formatMoney(q.amount)
                    b.amountValue.text = money
                    b.amountValue.fitAmount(money.length + 1, insetDp = 2 * 16f + 2 * 18f + 2f)
                    b.amountCalc.text = getString(
                        R.string.summary_calc,
                        formatNum(q.unitPrice),
                        q.boxCount,
                    )
                    b.warnRow.isVisible = false
                    b.warnBind.isVisible = false
                }
            }
        }

        val job = state.printJob
        b.printProgress.isVisible = job != null
        if (job != null) {
            b.printProgressText.text = getString(R.string.print_progress, job.done, job.total)
            b.printBar.max = job.total.coerceAtLeast(1)
            b.printBar.progress = job.done
        }
        b.reprint.bindReprint(
            action = if (job == null) reprintAction(state.printEnabled, state.unprinted, state.printBlock) else null,
            unprinted = state.unprinted,
            onPrint = { vm.reprintUnprinted() },
            onFixPrinter = { DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG) },
        )
        val hadError = b.error.isVisible && b.error.text?.isNotBlank() == true
        b.error.isVisible = state.error.isNotBlank()
        b.error.text = state.error
        if (state.submitError && state.error.isNotBlank() && !hadError) {
            Feedback.warn(requireContext())
        }

        val blockMissing = !state.allowWithoutPrice
        // 还缺一项（没有计价结果、缺基准价、缺规则）：主按钮写明缺什么并高亮，点了去补；齐了才是绿底的“提交单据”
        b.primary.isActivated = q == null || (blockMissing && (q.noBasePrice || q.noRule))
        when {
            state.submitting -> {
                b.primary.isEnabled = false
                b.primary.text = getString(R.string.summary_submitting)
            }
            state.refreshing -> {
                b.primary.isEnabled = false
                b.primary.text = getString(R.string.summary_refreshing)
            }
            state.loading && q == null -> {
                b.primary.isEnabled = false
                b.primary.text = getString(R.string.summary_loading)
            }
            q == null -> {
                // 没有计价结果不能提交：按钮写明缺的是什么，点了重新计价
                b.primary.isEnabled = true
                b.primary.text = getString(R.string.summary_requote)
            }
            blockMissing && q.noBasePrice -> {
                b.primary.isEnabled = true
                b.primary.text = getString(R.string.summary_refresh_base)
            }
            blockMissing && q.noRule -> {
                b.primary.isEnabled = true
                b.primary.text = getString(R.string.summary_bind_rule)
            }
            else -> {
                b.primary.isEnabled = true
                b.primary.text = getString(
                    if (state.submitError) R.string.summary_retry else R.string.summary_submit,
                )
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
