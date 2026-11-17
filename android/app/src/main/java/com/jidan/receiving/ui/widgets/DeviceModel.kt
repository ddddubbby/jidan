package com.jidan.receiving.ui.widgets

/**
 * 设备行上的型号写法（原型 devLine：`LS-S2 ··A3F1`）。
 * 选项里存的是适配器键（`lanstar-ble`、`gainscha-gp-m32b`，开发规格 4.3），这里换成机身上印的短型号；
 * 不认识的键原样显示。蓝石秤的型号名取自原型与模拟版，未经实物验证。
 */
fun deviceModelName(optionKey: String): String = when (optionKey) {
    "lanstar-ble" -> "LS-S2"
    "gainscha-gp-m32b" -> "GP-M32B"
    else -> optionKey
}

/** 设备编号只露最后 4 位：蓝牙地址去掉冒号、横线后取尾 4 位，大写。 */
fun shortDeviceId(id: String): String = id.filter { it != ':' && it != '-' }.takeLast(4).uppercase()

/** “LS-S2 ··A3F1”。 */
fun deviceModelText(optionKey: String, pairedId: String): String =
    "${deviceModelName(optionKey)} ··${shortDeviceId(pairedId)}"
