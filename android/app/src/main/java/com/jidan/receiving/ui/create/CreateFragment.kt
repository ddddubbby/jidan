package com.jidan.receiving.ui.create

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.jidan.receiving.DemoMode
import com.jidan.receiving.R
import com.jidan.receiving.SimConsoleHook
import com.jidan.receiving.appContainer
import com.jidan.receiving.data.FarmerDto
import com.jidan.receiving.databinding.FragmentCreateBinding
import com.jidan.receiving.databinding.ViewChoiceButtonBinding
import com.jidan.receiving.databinding.ViewListRowBinding
import com.jidan.receiving.databinding.ViewStepRailBinding
import com.jidan.receiving.platform.BluetoothPrompts
import com.jidan.receiving.ui.sheets.DevicesSheet
import com.jidan.receiving.ui.update.bindUpdateEntry
import com.jidan.receiving.ui.widgets.DeviceCardBinder
import com.jidan.receiving.ui.widgets.NumText
import com.jidan.receiving.ui.widgets.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 建单四步（APP-02 / lab screenCreate）。设备卡片与模拟台挂在本页底部。
 */
class CreateFragment : Fragment() {
    private var binding: FragmentCreateBinding? = null
    private val vm: CreateViewModel by viewModels {
        CreateViewModel.Factory(requireContext().appContainer())
    }
    private var syncingTail = false
    private val prompts = BluetoothPrompts(this)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentCreateBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        val c = requireContext().appContainer()

