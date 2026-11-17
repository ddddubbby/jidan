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

/**
 * [searchedEmpty]：刚搜过一次、附近没有这类设备。这时即使没配对过，下一步也是“重新搜索”，
 * 不再是“搜索秤”（原型 scanList 空结果分支）。
 */
fun linkAction(
    bluetoothOn: Boolean,
    permitted: Boolean,
    status: LinkStatus,
    pairedId: String?,
    searchedEmpty: Boolean = false,
): LinkActionKind = when {
    !bluetoothOn -> LinkActionKind.OPEN_BLUETOOTH
    !permitted -> LinkActionKind.GRANT_PERMISSION
    status == LinkStatus.CONNECTING -> LinkActionKind.CONNECTING
    status == LinkStatus.CONNECTED -> LinkActionKind.NONE
    searchedEmpty -> LinkActionKind.RESEARCH
    pairedId.isNullOrBlank() -> LinkActionKind.SEARCH
    else -> LinkActionKind.RESEARCH
}

fun linkAction(
    gate: BluetoothGate,
    status: LinkStatus,
    pairedId: String?,
    searchedEmpty: Boolean = false,
): LinkActionKind =
    linkAction(gate.isBluetoothOn(), gate.hasPermission(), status, pairedId, searchedEmpty)

fun LinkActionKind.label(isScale: Boolean): String = when (this) {
    LinkActionKind.OPEN_BLUETOOTH -> "打开蓝牙"
    LinkActionKind.GRANT_PERMISSION -> "允许使用蓝牙"
    LinkActionKind.CONNECTING -> "连接中"
    LinkActionKind.SEARCH -> if (isScale) "搜索秤" else "搜索打印机"
    LinkActionKind.RESEARCH -> "重新搜索"
    LinkActionKind.NONE -> ""
}

/**
 * 设备弹层里那台设备右边的唯一操作：缺什么写什么；已连上、没有缺的时就是“换一台”（原型 devSection），点了去搜索。
 */
fun LinkActionKind.rowLabel(isScale: Boolean): String =
    if (this == LinkActionKind.NONE) "换一台" else label(isScale)
