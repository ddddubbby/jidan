package com.jidan.receiving.ui.summary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
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
import com.jidan.receiving.ui.sheets.LedgerSheet
import com.jidan.receiving.ui.sheets.RuleSheet
import com.jidan.receiving.ui.weigh.ReceivingNav
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import com.jidan.receiving.ui.weigh.SummaryUiState
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
            when {
                s.refreshing || s.loading -> Unit
                q?.noBasePrice == true -> vm.refreshBasePrice()
                q?.noRule == true -> openRule()
                // 提交属 P7-4；齐了时按钮文案已是「提交单据」，点了暂不动作
                else -> Unit
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.summary.collect { render(it) }
                }
                launch {
                    vm.nav.collect { ev ->
                        if (ev is ReceivingNav.BackToWeigh) {
                            findNavController().navigateUp()
                        }
                    }
                }
            }
        }
    }

    private fun openRule() {
        RuleSheet().show(parentFragmentManager, RuleSheet.TAG)
    }

    private fun render(state: SummaryUiState) {
        val b = binding ?: return
        b.title.text = state.farmerName
        b.subtitle.text = getString(R.string.summary_subtitle, state.batchNo, state.warehouseName)
        b.factProduct.text = "${state.productName} ${state.specName}".trim()

        val q = state.quote
        val missing = q != null && (q.noBasePrice || q.noRule)
        b.moneyCard.background = ContextCompat.getDrawable(
            requireContext(),
            if (missing) R.drawable.bg_money_card_warn else R.drawable.bg_money_card,
        )

        if (q == null) {
            b.amountRow.isVisible = false
            b.amountCalc.isVisible = false
            b.warnRow.isVisible = false
            b.factRuleValue.text = getString(R.string.summary_rule_unbound)
            b.factFormula.isVisible = false
            b.factLedgerValue.text = "—"
            b.factAvg.text = "—"
        } else {
            b.warnRow.isVisible = missing
            if (q.noBasePrice) {
                b.amountRow.isVisible = false
                b.amountCalc.isVisible = false
                b.warnTag.text = getString(
                    if (state.m0Retried) R.string.summary_still_no_base else R.string.summary_no_base,
                )
                b.warnBind.isVisible = false
            } else if (q.noRule) {
                b.amountRow.isVisible = false
                b.amountCalc.isVisible = false
                b.warnTag.text = getString(R.string.summary_no_rule)
                b.warnBind.isVisible = true
            } else {
                b.amountRow.isVisible = true
                b.amountCalc.isVisible = true
                b.amountValue.text = formatMoney(q.amount)
                b.amountCalc.text = getString(
                    R.string.summary_calc,
                    formatMoney(q.unitPrice),
                    q.boxCount,
                )
                b.warnBind.isVisible = false
            }

            b.factRuleValue.text = q.ruleName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.summary_rule_unbound)
            val formula = formulaLine(q)
            b.factFormula.isVisible = formula.isNotBlank()
            b.factFormula.text = formula

            val total = q.totalKg?.let { Kg.of(it).format() } ?: "—"
            b.factLedgerValue.text = getString(R.string.summary_ledger_value, q.boxCount, total)
            b.factAvg.text = q.avgKg?.let { "${formatMoney(it)} kg" } ?: "—"
        }

        b.unprinted.isVisible = state.printEnabled && state.unprinted > 0
        if (b.unprinted.isVisible) {
            b.unprinted.text = getString(R.string.print_failed_n, state.unprinted, state.printError.ifBlank { "未打印" })
        }
        b.error.isVisible = state.error.isNotBlank()
        b.error.text = state.error

        when {
            state.refreshing -> {
                b.primary.isEnabled = false
                b.primary.text = getString(R.string.summary_refreshing)
            }
            state.loading && q == null -> {
                b.primary.isEnabled = false
                b.primary.text = getString(R.string.summary_loading)
            }
            q?.noBasePrice == true -> {
                b.primary.isEnabled = true
                b.primary.text = getString(R.string.summary_refresh_base)
            }
            q?.noRule == true -> {
                b.primary.isEnabled = true
                b.primary.text = getString(R.string.summary_bind_rule)
            }
            else -> {
                // 提交属 P7-4；齐了时显示提交文案但不做提交
                b.primary.isEnabled = false
                b.primary.text = getString(R.string.summary_submit)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
