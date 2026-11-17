package com.jidan.receiving.ui.done

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.jidan.receiving.databinding.FragmentDoneBinding
import com.jidan.receiving.platform.Feedback
import com.jidan.receiving.printing.reprintAction
import com.jidan.receiving.ui.sheets.DevicesSheet
import com.jidan.receiving.ui.sheets.LedgerSheet
import com.jidan.receiving.ui.summary.formatMoney
import com.jidan.receiving.ui.weigh.DoneUiState
import com.jidan.receiving.ui.weigh.ReceivingNav
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import com.jidan.receiving.ui.widgets.bindReprint
import kotlinx.coroutines.launch

class DoneFragment : Fragment() {
    private var binding: FragmentDoneBinding? = null
    private val vm: ReceivingViewModel by activityViewModels {
        ReceivingViewModel.Factory(requireContext().appContainer())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentDoneBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = vm.again()
            },
        )
        b.again.setOnClickListener { vm.again() }
        // 提交后流水只读，只能在弹层里逐张补打（APP-07）
        b.ledgerLine.setOnClickListener {
            LedgerSheet().show(parentFragmentManager, LedgerSheet.TAG)
        }
        if (!Feedback.reducedMotion(requireContext())) {
            b.check.scaleX = 0.85f
            b.check.scaleY = 0.85f
            b.check.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }
        Feedback.ok(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.done.collect { render(it) } }
                launch {
                    vm.nav.collect { ev ->
                        if (ev is ReceivingNav.ToCreate) {
                            findNavController().navigate(R.id.action_done_to_create)
                        }
                    }
                }
            }
        }
    }

    private fun render(state: DoneUiState) {
        val b = binding ?: return
        b.name.text = state.farmerName
        if (state.pendingPrice || state.amount == null) {
            b.amountRow.isVisible = false
            b.pendingPrice.isVisible = true
        } else {
            b.amountRow.isVisible = true
            b.pendingPrice.isVisible = false
            val money = formatMoney(state.amount)
            b.amountValue.text = money
            fitAmount(b.amountValue, money.length + 1)
        }
        val total = state.totalNetKg?.let { Kg.of(it).format() } ?: "—"
        b.ledgerLine.text = getString(
            R.string.done_ledger_line,
            "${state.productName} ${state.specName}".trim(),
            state.boxCount,
            total,
        )
        val avg = state.avgKg?.let { formatMoney(it) } ?: "—"
        val unit = state.unitPrice?.let { formatMoney(it) }
        b.sub.text = if (unit != null) {
            getString(R.string.done_sub, avg, unit)
        } else {
            getString(R.string.done_sub_avg_only, avg)
        }
        b.batch.text = getString(R.string.done_batch, state.batchNo)
        val job = state.printJob
        b.printProgress.isVisible = job != null
        if (job != null) {
            b.printProgress.text = getString(R.string.print_progress, job.done, job.total)
        }
        // 没打完的标签接着打，打完（或失败停下）才能再收一单，免得中途把剩下的打断
        b.again.isEnabled = job == null
        b.again.text = if (job != null) {
            getString(R.string.print_progress, job.done, job.total)
        } else {
            getString(R.string.done_again)
        }
        b.reprint.bindReprint(
            action = if (job == null) reprintAction(state.printEnabled, state.unprinted, state.printBlock) else null,
            unprinted = state.unprinted,
            onPrint = { vm.reprintUnprinted() },
            onFixPrinter = { DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG) },
        )
    }

    /** 金额字号随位数缩小（原型 .amt：min(64, 容器宽 × 1.55 / 字符数)），长金额也一行放得下。 */
    private fun fitAmount(view: TextView, chars: Int) {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density - 2 * 20f - 8f
        val fit = widthDp * 1.55f / chars / resources.configuration.fontScale
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, minOf(64f, fit))
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
