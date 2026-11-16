package com.jidan.receiving.ui.done

import android.os.Bundle
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
import com.jidan.receiving.databinding.FragmentDoneBinding
import com.jidan.receiving.platform.Feedback
import com.jidan.receiving.ui.summary.formatMoney
import com.jidan.receiving.ui.weigh.DoneUiState
import com.jidan.receiving.ui.weigh.ReceivingNav
import com.jidan.receiving.ui.weigh.ReceivingViewModel
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
        b.ledgerLine.setOnClickListener {
            // 提交后流水已清；只展示文案，不打开空弹层
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
            b.amountValue.text = formatMoney(state.amount)
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
        b.unprinted.isVisible = state.printEnabled && state.unprinted > 0
        if (b.unprinted.isVisible) {
            b.unprinted.text = getString(
                R.string.print_failed_n,
                state.unprinted,
                state.printError.ifBlank { "未打印" },
            )
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
