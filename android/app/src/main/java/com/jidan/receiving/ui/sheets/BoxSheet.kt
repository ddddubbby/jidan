package com.jidan.receiving.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.databinding.SheetBoxBinding
import com.jidan.receiving.ui.weigh.ReceivingViewModel

class BoxSheet : BottomSheetDialogFragment() {
    private var binding: SheetBoxBinding? = null
    private val vm: ReceivingViewModel by activityViewModels {
        ReceivingViewModel.Factory(requireContext().appContainer())
    }

    private val seq: Int?
        get() {
            val a = arguments ?: return null
            if (!a.containsKey(ARG_SEQ)) return null
            return a.getInt(ARG_SEQ)
        }
    private val addMode: Boolean
        get() = arguments?.getBoolean(ARG_ADD) == true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetBoxBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        if (addMode) {
            showEdit(b, title = getString(R.string.ledger_manual_add), seq = null)
            return
        }
        val s = seq ?: run {
            dismiss()
            return
        }
        val box = vm.box(s) ?: run {
            dismiss()
            return
        }
        val group = vm.groupOf(s)
        b.title.text = getString(R.string.weigh_box, box.seq) + " · ${box.net.format()} kg"
        val facts = buildList {
            add("皮重 ${box.tare.format()} kg")
            if (box.manual && box.raw != null) add("秤原始读数 ${box.raw!!.format()} kg")
            if (group.size > 1) add("同一次称 ${group.size} 箱")
        }
        b.facts.text = facts.joinToString(" · ")

        val canRegroup = vm.canRegroup(s)
        b.regroupLabel.isVisible = canRegroup
        b.regroupRow.isVisible = canRegroup
        if (canRegroup) {
            b.regroupRow.removeAllViews()
            (1..6).forEach { k ->
                val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                btn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = 4 }
                btn.text = k.toString()
                btn.isAllCaps = false
                if (k == group.size) btn.strokeWidth = 3
                btn.setOnClickListener {
                    vm.regroup(s, k)
                    dismiss()
                }
                b.regroupRow.addView(btn)
            }
        }

        b.reweigh.isVisible = group.size == 1
        b.reweigh.setOnClickListener {
            vm.startReweigh(s)
            dismiss()
        }
        b.manualEdit.setOnClickListener {
            showEdit(b, title = getString(R.string.box_manual_title, box.seq), seq = s)
        }
        b.delete.text = if (group.size > 1) {
            getString(R.string.box_delete_group, group.size)
        } else {
            getString(R.string.box_delete)
        }
        b.delete.setOnClickListener {
            vm.deleteBox(s)
            dismiss()
        }
    }

    private fun showEdit(b: SheetBoxBinding, title: String, seq: Int?) {
        b.title.text = title
        b.facts.isVisible = false
        b.regroupLabel.isVisible = false
        b.regroupRow.isVisible = false
        b.actions.isVisible = false
        b.editBlock.isVisible = true
        b.saveNet.setOnClickListener {
            val raw = b.netInput.text?.toString().orEmpty().trim()
            val v = raw.toDoubleOrNull()
            if (v == null || v <= 0) {
                b.saveNet.text = getString(R.string.box_need_net)
                return@setOnClickListener
            }
            if (seq == null) vm.addManual(v) else vm.editManual(seq, v)
            dismiss()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "box"
        private const val ARG_SEQ = "seq"
        private const val ARG_ADD = "add"

        fun newFor(seq: Int) = BoxSheet().apply {
            arguments = Bundle().apply { putInt(ARG_SEQ, seq) }
        }

        fun newAdd() = BoxSheet().apply {
            arguments = Bundle().apply { putBoolean(ARG_ADD, true) }
        }
    }
}
