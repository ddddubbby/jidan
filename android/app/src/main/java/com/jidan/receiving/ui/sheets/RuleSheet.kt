package com.jidan.receiving.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.databinding.SheetRuleBinding
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import kotlinx.coroutines.launch

/**
 * 定价规则弹层占位（APP-09）。P7-2 只打开并展示当前绑定状态；
 * 调阅 / 解绑 / 更换 / 改系数属 P7-3。
 */
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.summary.collect { state ->
                    val b = binding ?: return@collect
                    val q = state.quote
                    b.status.text = when {
                        q == null -> getString(R.string.rule_sheet_unbound)
                        q.ruleName.isNullOrBlank() || q.noRule -> getString(R.string.rule_sheet_unbound)
                        else -> q.ruleName
                    }
                }
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
