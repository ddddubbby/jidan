package com.jidan.receiving.ui.weigh

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
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
import com.jidan.receiving.core.Box
import com.jidan.receiving.core.EmptyBoxTare
import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.WeighPhase
import com.jidan.receiving.databinding.FragmentWeighBinding
import com.jidan.receiving.databinding.ViewBoxRowBinding
import com.jidan.receiving.databinding.ViewBoxSlotBinding
import com.jidan.receiving.databinding.ViewTareChoiceBinding
import com.jidan.receiving.platform.BluetoothPrompts
import com.jidan.receiving.platform.Feedback
import com.jidan.receiving.printing.reprintAction
import com.jidan.receiving.ui.devices.LinkActionKind
import com.jidan.receiving.ui.devices.label
import com.jidan.receiving.ui.devices.linkAction
import com.jidan.receiving.ui.sheets.BoxSheet
import com.jidan.receiving.ui.sheets.DevicesSheet
import com.jidan.receiving.ui.sheets.LeaveSheet
import com.jidan.receiving.ui.sheets.LedgerSheet
import com.jidan.receiving.ui.widgets.ChoiceGrid
import com.jidan.receiving.ui.widgets.ChoiceItem
import com.jidan.receiving.ui.widgets.NumText
import com.jidan.receiving.ui.widgets.StatusTone
import com.jidan.receiving.ui.widgets.TextGlue
import com.jidan.receiving.ui.widgets.bindChip
import com.jidan.receiving.ui.widgets.bindEventTone
import com.jidan.receiving.ui.widgets.bindReprint
import com.jidan.receiving.ui.widgets.dp
import com.jidan.receiving.ui.widgets.printerChip
import com.jidan.receiving.ui.widgets.scaleChip
import com.jidan.receiving.ui.widgets.tintDot
import kotlinx.coroutines.launch

