package com.jidan.receiving.ui.widgets

import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.jidan.receiving.R
import com.jidan.receiving.databinding.ViewDeviceCardBinding
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
