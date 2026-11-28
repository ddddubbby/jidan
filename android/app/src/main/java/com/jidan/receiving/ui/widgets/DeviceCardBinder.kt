package com.jidan.receiving.ui.widgets

import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.jidan.receiving.R
import com.jidan.receiving.databinding.ViewDeviceRowBinding
import com.jidan.receiving.databinding.ViewDeviceLineBinding
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrinterIssue
import com.jidan.receiving.platform.BluetoothGate
import com.jidan.receiving.ui.devices.LinkActionKind
import com.jidan.receiving.ui.devices.label
import com.jidan.receiving.ui.devices.linkAction
import com.jidan.receiving.ui.devices.rowLabel

object DeviceCardBinder {
    /**
     * 设备弹层里的一台设备（原型 devSection 的 .dev）：● 型号 ··尾号；秤连上后显示实时读数，打印机有问题时挂黄色小标；
     * 右边只有一个连接操作（v7 APP-01）：缺什么就是什么，连上了就是“换一台”。
     */
    fun bindScaleRow(
        binding: ViewDeviceRowBinding,
        gate: BluetoothGate,
        status: LinkStatus,
        pairedId: String?,
        modelKey: String,
        batteryLow: Boolean,
        reading: String?,
        onAction: (LinkActionKind) -> Unit,
        searchedEmpty: Boolean = false,
    ) {
        val connected = status == LinkStatus.CONNECTED
        bindRow(
            binding, modelKey, pairedId, status,
            statusText = scaleStatusText(status, batteryLow = false),
            tone = rowTone(status),
            action = linkAction(gate, status, pairedId, searchedEmpty), isScale = true, onAction,
        )
        // 连上后显示这台秤的实时读数：按一下自己的秤盘，数字跟着动，就是连对了
        binding.devReading.isVisible = connected
        binding.devStatus.isVisible = !connected
        if (connected) binding.devReading.text = NumText.row(binding.root.context, reading ?: "--.-", "斤")
        binding.devTag.isVisible = connected && batteryLow
        binding.devTag.text = "电量低"
    }

    fun bindPrinterRow(
        binding: ViewDeviceRowBinding,
        gate: BluetoothGate,
        status: LinkStatus,
        pairedId: String?,
        modelKey: String,
        issue: PrinterIssue,
        canTest: Boolean,
        onAction: (LinkActionKind) -> Unit,
        onTest: () -> Unit,
        searchedEmpty: Boolean = false,
    ) {
        val connected = status == LinkStatus.CONNECTED
        bindRow(
            binding, modelKey, pairedId, status,
            statusText = printerStatusText(status, issue),
            tone = if (connected && issue != PrinterIssue.OK) StatusTone.WARN else rowTone(status),
            action = linkAction(gate, status, pairedId, searchedEmpty), isScale = false, onAction,
        )
        binding.devReading.isVisible = false
        // 缺纸、开盖、过热写成黄色小标，状态文字只在没问题时写“就绪”
        val hasIssue = connected && issue != PrinterIssue.OK
        binding.devStatus.isVisible = !hasIssue
        binding.devTag.isVisible = hasIssue
        binding.devTag.text = printerStatusText(status, issue)
        binding.devTest.isVisible = connected
        binding.devTest.isEnabled = canTest
        binding.devTest.setOnClickListener { onTest() }
    }

    private fun rowTone(status: LinkStatus): StatusTone = when (status) {
        LinkStatus.CONNECTED -> StatusTone.OK
        LinkStatus.CONNECTING -> StatusTone.BUSY
        LinkStatus.LOST -> StatusTone.ERR
        LinkStatus.DISCONNECTED -> StatusTone.BUSY   // 没连过不是故障，不用红
    }

    private fun bindRow(
        binding: ViewDeviceRowBinding,
        modelKey: String,
        pairedId: String?,
        status: LinkStatus,
        statusText: String,
        tone: StatusTone,
        action: LinkActionKind,
        isScale: Boolean,
        onAction: (LinkActionKind) -> Unit,
    ) {
        val ctx = binding.root.context
        binding.devName.text = if (pairedId.isNullOrBlank()) ctx.getString(R.string.devices_unpaired) else deviceModelText(modelKey, pairedId)
        binding.devDot.background.mutate().setTint(
            ContextCompat.getColor(ctx, if (status == LinkStatus.CONNECTED) R.color.accent else if (tone == StatusTone.ERR) R.color.err_ink else R.color.faint),
        )
        binding.devStatus.text = statusText
        binding.devStatus.setTextColor(ContextCompat.getColor(ctx, tone.ink))
        binding.devAction.isEnabled = action != LinkActionKind.CONNECTING
        binding.devAction.text = action.rowLabel(isScale)
        binding.devAction.setOnClickListener { onAction(action) }
        binding.devTest.isVisible = false
    }

