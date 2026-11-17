package com.jidan.receiving.ui.create

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.jidan.receiving.R
import com.jidan.receiving.SimConsoleHook
import com.jidan.receiving.appContainer
import com.jidan.receiving.data.NamedDto
import com.jidan.receiving.data.FarmerDto
import com.jidan.receiving.databinding.FragmentCreateBinding
import com.jidan.receiving.platform.BluetoothPrompts
import com.jidan.receiving.ui.sheets.DevicesSheet
import com.jidan.receiving.ui.update.bindUpdateEntry
import com.jidan.receiving.ui.widgets.DeviceCardBinder
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
            DeviceCardBinder.bindScale(
                binding = b.scaleCard,
                gate = c.bluetoothGate,
                status = c.scale.linkStatus.value,
                pairedId = c.scale.pairedId.value,
                modelLabel = c.options.value.get("device.scale-model"),
                batteryLow = c.scale.batteryLow.value,
                onAction = { act -> if (!prompts.handle(act)) openSheet() },
                onOpenSheet = ::openSheet,
            )
            DeviceCardBinder.bindPrinter(
                binding = b.printerCard,
                gate = c.bluetoothGate,
                status = c.printer.linkStatus.value,
                pairedId = c.printer.pairedId.value,
                modelLabel = c.options.value.get("device.printer-model"),
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
                findNavController().navigate(R.id.action_global_to_login)
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
        renderFarmerStep(b, state, active)
        renderProductStep(b, state, active)
        renderSpecStep(b, state, active)
        renderWarehouseStep(b, state, active)

        b.action.isEnabled = true
        if (state.ready) {
            b.action.text = getString(R.string.create_start)
            b.action.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent))
            b.action.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_accent))
        } else {
            b.action.text = getString(R.string.create_missing, state.missing.size)
            b.action.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.warn_bg))
            b.action.setTextColor(ContextCompat.getColor(requireContext(), R.color.warn_ink))
        }
    }

    private fun renderFarmerStep(b: FragmentCreateBinding, state: CreateUiState, active: CreateStep?) {
        val done = state.farmerId != null
        val isActive = active == CreateStep.FARMER
        styleStep(b.stepFarmer, isActive)
        b.farmerBody.isVisible = isActive
        b.farmerSummary.isVisible = done && !isActive
        if (done && !isActive) {
            b.farmerTitle.text = getString(R.string.create_step_farmer)
            b.farmerSummary.text = buildString {
                append(state.farmerName.orEmpty())
                val phone = state.farmerPhone
                if (!phone.isNullOrBlank()) append('\n').append(formatPhone(phone))
            }
            b.stepFarmer.setOnClickListener { vm.edit(CreateStep.FARMER) }
        } else {
            b.farmerTitle.text = getString(R.string.create_ask_farmer)
            b.stepFarmer.setOnClickListener(null)
        }

        b.noMatch.isVisible = state.noMatch.isNotBlank() && state.query.isEmpty()
        if (state.noMatch.isNotBlank()) {
            b.noMatch.text = getString(R.string.create_no_match, state.noMatch)
        }

        b.farmerList.removeAllViews()
        val showRecent = isActive && state.query.isEmpty() && state.recent.isNotEmpty()
        b.recentLabel.isVisible = showRecent
        val rows: List<FarmerDto> = when {
            !isActive -> emptyList()
            state.query.length == 4 -> state.farmers
            state.query.isEmpty() -> state.recent
            else -> emptyList()
        }
        rows.forEach { f ->
            b.farmerList.addView(farmerRow(f, state.query))
        }
    }

    private fun renderProductStep(b: FragmentCreateBinding, state: CreateUiState, active: CreateStep?) {
        val done = state.productId != null
        val isActive = active == CreateStep.PRODUCT
        styleStep(b.stepProduct, isActive)
        b.productChoices.isVisible = isActive
        b.productSummary.isVisible = done && !isActive
        if (done && !isActive) {
            b.productTitle.text = getString(R.string.create_step_product)
            b.productSummary.text = state.productName
            b.stepProduct.setOnClickListener { vm.edit(CreateStep.PRODUCT) }
        } else {
            b.productTitle.text = if (isActive) {
                getString(R.string.create_ask_product)
            } else {
                getString(R.string.create_step_product)
            }
            b.stepProduct.setOnClickListener(null)
        }
        if (isActive) {
            b.productChoices.removeAllViews()
            val ruled = state.rules.map { it.productId }.toSet()
            state.products.forEach { p ->
                b.productChoices.addView(
                    choiceButton(
                        label = p.name,
                        badge = if (p.id in ruled) getString(R.string.create_has_rule) else null,
                        selected = p.id == state.productId,
                    ) { vm.selectProduct(p) },
                )
            }
        }
    }

    private fun renderSpecStep(b: FragmentCreateBinding, state: CreateUiState, active: CreateStep?) {
        val done = state.specId != null
        val isActive = active == CreateStep.SPEC
        styleStep(b.stepSpec, isActive)
        b.specChoices.isVisible = isActive
        b.specSummary.isVisible = done && !isActive
        b.specHints.isVisible = done && !isActive
        if (done && !isActive) {
            b.specTitle.text = getString(R.string.create_step_spec)
            b.specSummary.text = state.specName
            val hasRule = state.rules.any {
                it.productId == state.productId && it.specId == state.specId && it.enabled
            }
            val price = state.prices[state.specId]
            val hints = buildList {
                add(if (hasRule) {
                    state.rules.first {
                        it.productId == state.productId && it.specId == state.specId
                    }.name
                } else {
                    getString(R.string.create_no_rule)
                })
                if (price == null) add(getString(R.string.create_no_base_price))
            }
            b.specHints.text = hints.joinToString(" · ")
            b.stepSpec.setOnClickListener { vm.edit(CreateStep.SPEC) }
        } else {
            b.specTitle.text = if (isActive) {
                getString(R.string.create_ask_spec)
            } else {
                getString(R.string.create_step_spec)
            }
            b.stepSpec.setOnClickListener(null)
        }
        if (isActive) {
            b.specChoices.removeAllViews()
            state.specs.forEach { s ->
                val ruled = state.rules.any {
                    it.productId == state.productId && it.specId == s.id
                }
                val price = state.prices[s.id]
                val badge = when {
                    price == null -> getString(R.string.create_no_base_price)
                    ruled -> getString(R.string.create_has_rule)
                    else -> null
                }
                b.specChoices.addView(
                    choiceButton(
                        label = s.name,
                        badge = badge,
                        selected = s.id == state.specId,
                    ) { vm.selectSpec(s) },
                )
            }
        }
    }

    private fun renderWarehouseStep(b: FragmentCreateBinding, state: CreateUiState, active: CreateStep?) {
        val done = state.warehouseId != null
        val isActive = active == CreateStep.WAREHOUSE
        styleStep(b.stepWarehouse, isActive)
        b.warehouseList.isVisible = isActive
        b.warehouseSummary.isVisible = done && !isActive
        if (done && !isActive) {
            b.warehouseTitle.text = getString(R.string.create_step_warehouse)
            b.warehouseSummary.text = state.warehouseName
            b.stepWarehouse.setOnClickListener { vm.edit(CreateStep.WAREHOUSE) }
        } else {
            b.warehouseTitle.text = if (isActive) {
                getString(R.string.create_ask_warehouse)
            } else {
                getString(R.string.create_step_warehouse)
            }
            b.stepWarehouse.setOnClickListener(null)
        }
        if (isActive) {
            b.warehouseList.removeAllViews()
            val lastId = requireContext().appContainer().prefs.lastWarehouseId
            state.warehouses.forEach { w ->
                b.warehouseList.addView(warehouseRow(w, w.id == lastId, w.id == state.warehouseId))
            }
        }
    }

    private fun styleStep(view: View, active: Boolean) {
        view.background = ContextCompat.getDrawable(
            requireContext(),
            if (active) R.drawable.bg_step_active else R.drawable.bg_step_card,
        )
    }

    private fun farmerRow(f: FarmerDto, query: String): View {
        val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = dp(8) }
        btn.isAllCaps = false
        btn.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        val phones = if (query.length == 4) {
            f.phones.filter { it.replace("\\D".toRegex(), "").endsWith(query) }
                .ifEmpty { f.matchedPhones.ifEmpty { f.phones.take(1) } }
        } else {
            f.phones.take(1)
        }
        btn.text = buildString {
            append(f.name)
            if (phones.isNotEmpty()) append("\n").append(phones.joinToString(" ") { formatPhone(it) })
        }
        btn.setOnClickListener { vm.selectFarmer(f) }
        return btn
    }

    private fun warehouseRow(w: NamedDto, last: Boolean, selected: Boolean): View {
        val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = dp(8) }
        btn.isAllCaps = false
        btn.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        btn.text = buildString {
            append(w.name)
            if (last) append("\n").append(getString(R.string.create_last_warehouse))
        }
        if (selected) btn.strokeWidth = 3
        btn.setOnClickListener { vm.selectWarehouse(w) }
        return btn
    }

    private fun choiceButton(
        label: String,
        badge: String?,
        selected: Boolean,
        onClick: () -> Unit,
    ): View {
        val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = dp(8) }
        btn.isAllCaps = false
        btn.minHeight = resources.getDimensionPixelSize(R.dimen.button_height)
        btn.text = if (badge.isNullOrBlank()) label else "$label\n$badge"
        if (selected) btn.strokeWidth = 3
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun formatPhone(p: String): String {
        val d = p.filter(Char::isDigit)
        return if (d.length == 11) "${d.substring(0, 3)} ${d.substring(3, 7)} ${d.substring(7)}" else p
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
