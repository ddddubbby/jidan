package com.jidan.receiving.ui.weigh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.jidan.receiving.R
import com.jidan.receiving.SimConsoleHook
import com.jidan.receiving.appContainer
import com.jidan.receiving.core.WeighPhase
import com.jidan.receiving.databinding.FragmentWeighBinding
import com.jidan.receiving.platform.Feedback
import com.jidan.receiving.printing.reprintAction
import com.jidan.receiving.ui.sheets.BoxSheet
import com.jidan.receiving.ui.sheets.DevicesSheet
import com.jidan.receiving.ui.sheets.LedgerSheet
import com.jidan.receiving.ui.widgets.bindReprint
import kotlinx.coroutines.launch

class WeighFragment : Fragment() {
    private var binding: FragmentWeighBinding? = null
    private val vm: ReceivingViewModel by activityViewModels {
        ReceivingViewModel.Factory(requireContext().appContainer())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentWeighBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        vm.startFromDraft()

        b.back.setOnClickListener { leave() }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leave()
            },
        )
        b.chipScale.setOnClickListener { DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG) }
        b.chipPrinter.setOnClickListener { DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG) }
        b.changeTare.setOnClickListener { vm.openTarePicker() }
        b.tareWeighBtn.setOnClickListener { vm.weighEmptyBox() }
        b.cancelReweigh.setOnClickListener { vm.cancelReweigh() }
        b.recordBtn.setOnClickListener { vm.pressRecord() }
        b.openLedger.setOnClickListener {
            LedgerSheet().show(parentFragmentManager, LedgerSheet.TAG)
        }
        b.finish.setOnClickListener { vm.onConfirmWeighing() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.ui.collect { render(it) }
                }
                launch {
                    vm.nav.collect { ev ->
                        if (ev is ReceivingNav.ToSummary) {
                            findNavController().navigate(R.id.action_weigh_to_summary)
                        }
                    }
                }
            }
        }

        if (b.simHost.childCount == 0) {
            val prefs = requireContext().appContainer().prefs
            SimConsoleHook.attach(
                b.simHost,
                onFill100 = { vm.seedAdaptBoxes() },
                onWorstName = { vm.applyWorstFarmerName() },
                onToggleBigFont = {
                    prefs.simBigFont = !prefs.simBigFont
                    requireActivity().recreate()
                },
                bigFontOn = prefs.simBigFont,
            )
        }
    }

    private fun leave() {
        if (!vm.hasBoxes()) {
            findNavController().navigateUp()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.weigh_leave_title)
            .setPositiveButton(R.string.weigh_leave_keep) { _, _ ->
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.weigh_abandon) { _, _ ->
                vm.abandon()
                findNavController().navigateUp()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(state: WeighUiState) {
        val b = binding ?: return
        b.title.text = state.farmerName
        b.subtitle.text = buildString {
            append(state.productName)
            if (state.specName.isNotBlank()) append(' ').append(state.specName)
            if (state.batchNo.isNotBlank()) append(" · 批次 ").append(state.batchNo)
        }

        b.chipScale.text = when {
            state.scaleLost -> getString(R.string.weigh_scale_lost)
            state.scaleConnected -> getString(R.string.weigh_chip_ok)
            else -> getString(R.string.weigh_chip_off)
        }
        b.chipScale.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    state.scaleLost || !state.scaleConnected -> R.color.err_ink
                    else -> R.color.ok_ink
                },
            ),
        )

        b.tareCard.isVisible = state.showTarePicker
        if (state.showTarePicker) {
            b.tareChoices.removeAllViews()
            state.tares.forEach { t ->
                val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                btn.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = 8 }
                btn.isAllCaps = false
                val tag = if (t.id == state.lastTareId) " · 上一单用的" else ""
                btn.text = "${t.name}$tag\n${com.jidan.receiving.core.Kg.of(t.tareKg).format()} kg"
                btn.setOnClickListener { vm.selectTare(t) }
                b.tareChoices.addView(btn)
            }
            b.tareWeighBtn.isEnabled = state.emptyBox.available
            b.tareWeighBtn.text = com.jidan.receiving.core.EmptyBoxTare.label(state.emptyBox)
        }

        b.netLabel.text = if (state.k > 1) {
            getString(R.string.weigh_net_k, state.k)
        } else {
            getString(R.string.weigh_net)
        }
        b.phaseText.text = phaseLabel(state.phase)
        b.phaseText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (state.phase) {
                    WeighPhase.RECORDED, WeighPhase.READY -> R.color.ok_ink
                    WeighPhase.ASK, WeighPhase.HOLD -> R.color.warn_ink
                    WeighPhase.LOCKED -> R.color.err_ink
                    else -> R.color.ink_2
                },
            ),
        )
        b.weight.text = (state.displayNet?.format1() ?: "0.0") + " kg"

        val cand = state.candidates
        b.askRow.isVisible = cand != null
        if (cand != null) {
            b.askRow.removeAllViews()
            listOf(cand.first, cand.second).forEach { k ->
                val btn = MaterialButton(requireContext())
                btn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = 8 }
                btn.text = getString(R.string.weigh_choose_k, k)
                btn.isAllCaps = false
                btn.setOnClickListener { vm.chooseK(k) }
                b.askRow.addView(btn)
            }
        }

        b.tareRow.isVisible = !state.showTarePicker
        b.tareText.text = state.tare?.let { "${it.name} · ${it.unit.format()} kg" } ?: "—"

        b.reweighRow.isVisible = state.reweighSeq != null
        if (state.reweighSeq != null) {
            b.reweighText.text = getString(R.string.weigh_reweigh, state.reweighSeq)
        }

        b.recordBtn.isVisible = state.recordByButton
        b.recordBtn.isEnabled = state.canPressRecord

        val prevEvent = b.event.text?.toString().orEmpty()
        b.event.isVisible = state.eventText.isNotBlank()
        b.event.text = state.eventText
        if (state.eventText.isNotBlank() && state.eventText != prevEvent) {
            if (state.eventOk) Feedback.ok(requireContext()) else Feedback.warn(requireContext())
        }

        if (state.count == 0) {
            b.stats.text = getString(R.string.weigh_stats_zero)
            b.avg.text = "均重 —"
        } else {
            b.stats.text = getString(R.string.weigh_stats, state.count, state.totalNet.format())
            val avg = state.avg(vm.roundRule)
            val raw = String.format("%.2f", state.totalNet.toDouble() / state.count)
            b.avg.text = getString(R.string.weigh_avg, avg?.format() ?: "—") +
                getString(R.string.weigh_avg_raw, raw)
        }

        b.recentList.removeAllViews()
        state.recent.forEach { box ->
            val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
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
            }
            btn.setOnClickListener {
                BoxSheet.newFor(box.seq).show(parentFragmentManager, BoxSheet.TAG)
            }
            b.recentList.addView(btn)
        }

        b.openLedger.isVisible = state.count > 0

        val job = state.printJob
        b.printProgress.isVisible = job != null
        if (job != null) {
            b.printProgress.text = getString(R.string.print_progress, job.done, job.total)
        }
        // 去向由打印机此刻的状态定：换好纸后按钮立刻变回「补打」，不看上一次失败的文字
        b.reprint.bindReprint(
            action = if (job == null) reprintAction(state.printEnabled, state.unprinted, state.printBlock) else null,
            unprinted = state.unprinted,
            onPrint = { vm.reprintUnprinted() },
            onFixPrinter = { DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG) },
        )
        val preview = state.labelPreview
        b.labelPreviewTitle.isVisible = preview.isNotBlank()
        b.labelPreview.isVisible = preview.isNotBlank()
        b.labelPreview.text = preview
        b.simHost.findViewById<android.widget.TextView>(R.id.labelPreview)?.let { tv ->
            tv.text = preview.ifBlank { "—" }
        }

        b.finish.isEnabled = state.count > 0 && state.reweighSeq == null
        b.finish.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                if (b.finish.isEnabled) R.color.accent else R.color.line,
            ),
        )
        b.finish.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (b.finish.isEnabled) R.color.on_accent else R.color.ink_2,
            ),
        )
    }

    private fun phaseLabel(p: WeighPhase): String = when (p) {
        WeighPhase.LOCKED -> getString(R.string.weigh_phase_locked)
        WeighPhase.UNSTABLE -> getString(R.string.weigh_phase_unstable)
        WeighPhase.EMPTY -> getString(R.string.weigh_phase_empty)
        WeighPhase.RECORDED -> getString(R.string.weigh_phase_recorded)
        WeighPhase.HOLD -> getString(R.string.weigh_phase_hold)
        WeighPhase.ASK -> getString(R.string.weigh_phase_ask)
        WeighPhase.READY -> getString(R.string.weigh_phase_ready)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
