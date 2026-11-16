package com.jidan.receiving.ui.devices

import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.platform.BluetoothGate

/** 原型 linkAction：任何时候每台设备只给一个主操作。 */
enum class LinkActionKind {
    OPEN_BLUETOOTH,
    GRANT_PERMISSION,
    CONNECTING,
    SEARCH,
    RESEARCH,
    NONE,
}

fun linkAction(
    gate: BluetoothGate,
    status: LinkStatus,
    pairedId: String?,
): LinkActionKind = when {
    !gate.isBluetoothOn() -> LinkActionKind.OPEN_BLUETOOTH
    !gate.hasPermission() -> LinkActionKind.GRANT_PERMISSION
    status == LinkStatus.CONNECTING -> LinkActionKind.CONNECTING
    status == LinkStatus.CONNECTED -> LinkActionKind.NONE
    pairedId.isNullOrBlank() -> LinkActionKind.SEARCH
    else -> LinkActionKind.RESEARCH
}

fun LinkActionKind.label(isScale: Boolean): String = when (this) {
    LinkActionKind.OPEN_BLUETOOTH -> "打开蓝牙"
    LinkActionKind.GRANT_PERMISSION -> "允许使用蓝牙"
    LinkActionKind.CONNECTING -> "连接中"
    LinkActionKind.SEARCH -> if (isScale) "搜索秤" else "搜索打印机"
    LinkActionKind.RESEARCH -> "重新搜索"
    LinkActionKind.NONE -> ""
}
