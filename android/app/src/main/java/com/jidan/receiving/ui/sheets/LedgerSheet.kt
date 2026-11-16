package com.jidan.receiving.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.core.SameWeight
import com.jidan.receiving.databinding.SheetLedgerBinding
import com.jidan.receiving.ui.weigh.ReceivingViewModel
import kotlinx.coroutines.launch

class LedgerSheet : BottomSheetDialogFragment() {
    private var binding: SheetLedgerBinding? = null
    private val vm: ReceivingViewModel by activityViewModels {
        ReceivingViewModel.Factory(requireContext().appContainer())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetLedgerBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.manualAdd.setOnClickListener {
            dismiss()
            BoxSheet.newAdd().show(parentFragmentManager, BoxSheet.TAG)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.collect { state ->
                    b.title.text = getString(R.string.ledger_title_n, state.count)
                    val groups = SameWeight.groups(state.boxes.map { it.net })
                    b.groups.text = if (groups.isEmpty()) {
                        ""
                    } else {
                        groups.take(8).joinToString("  ") { it.label() }
                    }
                    b.rows.removeAllViews()
                    state.boxes.forEach { box ->
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
                        btn.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        btn.text = buildString {
                            append(getString(R.string.weigh_box, box.seq))
                            append("  ")
                            append(box.net.format())
                            append(" kg")
                            if (box.manual) append(" · ").append(getString(R.string.weigh_manual_tag))
                            if (box.manual && box.raw != null) {
                                append("\n原 ").append(box.raw!!.format()).append(" kg")
                            }
                        }
                        btn.setOnClickListener {
                            dismiss()
                            BoxSheet.newFor(box.seq).show(parentFragmentManager, BoxSheet.TAG)
                        }
                        b.rows.addView(btn)
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
        const val TAG = "ledger"
    }
}
