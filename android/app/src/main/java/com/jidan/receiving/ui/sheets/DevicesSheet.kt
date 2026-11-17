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
import com.jidan.receiving.databinding.ViewListRowBinding
import com.jidan.receiving.device.PrintOutcome
import com.jidan.receiving.ui.widgets.DeviceCardBinder
import com.jidan.receiving.ui.widgets.shortDeviceId
import com.jidan.receiving.ui.widgets.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DevicesSheet : JidanSheet() {
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

        bindHead(b.head, getString(R.string.devices_title))
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

        DeviceCardBinder.bindScaleRow(
            binding = b.scaleRow,
            gate = c.bluetoothGate,
            status = c.scale.linkStatus.value,
            pairedId = c.scale.pairedId.value,
            modelKey = scaleModel,
            batteryLow = c.scale.batteryLow.value,
            reading = reading?.raw?.format1(),
            onAction = { handle(it, scale = true) },
            searchedEmpty = scaleSearchedEmpty,
        )
        DeviceCardBinder.bindPrinterRow(
            binding = b.printerRow,
            gate = c.bluetoothGate,
            status = c.printer.linkStatus.value,
            pairedId = c.printer.pairedId.value,
            modelKey = printerModel,
            issue = c.printer.printerStatus.value,
            canTest = c.printer.linkStatus.value == LinkStatus.CONNECTED &&
                c.printer.printerStatus.value == PrinterIssue.OK,
            onAction = { handle(it, scale = false) },
            onTest = { testPrint() },
            searchedEmpty = printerSearchedEmpty,
        )
    }

    private fun testPrint() {
        val c = requireContext().appContainer()
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = c.printer.print(PrintPayload.Text(LabelRenderer.testTspl()))
            when (outcome) {
                is PrintOutcome.Success -> message("测试标签已发送", error = false)
                is PrintOutcome.Failure -> message(outcome.reason, error = true)
            }
        }
    }

    /** 一句话结果（测试标签发出去了、没连上）：没有内容时不占位；坏了才用红字。 */
    private fun message(text: String, error: Boolean) {
        val b = binding ?: return
        b.sheetMessage.isVisible = text.isNotBlank()
        b.sheetMessage.text = text
        b.sheetMessage.setTextColor(
            resources.getColor(if (error) R.color.err_ink else R.color.ink_2, null),
        )
    }

    private fun handle(action: LinkActionKind, scale: Boolean) {
        if (prompts.handle(action)) return
        when (action) {
            LinkActionKind.SEARCH, LinkActionKind.RESEARCH, LinkActionKind.NONE ->
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
        // 已连着的这台不再列出来（原型 scanList）：“换一台”要找的是别的
        val current = if (isScale) c.scale.pairedId.value.takeIf { c.scale.linkStatus.value == LinkStatus.CONNECTED }
        else c.printer.pairedId.value.takeIf { c.printer.linkStatus.value == LinkStatus.CONNECTED }
        val others = list.filter { it.id != current }
        if (others.isEmpty()) {
            shown.addView(hintText("附近没有${if (isScale) "秤" else "打印机"}"))
        } else {
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_card)
                clipToOutline = true
                dividerDrawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_hairline)
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = dp(8) }
            }
            for (d in others) card.addView(scanRow(d, isScale, card))
            shown.addView(card)
        }
        refresh()
    }

    private fun setScanning(isScale: Boolean, on: Boolean) {
        if (isScale) scaleScanning = on else printerScanning = on
    }

    private fun hintText(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(R.style.Text_Body)
        setTextColor(resources.getColor(R.color.ink_2, null))
        setPadding(0, dp(8), 0, 0)
    }

    /** 搜到的一台（原型 scanList 的 .row-btn）：信号格、型号 ··尾号、右边“连接”；整行可点。 */
    private fun scanRow(d: DiscoveredDevice, isScale: Boolean, parent: ViewGroup): View {
        val row = ViewListRowBinding.inflate(layoutInflater, parent, false)
        row.rowMain.text = "${d.model} ··${shortDeviceId(d.id)}"
        row.rowSide.isVisible = true
        row.rowSide.text = "连接"
        row.rowSide.setTextColor(resources.getColor(R.color.ok_ink, null))
        row.rowSide.setTypeface(row.rowSide.typeface, android.graphics.Typeface.BOLD)
        (row.rowRoot as LinearLayout).addView(signalBars(d.rssiBars.coerceIn(1, 3)), 0)
        row.rowRoot.setOnClickListener {
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
                if (ok) message("", error = false) else message("没连上，可以再点一次，或重新搜索", error = true)
                if (ok) {
                    if (isScale) c.linkKeeper.onScaleConnected(d.id) else c.linkKeeper.onPrinterConnected(d.id)
                    binding?.scaleScan?.isVisible = false
                    binding?.printerScan?.isVisible = false
                }
                refresh()
            }
        }
        return row.root
    }

    /** 信号强弱三格（原型 bars）：亮的墨色、暗的浅灰。 */
    private fun signalBars(on: Int): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.BOTTOM
            contentDescription = "信号 $on 格"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.marginEnd = dp(12) }
            val heights = intArrayOf(8, 13, 18)
            for (i in 0 until 3) {
                addView(
                    View(ctx).apply {
                        setBackgroundColor(resources.getColor(if (i < on) R.color.ink else R.color.grab, null))
                        layoutParams = LinearLayout.LayoutParams(dp(5), dp(heights[i])).also {
                            if (i > 0) it.marginStart = dp(3)
                        }
                    },
                )
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "DevicesSheet"
    }
}
