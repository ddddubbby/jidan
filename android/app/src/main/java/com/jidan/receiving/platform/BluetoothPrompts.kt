package com.jidan.receiving.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.jidan.receiving.appContainer
import com.jidan.receiving.ui.devices.LinkActionKind

/**
 * “打开蓝牙”“允许使用蓝牙”两个按钮点了之后做什么（APP-01：按钮点了直达，不再多一步）。
 * 必须作为 Fragment 的属性创建（授权弹窗的回调要在 Fragment 启动前注册）。
 *
 * - 打开蓝牙：跳系统蓝牙设置。不用 `ACTION_REQUEST_ENABLE`：它在 Android 12 起要先有
 *   BLUETOOTH_CONNECT，没授权时会抛 SecurityException；而“打开蓝牙”恰恰排在“允许使用蓝牙”之前。
 * - 允许使用蓝牙：弹系统授权框。被选了“不再询问”之后系统不再弹框，这时改跳本应用的系统设置页。
 *
 * 未经实物验证（没有真手机）。
 */
class BluetoothPrompts(private val fragment: Fragment) {
    private val permLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val gate = fragment.requireContext().appContainer().bluetoothGate
        gate.refresh()
        val missing = gate.missingPermissions()
        if (missing.isNotEmpty() && missing.none { fragment.shouldShowRequestPermissionRationale(it) }) {
            openAppSettings()
        }
    }

    /** 处理“打开蓝牙”“允许使用蓝牙”；其余动作不归这里，返回 false。 */
    fun handle(action: LinkActionKind): Boolean = when (action) {
        LinkActionKind.OPEN_BLUETOOTH -> {
            openBluetoothSettings()
            true
        }
        LinkActionKind.GRANT_PERMISSION -> {
            val gate = fragment.requireContext().appContainer().bluetoothGate
            val missing = gate.missingPermissions()
            if (missing.isEmpty()) gate.refresh() else permLauncher.launch(missing.toTypedArray())
            true
        }
        else -> false
    }

    private fun openBluetoothSettings() {
        val ctx = fragment.requireContext()
        try {
            ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun openAppSettings() {
        val ctx = fragment.requireContext()
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
            )
        }
    }
}