        b.subtitle.text = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.M.d"))
        b.account.setOnClickListener { confirmLogout() }
        b.action.setOnClickListener { onAction() }
        b.tail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncingTail) return
                vm.onTail(s?.toString().orEmpty())
            }
        })

        fun openSheet() {
            DevicesSheet().show(parentFragmentManager, DevicesSheet.TAG)
        }

        fun refreshCards() {
            DeviceCardBinder.bindScaleLine(
                binding = b.scaleCard,
                gate = c.bluetoothGate,
                status = c.scale.linkStatus.value,
                pairedId = c.scale.pairedId.value,
                modelKey = c.options.value.get("device.scale-model"),
                batteryLow = c.scale.batteryLow.value,
                onAction = { act -> if (!prompts.handle(act)) openSheet() },
                onOpenSheet = ::openSheet,
            )
            DeviceCardBinder.bindPrinterLine(
                binding = b.printerCard,
                gate = c.bluetoothGate,
                status = c.printer.linkStatus.value,
                pairedId = c.printer.pairedId.value,
                modelKey = c.options.value.get("device.printer-model"),
                issue = c.printer.printerStatus.value,
                onAction = { act -> if (!prompts.handle(act)) openSheet() },
                onOpenSheet = ::openSheet,
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.ui.collect { render(it) } }
                launch { c.bluetoothGate.state.collect { refreshCards() } }
                launch { c.scale.linkStatus.collect { refreshCards() } }
                launch { c.scale.pairedId.collect { refreshCards() } }
                launch { c.scale.batteryLow.collect { refreshCards() } }
                launch { c.printer.linkStatus.collect { refreshCards() } }
                launch { c.printer.pairedId.collect { refreshCards() } }
                launch { c.printer.printerStatus.collect { refreshCards() } }
            }
        }
        refreshCards()

        if (b.simHost.childCount == 0) {
            val prefs = c.prefs
            SimConsoleHook.attach(
                b.simHost,
                onToggleBigFont = {
                    prefs.simBigFont = !prefs.simBigFont
                    requireActivity().recreate()
                },
                bigFontOn = prefs.simBigFont,
            )
        }
    }

    private fun onAction() {
        val state = vm.ui.value
        if (!state.ready) {
            vm.nudge()
            return
        }
        if (vm.commitHeader()) {
            findNavController().navigate(R.id.action_create_to_weigh)
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.logout)
            .setPositiveButton(R.string.logout) { _, _ ->
                requireContext().appContainer().clearSession()
                // 演示版没有登录页：回到一张空白建单页（已重新以演示账号登录）
                findNavController().navigate(
                    if (DemoMode.enabled) R.id.action_global_to_create else R.id.action_global_to_login,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(state: CreateUiState) {
        val b = binding ?: return
        b.account.text = if (state.operatorName.isBlank()) "›" else "${state.operatorName} ›"
        b.error.isVisible = state.error.isNotBlank()
        b.error.text = state.error
        b.updateEntry.bindUpdateEntry(state.update)

        syncingTail = true
        if (b.tail.text?.toString() != state.query) {
            b.tail.setText(state.query)
            b.tail.setSelection(b.tail.text?.length ?: 0)
        }
        syncingTail = false

        val active = state.active
        val steps = stepViews(b)
        val states = listOf(
            stepState(state.farmerId != null, active == CreateStep.FARMER),
            stepState(state.productId != null, active == CreateStep.PRODUCT),
            stepState(state.specId != null, active == CreateStep.SPEC),
            stepState(state.warehouseId != null, active == CreateStep.WAREHOUSE),
        )
        states.forEachIndexed { i, st ->
            styleStep(
                steps[i], i, st,
                last = i == states.lastIndex,
                prevDone = i > 0 && states[i - 1] == StepState.DONE,
            )
        }
        renderFarmerStep(b, state, active)
        renderProductStep(b, state, active)
        renderSpecStep(b, state, active)
        renderWarehouseStep(b, state, active)

        // 还差几步：白底绿边（仍可点，点了直达缺的那步）；齐了：绿底
        b.action.isEnabled = true
        b.action.isActivated = !state.ready
        b.action.text = if (state.ready) {
            getString(R.string.create_start)
        } else {
            getString(R.string.create_missing, state.missing.size)
        }
    }

    private fun stepState(done: Boolean, active: Boolean) = when {
        active -> StepState.ACTIVE
        done -> StepState.DONE
        else -> StepState.TODO
    }

    private fun renderFarmerStep(b: FragmentCreateBinding, state: CreateUiState, active: CreateStep?) {
        val done = state.farmerId != null
        val isActive = active == CreateStep.FARMER
        b.farmerBody.isVisible = isActive
        b.farmerSummary.isVisible = done && !isActive
        if (done && !isActive) {
            b.farmerTitle.text = getString(R.string.create_step_farmer)
            b.farmerSummary.text = nameAndPhone(state.farmerName.orEmpty(), state.farmerPhone)
            b.stepFarmer.setOnClickListener { vm.edit(CreateStep.FARMER) }
        } else {
            b.farmerTitle.text = getString(if (isActive) R.string.create_ask_farmer else R.string.create_step_farmer)
            b.stepFarmer.setOnClickListener(null)
        }
        b.stepFarmer.isClickable = done && !isActive

        renderTailCells(b, state.query, isActive)
        b.noMatch.isVisible = isActive && state.noMatch.isNotBlank() && state.query.isEmpty()
        if (state.noMatch.isNotBlank()) {
            b.noMatch.text = getString(R.string.create_no_match, state.noMatch)
        }

        b.farmerList.removeAllViews()
        val rows: List<FarmerDto> = when {
            !isActive -> emptyList()
            state.query.length == 4 -> state.farmers
            state.query.isEmpty() -> state.recent
            else -> emptyList()
        }
        b.recentLabel.isVisible = isActive && state.query.isEmpty() && state.recent.isNotEmpty()
        b.farmerList.isVisible = rows.isNotEmpty()
        rows.forEachIndexed { i, f ->
            if (i > 0) b.farmerList.addView(divider())
            val selected = state.query.length == 4 && f.id == state.farmerId
            b.farmerList.addView(
                listRow(
                    parent = b.farmerList,
                    main = farmerRowText(f, state.query),
                    side = if (selected) getString(R.string.create_selected) else null,
                    selected = selected,
                ) { vm.selectFarmer(f) },
            )
        }
    }

    /** 手机尾号 4 个格子：当前要输的那一格亮边（空的再加光标线）。 */
    private fun renderTailCells(b: FragmentCreateBinding, query: String, isActive: Boolean) {
        val cells = listOf(b.cell0, b.cell1, b.cell2, b.cell3)
        val cur = minOf(query.length, cells.lastIndex)
        cells.forEachIndexed { i, cell ->
            val ch = query.getOrNull(i)
            cell.text = ch?.toString().orEmpty()
            cell.setBackgroundResource(
                when {
                    !isActive || i != cur -> R.drawable.bg_otp_cell
                    ch == null -> R.drawable.bg_otp_cell_cur_empty
                    else -> R.drawable.bg_otp_cell_cur
                },
            )
        }
    }

    private fun renderProductStep(b: FragmentCreateBinding, state: CreateUiState, active: CreateStep?) {
        val done = state.productId != null
        val isActive = active == CreateStep.PRODUCT
        b.productChoices.isVisible = isActive
        b.productSummary.isVisible = done && !isActive
        if (done && !isActive) {
            b.productTitle.text = getString(R.string.create_step_product)
            b.productSummary.text = state.productName
            b.stepProduct.setOnClickListener { vm.edit(CreateStep.PRODUCT) }
        } else {
            b.productTitle.text = getString(if (isActive) R.string.create_ask_product else R.string.create_step_product)
            b.stepProduct.setOnClickListener(null)
        }
        b.stepProduct.isClickable = done && !isActive
        if (isActive) {
            val ruled = state.rules.map { it.productId }.toSet()
            fillChoices(
                b.productChoices,
                state.products.map { p ->
                    Choice(
                        label = p.name,
                        badge = if (p.id in ruled) getString(R.string.create_has_rule) else null,
                        warn = false,
                        selected = p.id == state.productId,
                    ) { vm.selectProduct(p) }
                },
            )
        }
    }

    private fun renderSpecStep(b: FragmentCreateBinding, state: CreateUiState, active: CreateStep?) {
        val done = state.specId != null
        val isActive = active == CreateStep.SPEC
        b.specChoices.isVisible = isActive
        b.specSummary.isVisible = done && !isActive
        b.specHints.isVisible = done && !isActive
        if (done && !isActive) {
            b.specTitle.text = getString(R.string.create_step_spec)
            b.specSummary.text = state.specName
            val rule = state.rules.firstOrNull {
                it.productId == state.productId && it.specId == state.specId && it.enabled
            }
            b.specHints.removeAllViews()
            if (rule != null) {
                b.specHints.addView(
                    TextView(requireContext()).apply {
                        setTextAppearance(R.style.TextAppearance_Jidan_Hint)
                        text = rule.name
                    },
                )
            } else {
                b.specHints.addView(tagline(getString(R.string.create_no_rule)))
            }
            if (state.prices[state.specId] == null) {
                b.specHints.addView(tagline(getString(R.string.create_no_base_price)))
            }
            b.stepSpec.setOnClickListener { vm.edit(CreateStep.SPEC) }
        } else {
            b.specTitle.text = getString(if (isActive) R.string.create_ask_spec else R.string.create_step_spec)
            b.stepSpec.setOnClickListener(null)
        }
        b.stepSpec.isClickable = done && !isActive
        if (isActive) {
            fillChoices(
                b.specChoices,
                state.specs.map { s ->
                    val ruled = state.rules.any { it.productId == state.productId && it.specId == s.id }
                    val noPrice = state.prices[s.id] == null
                    Choice(
                        label = s.name,
                        badge = when {
                            noPrice -> getString(R.string.create_no_base_price)
                            ruled -> getString(R.string.create_has_rule)
                            else -> null
                        },
                        warn = noPrice,
                        selected = s.id == state.specId,
                    ) { vm.selectSpec(s) }
                },
            )
        }
    }

    private fun renderWarehouseStep(b: FragmentCreateBinding, state: CreateUiState, active: CreateStep?) {
        val done = state.warehouseId != null
        val isActive = active == CreateStep.WAREHOUSE
        b.warehouseList.isVisible = isActive
        b.warehouseSummary.isVisible = done && !isActive
        if (done && !isActive) {
            b.warehouseTitle.text = getString(R.string.create_step_warehouse)
            b.warehouseSummary.text = state.warehouseName
            b.stepWarehouse.setOnClickListener { vm.edit(CreateStep.WAREHOUSE) }
        } else {
            b.warehouseTitle.text = getString(
                if (isActive) R.string.create_ask_warehouse else R.string.create_step_warehouse,
            )
            b.stepWarehouse.setOnClickListener(null)
        }
        b.stepWarehouse.isClickable = done && !isActive
        if (isActive) {
            b.warehouseList.removeAllViews()
            val lastId = requireContext().appContainer().prefs.lastWarehouseId
            state.warehouses.forEachIndexed { i, w ->
                if (i > 0) b.warehouseList.addView(divider())
                b.warehouseList.addView(
                    listRow(
                        parent = b.warehouseList,
                        main = bold(w.name),
                        side = if (w.id == lastId) getString(R.string.create_last_warehouse) else null,
                        selected = w.id == state.warehouseId,
                    ) { vm.selectWarehouse(w) },
                )
            }
        }
    }

    // ---------- 步骤条 ----------

    private enum class StepState { TODO, ACTIVE, DONE }

    private class StepViews(
        val card: LinearLayout,
        val rail: ViewStepRailBinding,
        val title: TextView,
        val chev: View,
    )

    private fun stepViews(b: FragmentCreateBinding) = listOf(
        StepViews(b.cardFarmer, b.railFarmer, b.farmerTitle, b.farmerChev),
        StepViews(b.cardProduct, b.railProduct, b.productTitle, b.productChev),
        StepViews(b.cardSpec, b.railSpec, b.specTitle, b.specChev),
        StepViews(b.cardWarehouse, b.railWarehouse, b.warehouseTitle, b.warehouseChev),
    )

    /**
     * 一步的三种样子（原型 .step[data-state]）：
     * 未做：灰圆标、一行灰标题；进行中：绿实心圆标、浅绿卡片展开；做完：绿圆标打勾、一行“小标签 + 大值 + ›”，点了改。
     * 圆标的上边距各不相同（14 / 18 / 20dp），竖线从圆标下沿连到下一步圆标，做完的一段是绿的。
     */
    private fun styleStep(v: StepViews, index: Int, state: StepState, last: Boolean, prevDone: Boolean) {
        val ctx = requireContext()
        val r = v.rail
        val circleTop = when (state) {
            StepState.TODO -> 14
            StepState.ACTIVE -> 18
            StepState.DONE -> 20
        }
        (r.railCircle.layoutParams as FrameLayout.LayoutParams).topMargin = dp(circleTop)
        r.railCircle.setBackgroundResource(
            if (state == StepState.TODO) R.drawable.bg_step_num_todo else R.drawable.bg_step_num_on,
        )
        r.railCircle.requestLayout()
        r.railNum.text = (index + 1).toString()
        r.railNum.isVisible = state != StepState.DONE
        r.railNum.setTextColor(
            ContextCompat.getColor(ctx, if (state == StepState.TODO) R.color.ink_3 else R.color.on_accent),
        )
        r.railCheck.isVisible = state == StepState.DONE

        r.railLineTop.isVisible = index > 0
        r.railLineTop.layoutParams.height = dp(circleTop)
        r.railLineTop.setBackgroundColor(ContextCompat.getColor(ctx, if (prevDone) R.color.accent else R.color.line))
        r.railLineBottom.isVisible = !last
        (r.railLineBottom.layoutParams as FrameLayout.LayoutParams).topMargin = dp(circleTop + 32)
        r.railLineBottom.setBackgroundColor(
            ContextCompat.getColor(ctx, if (state == StepState.DONE) R.color.accent else R.color.line),
        )
        r.railLineTop.requestLayout()
        r.railLineBottom.requestLayout()

        val lp = v.card.layoutParams as LinearLayout.LayoutParams
        when (state) {
            StepState.ACTIVE -> {
                v.card.background = ContextCompat.getDrawable(ctx, R.drawable.bg_step_active)
                v.card.setPadding(dp(14), dp(16), dp(16), dp(18))
                lp.topMargin = dp(8)
                lp.bottomMargin = dp(20)
                v.card.minimumHeight = 0
                v.card.gravity = Gravity.TOP
                v.title.setTextAppearance(R.style.TextAppearance_Jidan_StepActive)
                v.title.minHeight = dp(28)
                v.title.setPadding(0, 0, 0, dp(12))
            }
            StepState.DONE -> {
                v.card.background = null
                v.card.setPadding(dp(12), dp(12), 0, dp(12))
                lp.topMargin = 0
                lp.bottomMargin = dp(6)
                v.card.minimumHeight = dp(72)
                v.card.gravity = Gravity.CENTER_VERTICAL
                v.title.setTextAppearance(R.style.TextAppearance_Jidan_StepDone)
                v.title.minHeight = dp(18)
                v.title.setPadding(0, 0, 0, 0)
            }
            StepState.TODO -> {
                v.card.background = null
                v.card.setPadding(dp(12), 0, 0, 0)
                lp.topMargin = 0
                lp.bottomMargin = dp(6)
                v.card.minimumHeight = 0
                v.card.gravity = Gravity.TOP
                v.title.setTextAppearance(R.style.TextAppearance_Jidan_StepTodo)
                v.title.minHeight = dp(60)
                v.title.setPadding(0, 0, 0, 0)
            }
        }
        v.title.gravity = Gravity.CENTER_VERTICAL
        v.card.layoutParams = lp
        v.chev.isVisible = state == StepState.DONE
    }

    private fun divider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.line))
    }

    private fun tagline(text: String): View {
        val tv = layoutInflater.inflate(R.layout.view_tagline, null, false) as TextView
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = dp(4) }
        tv.text = text
        tv.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.hint_bg))
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.warn_ink))
        return tv
    }

    // ---------- 列表行、选项按钮 ----------

    private fun listRow(
        parent: LinearLayout,
        main: CharSequence,
        side: String?,
        selected: Boolean,
        onClick: () -> Unit,
    ): View {
        val row = ViewListRowBinding.inflate(layoutInflater, parent, false)
        row.rowMain.text = main
        row.rowSide.isVisible = side != null
        row.rowSide.text = side
        row.rowRoot.isSelected = selected
        row.rowRoot.setOnClickListener { onClick() }
        return row.root
    }

    private class Choice(
        val label: String,
        val badge: String?,
        val warn: Boolean,
        val selected: Boolean,
        val onClick: () -> Unit,
    )

    /** 一行放几个：3 个以内各占一格，多了两列；名字长的少放，免得挤成竖条（原型 segCols）。 */
    private fun choiceCols(labels: List<String>): Int {
        val longest = labels.maxOfOrNull { it.codePointCount(0, it.length) } ?: 0
        val cols = if (labels.size <= 3) labels.size else 2
        return when {
            longest > 10 -> 1
            longest > 5 -> minOf(cols, 2)
            else -> cols
        }.coerceAtLeast(1)
    }

    private fun fillChoices(host: LinearLayout, items: List<Choice>) {
        host.removeAllViews()
        val cols = choiceCols(items.map { it.label })
        items.chunked(cols).forEachIndexed { rowIdx, rowItems ->
            val line = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false   // 有的带“有规则”小字、有的不带，按基线对齐会把按钮错开
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { if (rowIdx > 0) it.topMargin = dp(8) }
            }
            for (col in 0 until cols) {
                val item = rowItems.getOrNull(col)
                val cell: View = if (item != null) {
                    choiceButton(item, line)
                } else {
                    Space(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
                }
                (cell.layoutParams as LinearLayout.LayoutParams).marginStart = if (col == 0) 0 else dp(8)
                line.addView(cell)
            }
            host.addView(line)
        }
    }

    private fun choiceButton(item: Choice, parent: LinearLayout): View {
        val btn = ViewChoiceButtonBinding.inflate(layoutInflater, parent, false).root
        val text = SpannableStringBuilder(item.label)
        if (!item.badge.isNullOrBlank()) {
            val start = text.length
            text.append('\n').append(item.badge)
            val tone = when {
                item.selected -> R.color.on_accent
                item.warn -> R.color.warn_ink
                else -> R.color.ok_ink
            }
            text.setSpan(RelativeSizeSpan(13f / 17f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(requireContext(), tone)),
                start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        btn.text = text
        btn.isSelected = item.selected
        btn.setOnClickListener { item.onClick() }
        return btn
    }

    // ---------- 文字 ----------

    /** 做完一步的值：名称大而粗（TextView 本身是 Text.Value），手机号在下一行，小一档、次级灰。 */
    private fun nameAndPhone(name: String, phone: String?): CharSequence {
        val out = SpannableStringBuilder(name)
        if (!phone.isNullOrBlank()) {
            out.append('\n')
            val start = out.length
            out.append(formatPhone(phone))
            subSpans(out, start, out.length, 15f / 19f, bold = false)
        }
        return out
    }

    /** 副行（手机号）：小一档、次级灰、Inter 数字；[bold] 为真时用墨色 Inter 粗体（匹配上的尾号）。 */
    private fun subSpans(out: SpannableStringBuilder, start: Int, end: Int, size: Float, bold: Boolean) {
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (size != 1f) out.setSpan(RelativeSizeSpan(size), start, end, flags)
        out.setSpan(StyleSpan(Typeface.NORMAL), start, end, flags)
        val color = if (bold) R.color.ink else R.color.ink_2
        out.setSpan(ForegroundColorSpan(ContextCompat.getColor(requireContext(), color)), start, end, flags)
        val font = ResourcesCompat.getFont(requireContext(), if (bold) R.font.inter_bold else R.font.inter_medium)
        if (font != null) out.setSpan(NumText.TypefaceSpanCompat(font), start, end, flags)
    }

    /** 农户行：名称大而粗，下面手机号；尾号搜到的把匹配上的后 4 位加粗，同名的人靠号码区分。 */
    private fun farmerRowText(f: FarmerDto, query: String): CharSequence {
        val phones = if (query.length == 4) {
            f.phones.filter { it.replace("\\D".toRegex(), "").endsWith(query) }
                .ifEmpty { f.matchedPhones.ifEmpty { f.phones.take(1) } }
        } else {
            f.phones.take(1)
        }
        val out = SpannableStringBuilder(f.name)
        out.setSpan(StyleSpan(Typeface.BOLD), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (phones.isNotEmpty()) {
            out.append('\n')
            val start = out.length
            phones.forEachIndexed { i, p ->
                if (i > 0) out.append("  ")
                val from = out.length
                val shown = formatPhone(p)
                out.append(shown)
                subSpans(out, from, out.length, 15f / 17f, bold = false)
                val matched = query.length == 4 && p.filter(Char::isDigit).endsWith(query)
                if (matched && shown.length >= 4) subSpans(out, out.length - 4, out.length, 1f, bold = true)
            }
            if (start == out.length) out.delete(start - 1, start)
        }
        return out
    }

    private fun bold(s: String): CharSequence =
        SpannableString(s).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    private fun formatPhone(p: String): String {
        val d = p.filter(Char::isDigit)
        return if (d.length == 11) "${d.substring(0, 3)} ${d.substring(3, 7)} ${d.substring(7)}" else p
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
