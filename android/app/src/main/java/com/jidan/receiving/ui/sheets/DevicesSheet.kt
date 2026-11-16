package com.jidan.receiving.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
import com.jidan.receiving.platform.BluetoothPrompts
import com.jidan.receiving.ui.devices.LinkActionKind
import com.jidan.receiving.ui.widgets.DeviceCardBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DevicesSheet : BottomSheetDialogFragment() {
    private var binding: SheetDevicesBinding? = null

    private val prompts = BluetoothPrompts(this)

    /** 刚搜过一次、附近没有这类设备：这台设备的下一步从“搜索”变成“重新搜索”。 */
    private var scaleSearchedEmpty = false
    private var printerSearchedEmpty = false
    private var scaleScanning = false
    private var printerScanning = false

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
                launch { c.bluetoothGate.state.collect { refresh() } }
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
            searchedEmpty = scaleSearchedEmpty,
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
            searchedEmpty = printerSearchedEmpty,
        )
        b.testPrint.isEnabled =
            c.printer.linkStatus.value == LinkStatus.CONNECTED &&
                c.printer.printerStatus.value == PrinterIssue.OK
    }

    private fun handle(action: LinkActionKind, scale: Boolean) {
        if (prompts.handle(action)) return
        when (action) {
            LinkActionKind.SEARCH, LinkActionKind.RESEARCH ->
                viewLifecycleOwner.lifecycleScope.launch { showScan(scale) }
            else -> Unit
        }
    }

    private suspend fun showScan(isScale: Boolean) {
        val b = binding ?: return
        val c = requireContext().appContainer()
        val host = if (isScale) b.scaleScan else b.printerScan
        // 蓝牙没开或没授权时不碰适配器（SDK 在这两种情况下的表现没验证过）；按钮本来也只会是“打开蓝牙”“允许使用蓝牙”
        if (!c.bluetoothGate.ready()) {
            host.isVisible = false
            return
        }
        if (if (isScale) scaleScanning else printerScanning) return
        setScanning(isScale, true)
        host.removeAllViews()
        host.isVisible = true
        host.addView(hintText("正在搜索附近的${if (isScale) "秤" else "打印机"}…"))
        val list = try {
            if (isScale) c.scale.search() else c.printer.search()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList() // 搜索出错当作没搜到，给“重新搜索”
        } finally {
            setScanning(isScale, false)
        }
        val view = binding ?: return
        val shown = if (isScale) view.scaleScan else view.printerScan
        shown.removeAllViews()
        if (isScale) scaleSearchedEmpty = list.isEmpty() else printerSearchedEmpty = list.isEmpty()
        if (list.isEmpty()) {
            shown.addView(hintText("附近没有${if (isScale) "秤" else "打印机"}"))
        } else {
            for (d in list) shown.addView(scanRow(d, isScale))
        }
        refresh()
    }

    private fun setScanning(isScale: Boolean, on: Boolean) {
        if (isScale) scaleScanning = on else printerScanning = on
    }

    private fun hintText(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextColor(resources.getColor(R.color.ink_2, null))
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
                    // 连接失败时不记住这台，也不收起列表：留在列表里可以再点，或点“重新搜索”
                    val ok = try {
                        if (isScale) {
                            c.scale.connect(d.id)
                            c.scale.linkStatus.value == LinkStatus.CONNECTED
                        } else {
                            c.printer.connect(d.id)
                            c.printer.linkStatus.value == LinkStatus.CONNECTED
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    binding?.sheetMessage?.text = if (ok) "" else "没连上，可以再点一次，或重新搜索"
                    if (ok) {
                        if (isScale) c.linkKeeper.onScaleConnected(d.id) else c.linkKeeper.onPrinterConnected(d.id)
                        binding?.scaleScan?.isVisible = false
                        binding?.printerScan?.isVisible = false
                    }
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
