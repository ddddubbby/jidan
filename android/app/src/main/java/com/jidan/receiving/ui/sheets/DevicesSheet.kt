package com.jidan.receiving.ui.sheets

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jidan.receiving.R
import com.jidan.receiving.appContainer
import com.jidan.receiving.databinding.SheetDevicesBinding
import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrintPayload
import com.jidan.receiving.device.PrinterIssue
import com.jidan.receiving.device.label.LabelRenderer
import com.jidan.receiving.ui.devices.LinkActionKind
import com.jidan.receiving.ui.widgets.DeviceCardBinder
import kotlinx.coroutines.launch

class DevicesSheet : BottomSheetDialogFragment() {
    private var binding: SheetDevicesBinding? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* UI 会随 gate 重绘 */ }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetDevicesBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val c = requireContext().appContainer()
        val b = binding ?: return

        b.testPrint.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val outcome = c.printer.print(PrintPayload.Text(LabelRenderer.testTspl()))
                b.sheetMessage.text = when (outcome) {
                    is com.jidan.receiving.device.PrintOutcome.Success -> "测试标签已发送"
                    is com.jidan.receiving.device.PrintOutcome.Failure -> outcome.reason
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { c.scale.linkStatus.collect { refresh() } }
                launch { c.scale.pairedId.collect { refresh() } }
                launch { c.scale.batteryLow.collect { refresh() } }
                launch { c.scale.readings.collect { refresh() } }
                launch { c.printer.linkStatus.collect { refresh() } }
                launch { c.printer.pairedId.collect { refresh() } }
                launch { c.printer.printerStatus.collect { refresh() } }
            }
        }
        refresh()
    }

    private fun refresh() {
        val b = binding ?: return
        val c = requireContext().appContainer()
        val scaleModel = c.options.value.get("device.scale-model")
        val printerModel = c.options.value.get("device.printer-model")
        val reading = c.scale.readings.value
        b.scaleReading.text = if (reading != null) {
            "${reading.raw.format1()} kg"
        } else {
            "--.- kg"
        }

        DeviceCardBinder.bindScale(
            binding = b.scaleCard,
            gate = c.bluetoothGate,
            status = c.scale.linkStatus.value,
            pairedId = c.scale.pairedId.value,
            modelLabel = scaleModel,
            batteryLow = c.scale.batteryLow.value,
            onAction = { handle(it, scale = true) },
            onOpenSheet = {},
        )
        DeviceCardBinder.bindPrinter(
            binding = b.printerCard,
            gate = c.bluetoothGate,
            status = c.printer.linkStatus.value,
            pairedId = c.printer.pairedId.value,
            modelLabel = printerModel,
            issue = c.printer.printerStatus.value,
            onAction = { handle(it, scale = false) },
            onOpenSheet = {},
        )
        b.testPrint.isEnabled =
            c.printer.linkStatus.value == LinkStatus.CONNECTED &&
                c.printer.printerStatus.value == PrinterIssue.OK
    }

    private fun handle(action: LinkActionKind, scale: Boolean) {
        val c = requireContext().appContainer()
        when (action) {
            LinkActionKind.OPEN_BLUETOOTH ->
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            LinkActionKind.GRANT_PERMISSION ->
                permLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.BLUETOOTH_SCAN,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            LinkActionKind.SEARCH, LinkActionKind.RESEARCH ->
                viewLifecycleOwner.lifecycleScope.launch { showScan(scale) }
            LinkActionKind.CONNECTING, LinkActionKind.NONE -> Unit
        }
    }

    private suspend fun showScan(isScale: Boolean) {
        val b = binding ?: return
        val c = requireContext().appContainer()
        val list = if (isScale) c.scale.search() else c.printer.search()
        val host = if (isScale) b.scaleScan else b.printerScan
        host.removeAllViews()
        host.isVisible = true
        if (list.isEmpty()) {
            host.addView(TextView(requireContext()).apply {
                text = "附近没有设备"
                setTextColor(resources.getColor(R.color.ink_2, null))
            })
            return
        }
        for (d in list) {
            host.addView(scanRow(d, isScale))
        }
    }

    private fun scanRow(d: DiscoveredDevice, isScale: Boolean): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 12, 8, 12)
        }
        val label = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "${d.model} · ${d.id}  ${"▮".repeat(d.rssiBars.coerceIn(1, 3))}"
            setTextColor(resources.getColor(R.color.ink, null))
        }
        val btn = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "连接"
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    val c = requireContext().appContainer()
                    if (isScale) {
                        c.scale.connect(d.id)
                        c.linkKeeper.onScaleConnected(d.id)
                    } else {
                        c.printer.connect(d.id)
                        c.linkKeeper.onPrinterConnected(d.id)
                    }
                    binding?.scaleScan?.isVisible = false
                    binding?.printerScan?.isVisible = false
                    refresh()
                }
            }
        }
        row.addView(label)
        row.addView(btn)
        return row
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "DevicesSheet"
    }
}
