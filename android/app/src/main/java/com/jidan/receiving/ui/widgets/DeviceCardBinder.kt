package com.jidan.receiving.ui.widgets

import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.jidan.receiving.R
import com.jidan.receiving.databinding.ViewDeviceCardBinding
import com.jidan.receiving.databinding.ViewDeviceLineBinding
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrinterIssue
import com.jidan.receiving.platform.BluetoothGate
import com.jidan.receiving.ui.devices.LinkActionKind
import com.jidan.receiving.ui.devices.label
import com.jidan.receiving.ui.devices.linkAction

object DeviceCardBinder {
    fun bindScale(
        binding: ViewDeviceCardBinding,
        gate: BluetoothGate,
        status: LinkStatus,
        pairedId: String?,
        modelLabel: String,
        batteryLow: Boolean,
        onAction: (LinkActionKind) -> Unit,
        onOpenSheet: () -> Unit,
        searchedEmpty: Boolean = false,
    ) {
        val action = linkAction(gate, status, pairedId, searchedEmpty)
        binding.cardTitle.text = buildString {
            append(modelLabel)
            if (!pairedId.isNullOrBlank()) append(" · ").append(pairedId)
        }
        binding.status.text = scaleStatusText(status, batteryLow)
        tintDot(binding.dot, status, batteryLow)
        bindAction(binding, action, isScale = true, onAction)
        binding.root.setOnClickListener { onOpenSheet() }
    }

    fun bindPrinter(
        binding: ViewDeviceCardBinding,
        gate: BluetoothGate,
        status: LinkStatus,
        pairedId: String?,
        modelLabel: String,
        issue: PrinterIssue,
        onAction: (LinkActionKind) -> Unit,
        onOpenSheet: () -> Unit,
        searchedEmpty: Boolean = false,
    ) {
        val action = linkAction(gate, status, pairedId, searchedEmpty)
        binding.cardTitle.text = buildString {
            append(modelLabel)
            if (!pairedId.isNullOrBlank()) append(" · ").append(pairedId)
        }
        binding.status.text = printerStatusText(status, issue)
        tintDot(binding.dot, status, warn = status == LinkStatus.CONNECTED && issue != PrinterIssue.OK)
        bindAction(binding, action, isScale = false, onAction)
        binding.root.setOnClickListener { onOpenSheet() }
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
            LinkStatus.CONNECTED -> if (batteryLow) Tone.WARN else Tone.OK
            LinkStatus.CONNECTING -> Tone.BUSY
            LinkStatus.LOST, LinkStatus.DISCONNECTED -> Tone.ERR
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
            LinkStatus.CONNECTED -> if (issue == PrinterIssue.OK) Tone.OK else Tone.WARN
            LinkStatus.CONNECTING -> Tone.BUSY
            LinkStatus.LOST -> Tone.ERR
            LinkStatus.DISCONNECTED -> Tone.WARN   // 打印机没连不拦称重，只提醒（v7 APP-01）
        }
        bindLine(
            binding, binding.root.context.getString(R.string.create_dev_printer), modelKey, pairedId,
            printerStatusText(status, issue), tone,
            linkAction(gate, status, pairedId, searchedEmpty), isScale = false, onAction, onOpenSheet,
        )
    }

    private enum class Tone { OK, WARN, ERR, BUSY }

    private fun bindLine(
        binding: ViewDeviceLineBinding,
        name: String,
        modelKey: String,
        pairedId: String?,
        statusText: String,
        tone: Tone,
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
            Tone.OK -> R.color.ok_ink
            Tone.WARN -> R.color.warn_ink
            Tone.ERR -> R.color.err_ink
            Tone.BUSY -> R.color.ink_2
        }
        binding.lineStatus.setTextColor(ContextCompat.getColor(ctx, ink))
        val dot = when (tone) {
            Tone.OK -> R.color.accent
            Tone.BUSY -> R.color.faint
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

    private fun bindAction(
        binding: ViewDeviceCardBinding,
        action: LinkActionKind,
        isScale: Boolean,
        onAction: (LinkActionKind) -> Unit,
    ) {
        if (action == LinkActionKind.NONE) {
            binding.cardAction.isVisible = false
            return
        }
        binding.cardAction.isVisible = true
        binding.cardAction.isEnabled = action != LinkActionKind.CONNECTING
        binding.cardAction.text = action.label(isScale)
        binding.cardAction.setOnClickListener { onAction(action) }
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

    private fun tintDot(dot: View, status: LinkStatus, warn: Boolean = false) {
        val color = when {
            status == LinkStatus.CONNECTED && warn -> R.color.warn_ink
            status == LinkStatus.CONNECTED -> R.color.ok_ink
            status == LinkStatus.CONNECTING -> R.color.warn_ink
            status == LinkStatus.LOST -> R.color.err_ink
            else -> R.color.edge
        }
        dot.background.setTint(ContextCompat.getColor(dot.context, color))
    }
}