    /** 建单页底部设备卡里的一行（原型 devLine）：● 名称 型号 ··尾号 状态；缺什么时右侧多一个操作按钮。 */
    fun bindScaleLine(
        binding: ViewDeviceLineBinding,
        gate: BluetoothGate,
        status: LinkStatus,
        pairedId: String?,
        modelKey: String,
        batteryLow: Boolean,
        onAction: (LinkActionKind) -> Unit,
        onOpenSheet: () -> Unit,
        searchedEmpty: Boolean = false,
    ) {
        val tone = when (status) {
            LinkStatus.CONNECTED -> if (batteryLow) StatusTone.WARN else StatusTone.OK
            LinkStatus.CONNECTING -> StatusTone.BUSY
            LinkStatus.LOST, LinkStatus.DISCONNECTED -> StatusTone.ERR
        }
        bindLine(
            binding, binding.root.context.getString(R.string.create_dev_scale), modelKey, pairedId,
            scaleStatusText(status, batteryLow), tone,
            linkAction(gate, status, pairedId, searchedEmpty), isScale = true, onAction, onOpenSheet,
        )
    }

    fun bindPrinterLine(
        binding: ViewDeviceLineBinding,
        gate: BluetoothGate,
        status: LinkStatus,
        pairedId: String?,
        modelKey: String,
        issue: PrinterIssue,
        onAction: (LinkActionKind) -> Unit,
        onOpenSheet: () -> Unit,
        searchedEmpty: Boolean = false,
    ) {
        val tone = when (status) {
            LinkStatus.CONNECTED -> if (issue == PrinterIssue.OK) StatusTone.OK else StatusTone.WARN
            LinkStatus.CONNECTING -> StatusTone.BUSY
            LinkStatus.LOST -> StatusTone.ERR
            LinkStatus.DISCONNECTED -> StatusTone.WARN   // 打印机没连不拦称重，只提醒（v7 APP-01）
        }
        bindLine(
            binding, binding.root.context.getString(R.string.create_dev_printer), modelKey, pairedId,
            printerStatusText(status, issue), tone,
            linkAction(gate, status, pairedId, searchedEmpty), isScale = false, onAction, onOpenSheet,
        )
    }

    private fun bindLine(
        binding: ViewDeviceLineBinding,
        name: String,
        modelKey: String,
        pairedId: String?,
        statusText: String,
        tone: StatusTone,
        action: LinkActionKind,
        isScale: Boolean,
        onAction: (LinkActionKind) -> Unit,
        onOpenSheet: () -> Unit,
    ) {
        val ctx = binding.root.context
        binding.lineName.text = name
        binding.lineModel.text = if (pairedId.isNullOrBlank()) "" else deviceModelText(modelKey, pairedId)
        binding.lineStatus.text = statusText
        val ink = when (tone) {
            StatusTone.OK -> R.color.ok_ink
            StatusTone.WARN -> R.color.warn_ink
            StatusTone.ERR -> R.color.err_ink
            StatusTone.BUSY -> R.color.ink_2
        }
        binding.lineStatus.setTextColor(ContextCompat.getColor(ctx, ink))
        val dot = when (tone) {
            StatusTone.OK -> R.color.accent
            StatusTone.BUSY -> R.color.faint
            else -> ink
        }
        binding.lineDot.background.mutate().setTint(ContextCompat.getColor(ctx, dot))
        binding.lineAction.isVisible = action != LinkActionKind.NONE
        if (action != LinkActionKind.NONE) {
            binding.lineAction.isEnabled = action != LinkActionKind.CONNECTING
            binding.lineAction.text = action.label(isScale)
            binding.lineAction.setOnClickListener { onAction(action) }
        }
        binding.lineRoot.setOnClickListener { onOpenSheet() }
    }

    private fun scaleStatusText(status: LinkStatus, batteryLow: Boolean): String = when (status) {
        LinkStatus.CONNECTED -> if (batteryLow) "电量低" else "已连接"
        LinkStatus.CONNECTING -> "连接中"
        LinkStatus.LOST -> "已断开"
        LinkStatus.DISCONNECTED -> "未连接"
    }

    private fun printerStatusText(status: LinkStatus, issue: PrinterIssue): String = when (status) {
        LinkStatus.CONNECTED -> when (issue) {
            PrinterIssue.OK -> "就绪"
            PrinterIssue.NO_PAPER -> "缺纸"
            PrinterIssue.OPEN_COVER -> "开盖"
            PrinterIssue.OVERHEAT -> "过热"
        }
        LinkStatus.CONNECTING -> "连接中"
        LinkStatus.LOST -> "已断开"
        LinkStatus.DISCONNECTED -> "未连接"
    }
}