class WeighFragment : Fragment() {
    private var binding: FragmentWeighBinding? = null
    private val prompts = BluetoothPrompts(this)
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
        // 放弃提示（底部弹层）选了“放弃这一单”：清掉这一单，退回建单页
        parentFragmentManager.setFragmentResultListener(LeaveSheet.RESULT_KEY, viewLifecycleOwner) { _, result ->
            if (result.getBoolean(LeaveSheet.KEY_ABANDON)) {
                vm.abandon()
                findNavController().navigateUp()
            }
        }
        b.chipScale.setOnClickListener { DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG) }
        b.chipPrinter.setOnClickListener { DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG) }
        b.changeTare.setOnClickListener { vm.openTarePicker() }
        b.retryTare.setOnClickListener { vm.retryTare() }
        b.startRetry.setOnClickListener { vm.startFromDraft() }
        b.tareWeighBtn.setOnClickListener { vm.weighEmptyBox() }
        b.cancelReweigh.setOnClickListener { vm.cancelReweigh() }
        b.recordBtn.setOnClickListener { vm.pressRecord() }
        b.openLedger.setOnClickListener {
            LedgerSheet().show(parentFragmentManager, LedgerSheet.TAG)
        }
        b.finish.setOnClickListener { vm.onConfirmWeighing() }
        b.scaleCardManual.setOnClickListener {
            BoxSheet.newAdd().show(parentFragmentManager, BoxSheet.TAG)
        }
        // 称重大数字的字号跟着称重框宽度走（原型 .weight：min(96px, 28cqi)），不会因位数多而顶出框
        b.scalePanel.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val inner = (right - left) - v.paddingLeft - v.paddingRight
            if (inner <= 0) return@addOnLayoutChangeListener
            val max = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, WEIGHT_MAX_SP, resources.displayMetrics,
            )
            b.weight.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(max, inner * WEIGHT_WIDTH_RATIO))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.ui.collect { render(it) }
                }
                // 蓝牙开关、授权、配对变化时，连接卡片上“当前该做的一步”要跟着变
                launch {
                    requireContext().appContainer().bluetoothGate.state.collect { renderScaleCard(vm.ui.value) }
                }
                launch {
                    requireContext().appContainer().scale.pairedId.collect { renderScaleCard(vm.ui.value) }
                }
                // 顶部两颗芯片跟着秤、打印机的连接状态、电量与缺纸开盖变
                val c = requireContext().appContainer()
                launch { c.scale.linkStatus.collect { renderChips() } }
                launch { c.scale.batteryLow.collect { renderChips() } }
                launch { c.printer.linkStatus.collect { renderChips() } }
                launch { c.printer.printerStatus.collect { renderChips() } }
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
        if (vm.leaveWeigh() == LeaveWeigh.Left) {
            findNavController().navigateUp()
            return
        }
        // 有箱只给两个答案（原型 sheetLeave）：放弃这一单 / 继续称
        if (parentFragmentManager.findFragmentByTag(LeaveSheet.TAG) == null) {
            LeaveSheet().show(parentFragmentManager, LeaveSheet.TAG)
        }
    }

    private fun renderChips() {
        val b = binding ?: return
        val c = requireContext().appContainer()
        val state = vm.ui.value
        b.chipScale.bindChip(
            scaleChip(
                status = c.scale.linkStatus.value,
                batteryLow = c.scale.batteryLow.value,
                lost = state.scaleLost,
            ),
        )
        b.chipPrinter.bindChip(printerChip(c.printer.linkStatus.value, c.printer.printerStatus.value))
    }

    private fun render(state: WeighUiState) {
        val b = binding ?: return
        val ctx = requireContext()
        b.title.text = state.farmerName
        // 规格、批次号各自不许从中间断开（原型 .nb）
        b.subtitle.text = buildString {
            append(state.productName)
            if (state.specName.isNotBlank()) append(' ').append(TextGlue.noBreak(state.specName))
            if (state.batchNo.isNotBlank()) {
                append(" · ").append(TextGlue.noBreak("批次${TextGlue.NBSP}${state.batchNo}"))
            }
        }
        renderChips()

        // 没取到最新设置、或还在取：不显示称重框，不能称重；失败时按钮直达重试
        b.startCard.isVisible = !state.ready
        if (!state.ready) {
            val failed = state.error.isNotBlank()
            b.startMsg.text = if (failed) {
                getString(R.string.weigh_start_failed, state.error)
            } else {
                getString(R.string.weigh_start_loading)
            }
            b.startMsg.tintDot(if (failed) R.color.err_ink else R.color.faint)
            b.startRetry.isVisible = !state.loading && failed
        }
        b.scalePanel.isVisible = state.ready

        b.tareCard.isVisible = state.showTarePicker
        if (state.showTarePicker) renderTarePicker(b, state)

        b.netLabel.text = when {
            state.reweighSeq != null -> getString(R.string.weigh_net_reweigh, state.reweighSeq)
            state.k > 1 -> getString(R.string.weigh_net_k, state.k)
            else -> getString(R.string.weigh_net)
        }
        b.phaseText.text = phaseLabel(state)
        b.phaseText.setTextColor(
            ContextCompat.getColor(
                ctx,
                when (state.phase) {
                    WeighPhase.RECORDED, WeighPhase.READY -> R.color.ok_ink
                    WeighPhase.ASK, WeighPhase.HOLD -> R.color.warn_ink
                    else -> R.color.ink_2
                },
            ),
        )
        b.scalePanel.setBackgroundResource(
            when (state.phase.cardTone()) {
                CardTone.OK -> R.drawable.bg_scale_ok
                CardTone.WARN -> R.drawable.bg_scale_warn
                CardTone.IDLE -> R.drawable.bg_scale
            },
        )
        b.weight.text = NumText.weight(ctx, state.displayNet?.format1() ?: "0.0", "斤")
        b.weight.setTextColor(
            ContextCompat.getColor(ctx, if (state.phase.numberTone() == NumberTone.PALE) R.color.idle else R.color.ink),
        )
        b.weight.alpha = if (state.phase.numberTone() == NumberTone.FADED) 0.3f else 1f

        val cand = state.candidates
        b.askRow.isVisible = cand != null
        if (cand != null) {
            b.askRow.removeAllViews()
            listOf(cand.first, cand.second).forEachIndexed { i, k ->
                val btn = MaterialButton(
                    ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
                )
                btn.setTextAppearance(R.style.TextAppearance_Jidan_Button)
                btn.minimumHeight = dp(52)
                btn.textSize = 18f
                btn.strokeColor = ContextCompat.getColorStateList(ctx, R.color.warn_ink)
                btn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { if (i == 0) it.marginEnd = dp(5) else it.marginStart = dp(5) }
                btn.text = getString(R.string.weigh_choose_k, k)
                btn.setOnClickListener { vm.chooseK(k) }
                b.askRow.addView(btn)
            }
        }

        b.tareRow.isVisible = !state.showTarePicker
        b.tareText.text = tareText(ctx, state)

        val tareFailed = state.ready && state.tareError.isNotBlank()
        b.tareErrorText.isVisible = tareFailed
        b.retryTare.isVisible = tareFailed
        if (tareFailed) {
            val tare = state.tare
            if (tare != null) {
                b.tareErrorText.text = getString(R.string.weigh_tare_failed, state.tareError)
                b.retryTare.text = getString(R.string.weigh_retry_tare, tare.unit.format())
            } else {
                // 还没选皮重：失败的是清掉秤里上一单留下的皮重
                b.tareErrorText.text = getString(R.string.weigh_clear_tare_failed, state.tareError)
                b.retryTare.text = getString(R.string.weigh_retry_clear_tare)
            }
        }

        b.reweighRow.isVisible = state.reweighSeq != null
        if (state.reweighSeq != null) {
            val old = state.boxes.firstOrNull { it.seq == state.reweighSeq }
            b.reweighText.text = buildString {
                append(getString(R.string.weigh_box, state.reweighSeq))
                if (old != null) append(" · 原 ").append(TextGlue.withUnit(old.net.format(), "斤"))
            }
        }

        b.recordBtn.isVisible = state.recordByButton && !state.showScaleCard
        b.recordBtn.isEnabled = state.canPressRecord

        val prevEvent = b.event.text?.toString().orEmpty()
        // 正常记录的反馈是称重框变绿、提示音，不另写一行；重称、删除、改箱数才写（原型 record()）。
        // 文字照常放进去，下面靠它判断这一条是不是新的，新的才响提示音
        b.event.text = state.eventText
        b.event.isVisible = showEventLine(state.eventText)
        b.event.bindEventTone(if (state.eventOk) null else StatusTone.WARN)
        if (state.eventText.isNotBlank() && state.eventText != prevEvent) {
            if (state.eventOk) Feedback.ok(ctx) else Feedback.warn(ctx)
        }

        renderRecent(b, state)

        val job = state.printJob
        b.printProgress.isVisible = job != null
        if (job != null) {
            b.printProgressText.text = getString(R.string.print_progress, job.done, job.total)
            b.printBar.max = job.total.coerceAtLeast(1)
            b.printBar.progress = job.done
        }
        // 去向由打印机此刻的状态定：换好纸后按钮立刻变回「补打」，不看上一次失败的文字
        b.reprint.bindReprint(
            action = if (job == null) reprintAction(state.printEnabled, state.unprinted, state.printBlock) else null,
            unprinted = state.unprinted,
            onPrint = { vm.reprintUnprinted() },
            onFixPrinter = { DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG) },
        )
        // 标签预览只在模拟台里（开发规格 11.4）；实机版没有模拟台，称重页上不出现
        SimConsoleHook.showLabelPreview(b.simHost, state.labelPreview)

        b.finish.isEnabled = state.count > 0 && state.reweighSeq == null
        renderScaleCard(state)
    }

    /** 请选择皮重：三个选项排成一行的小块（选中的墨底），下面是“称空箱”。 */
    private fun renderTarePicker(b: FragmentWeighBinding, state: WeighUiState) {
        val items = state.tares.map { t ->
            val tare = Jin.of(t.tareJin)
            val tag = if (t.id == state.lastTareId) " · 上一单" else ""
            ChoiceItem(
                label = t.name,
                badge = "${tare.format()}${TextGlue.NBSP}斤$tag",
                selected = state.tare?.let { it.name == t.name && it.unit == tare } == true,
            ) { vm.selectTare(t) }
        }
        ChoiceGrid.fill(
            host = b.tareChoices,
            items = items,
            cols = ChoiceGrid.cols(state.tares.map { it.name }),
            badgeRatio = 12f / 16f,
        ) { parent -> ViewTareChoiceBinding.inflate(layoutInflater, parent, false).root }
        b.tareWeighBtn.isEnabled = state.emptyBox.available
        b.tareWeighBtn.text = EmptyBoxTare.label(state.emptyBox)
    }

    /** 皮重这一行：“2号包装箱 2斤”；一次放 k 箱时写“× k = 合计”（原型 live()）。没选时留白（皮重卡片在上面）。 */
    private fun tareText(ctx: android.content.Context, state: WeighUiState): CharSequence {
        val t = state.tare ?: return "—"
        val out = SpannableStringBuilder(t.name).append(' ')
        out.append(NumText.row(ctx, t.unit.format(), "斤"))
        if (state.k > 1) {
            out.append(" × ${state.k} = ")
            out.append(NumText.row(ctx, Jin(t.unit.cents * state.k).format(), "斤"))
        }
        return out
    }

    /** 已称统计、均重，与最近几箱（没称之前留 4 个空位，记一箱填一格，版面不跳）。 */
    private fun renderRecent(b: FragmentWeighBinding, state: WeighUiState) {
        val ctx = requireContext()
        if (state.count == 0) {
            b.stats.text = getString(R.string.weigh_stats_zero)
            b.stats.setTextColor(ContextCompat.getColor(ctx, R.color.idle))
            b.avg.text = avgText(ctx, null, null, empty = true)
        } else {
            b.stats.text = getString(R.string.weigh_stats, state.count, state.totalNet.format())
            b.stats.setTextColor(ContextCompat.getColor(ctx, R.color.ink))
            val raw = String.format("%.2f", state.totalNet.toDouble() / state.count)
            b.avg.text = avgText(ctx, state.avg(vm.roundRule)?.format(), raw, empty = false)
        }

        b.recentList.removeAllViews()
        val warnable = state.printPerBox || state.locked
        state.recent.forEach { box ->
            b.recentList.addView(boxRow(b.recentList, box, state, warnable))
        }
        repeat((RECENT_SLOTS - state.recent.size).coerceAtLeast(0)) {
            b.recentList.addView(ViewBoxSlotBinding.inflate(layoutInflater, b.recentList, false).root)
        }
        b.openLedger.isVisible = state.count > 0
    }

    private fun boxRow(parent: ViewGroup, box: Box, state: WeighUiState, warnable: Boolean): View {
        val r = ViewBoxRowBinding.inflate(layoutInflater, parent, false)
        val ctx = requireContext()
        r.boxSeq.text = getString(R.string.weigh_box, box.seq)
        r.boxNet.text = NumText.row(ctx, box.net.format(), "斤")
        r.boxManual.isVisible = box.manual
        when (printMark(state.printEnabled, warnable, box)) {
            PrintMark.NONE -> r.boxMark.isVisible = false
            PrintMark.DONE -> {
                r.boxMark.isVisible = true
                r.boxMark.text = "✓"
                r.boxMark.setTextColor(ContextCompat.getColor(ctx, R.color.ink_2))
            }
            PrintMark.PENDING -> {
                r.boxMark.isVisible = true
                r.boxMark.text = getString(R.string.weigh_mark_unprinted)
                r.boxMark.setTextColor(ContextCompat.getColor(ctx, R.color.warn_ink))
            }
        }
        r.boxRow.contentDescription = getString(R.string.weigh_box_adjust, box.seq)
        r.boxRow.setOnClickListener {
            BoxSheet.newFor(box.seq).show(parentFragmentManager, BoxSheet.TAG)
        }
        return r.root
    }

    /** 均重一行：“均重 47 斤 46.50”——名称 15 号灰、取整后的均重 19 号粗、未取整的两位小数 13 号灰（原型 .avg）。 */
    private fun avgText(ctx: android.content.Context, avg: String?, raw: String?, empty: Boolean): CharSequence {
        val out = SpannableStringBuilder()
        out.append(getString(R.string.weigh_avg_key))
        out.setSpan(AbsoluteSizeSpan(15, true), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.ink_2)), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append("  ")
        val start = out.length
        if (empty || avg == null) {
            out.append("—")
        } else {
            out.append(NumText.row(ctx, avg, "斤"))
        }
        out.setSpan(AbsoluteSizeSpan(19, true), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val color = if (empty) R.color.idle else R.color.ink
        out.setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, color)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!empty && raw != null) {
            out.append("  ")
            val s2 = out.length
            out.append(raw)
            out.setSpan(AbsoluteSizeSpan(13, true), s2, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.ink_2)), s2, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ResourcesCompat.getFont(ctx, R.font.inter_medium)?.let {
                out.setSpan(NumText.TypefaceSpanCompat(it), s2, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    /**
     * 秤没连上或断开：称重框位置换成连接卡片，只给当前该做的一个连接操作和“手工加一箱”（原型 screenWeigh）。
     * 卡片出现时称重框里的读数、皮重、按钮都藏起来；正在重称的提示行留着，免得取消不掉。
     * 断开（连上过又断了）整卡变红，没连过的是平常的绿色步骤卡。
     */
    private fun renderScaleCard(state: WeighUiState) {
        val b = binding ?: return
        val c = requireContext().appContainer()
        val card = state.showScaleCard
        b.scaleCard.isVisible = card
        b.scalePanel.isVisible = !card || state.reweighSeq != null
        b.scaleTop.isVisible = !card
        b.weight.isVisible = !card
        b.tareRow.isVisible = !card && !state.showTarePicker
        if (card) b.askRow.isVisible = false
        if (!card) return
        b.scaleCard.setBackgroundResource(if (state.scaleLost) R.drawable.bg_card_lost else R.drawable.bg_step_active)
        b.scaleCardTitle.setTextColor(
            ContextCompat.getColor(requireContext(), if (state.scaleLost) R.color.err_ink else R.color.ok_ink),
        )
        b.scaleCardTitle.text = getString(if (state.scaleLost) R.string.weigh_scale_lost else R.string.weigh_scale_off)
        val action = linkAction(c.bluetoothGate, c.scale.linkStatus.value, c.scale.pairedId.value)
        b.scaleCardAction.isVisible = action != LinkActionKind.NONE
        b.scaleCardAction.isEnabled = action != LinkActionKind.CONNECTING
        b.scaleCardAction.text = action.label(isScale = true)
        b.scaleCardAction.setOnClickListener {
            // 打开蓝牙、允许使用蓝牙点了直达系统页；搜索类去设备弹层选秤
            if (!prompts.handle(action)) DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG)
        }
        b.scaleCardManual.isVisible = state.showManualAdd
    }

    /**
     * 称重框右上角的状态（原型 live()）：未设皮重 / 读数未稳定 / 空秤 / ✓ 第 N 箱已记录 / ↑ 待拿下 / 几箱？ / 已稳定。
     */
    private fun phaseLabel(state: WeighUiState): String = when (state.phase) {
        WeighPhase.LOCKED -> getString(R.string.weigh_phase_locked)
        WeighPhase.UNSTABLE -> getString(R.string.weigh_phase_unstable)
        WeighPhase.EMPTY -> getString(R.string.weigh_phase_empty)
        WeighPhase.RECORDED -> recordedLabel(state.boxes, state.eventText)
        WeighPhase.HOLD -> getString(R.string.weigh_phase_hold)
        WeighPhase.ASK -> getString(R.string.weigh_phase_ask)
        WeighPhase.READY -> getString(R.string.weigh_phase_ready)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val WEIGHT_MAX_SP = 96f
        const val WEIGHT_WIDTH_RATIO = 0.28f
        const val RECENT_SLOTS = 4
    }
}
