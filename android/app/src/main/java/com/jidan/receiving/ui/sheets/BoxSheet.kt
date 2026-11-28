package com.jidan.receiving.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.core.Box
import com.jidan.receiving.databinding.SheetBoxBinding
import com.jidan.receiving.databinding.ViewKChoiceBinding
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import com.jidan.receiving.ui.weigh.seqRangeText
import com.jidan.receiving.ui.widgets.ChoiceGrid
import com.jidan.receiving.ui.widgets.ChoiceItem
import com.jidan.receiving.ui.widgets.NumText
import com.jidan.receiving.ui.widgets.TextGlue

class BoxSheet : JidanSheet() {
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
        bindHead(b.head, "")
        // `weigh.manual-entry = off`：不出现手工录入与手工加一箱（重称、删除照常）
        val manualOk = vm.ui.value.manualEntry
        if (addMode) {
            if (!manualOk) {
                dismiss()
                return
            }
            showEdit(b, title = getString(R.string.ledger_manual_add), seq = null, box = null)
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
        showDetail(b, s, box, manualOk)
    }

    private fun showDetail(b: SheetBoxBinding, s: Int, box: Box, manualOk: Boolean) {
        val ctx = requireContext()
        b.head.sheetTitle.text = getString(R.string.weigh_box, box.seq)
        b.detail.isVisible = true
        b.editBlock.isVisible = false
        val group = vm.groupOf(s)

        // 大字净重，旁边是“手录”小标；下面一行小字：皮重、手录时秤的原读数、同一次称的另几箱
        b.boxBig.text = NumText.big(ctx, box.net.format(), "斤")
        b.boxManual.isVisible = box.manual
        val facts = buildList {
            add("皮重 ${TextGlue.withUnit(box.tare.format(), "斤")}")
            if (box.manual && box.raw != null) add("秤原始读数 ${TextGlue.withUnit(box.raw!!.format(), "斤")}")
            if (group.size > 1) {
                val others = group.filter { it.seq != s }
                if (others.isNotEmpty()) {
                    add("和${seqRangeText(others.first().seq..others.last().seq)}是同一次称的")
                }
            }
        }
        b.facts.text = facts.joinToString(" · ")

        val canRegroup = vm.canRegroup(s)
        b.regroupLabel.isVisible = canRegroup
        b.regroupRow.isVisible = canRegroup
        if (canRegroup) {
            val items = (1..6).map { k ->
                ChoiceItem(label = k.toString(), selected = k == group.size) {
                    vm.regroup(s, k)
                    dismiss()
                }
            }
            ChoiceGrid.fill(b.regroupRow, items, cols = 6, gapDp = 6) { parent ->
                ViewKChoiceBinding.inflate(layoutInflater, parent, false).root
            }
        }

        b.reweigh.isVisible = group.size == 1
        b.reweigh.setOnClickListener {
            vm.startReweigh(s)
            dismiss()
        }
        b.manualEdit.isVisible = manualOk
        b.manualEdit.setOnClickListener {
            showEdit(b, title = getString(R.string.box_manual_title, box.seq), seq = s, box = box)
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

    /**
     * 手工录入 / 手工加一箱（原型 boxMode）：改已有的一箱时先带出现在的净重并全选，直接打新数就覆盖；
     * 没填对时“保存”变成白底绿边的“填写净重”，点了把光标送过去（先决条件靠交互，不靠说明文字）。
     * 取消回到这一箱的详情；手工加一箱时取消就是关掉。
     */
    private fun showEdit(b: SheetBoxBinding, title: String, seq: Int?, box: Box?) {
        b.head.sheetTitle.text = title
        b.detail.isVisible = false
        b.editBlock.isVisible = true
        b.editHint.isVisible = box != null
        if (box != null) {
            // 手录过的箱，秤的读数是当初的原读数；没手录过的，秤的读数就是现在的净重
            val reading = if (box.manual && box.raw != null) box.raw!! else box.net
            b.editHint.text = "秤的读数 ${TextGlue.withUnit(reading.format(), "斤")}"
        }
        fun valid(): Boolean = (b.netInput.text?.toString().orEmpty().trim().toDoubleOrNull() ?: 0.0) > 0
        fun refresh() {
            val ok = valid()
            b.saveNet.isActivated = !ok
            b.saveNet.text = getString(if (ok) R.string.box_save else R.string.box_need_net)
        }
        b.netInput.setText(box?.net?.format().orEmpty())
        b.netInput.doAfterTextChanged { refresh() }
        refresh()
        b.cancelEdit.setOnClickListener {
            if (seq == null || box == null) dismiss() else showDetail(b, seq, box, manualOk = true)
        }
        b.saveNet.setOnClickListener {
            if (!valid()) {
                focusInput(b)
                return@setOnClickListener
            }
            val v = b.netInput.text.toString().trim().toDouble()
            if (seq == null) vm.addManual(v) else vm.editManual(seq, v)
            dismiss()
        }
        focusInput(b)
    }

    private fun focusInput(b: SheetBoxBinding) {
        b.netInput.requestFocus()
        b.netInput.setSelection(b.netInput.text?.length ?: 0)
        b.netInput.selectAll()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        (requireContext().getSystemService(InputMethodManager::class.java))?.showSoftInput(b.netInput, 0)
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
