package com.jidan.receiving.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.databinding.SheetLeaveBinding
import com.jidan.receiving.ui.weigh.ReceivingViewModel

/**
 * 放弃这一单？（原型 sheetLeave）：已称了箱子时离开称重页先问一句，只有两个答案——放弃这一单、继续称。
 * 选“放弃”时把结果交给称重页（[RESULT_KEY]），由它清掉这一单并退回建单页；点遮罩外或下拉都当“继续称”。
 */
class LeaveSheet : JidanSheet() {
    private var binding: SheetLeaveBinding? = null
    private val vm: ReceivingViewModel by activityViewModels {
        ReceivingViewModel.Factory(requireContext().appContainer())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetLeaveBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        bindHead(b.head, getString(R.string.weigh_leave_title), closable = false)
        b.leaveMessage.text = getString(R.string.weigh_leave_message, vm.ui.value.boxes.size)
        b.keepWeighing.setOnClickListener { dismiss() }
        b.abandon.setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(KEY_ABANDON to true))
            dismiss()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "leave"
        const val RESULT_KEY = "leave-result"
        const val KEY_ABANDON = "abandon"
    }
}
