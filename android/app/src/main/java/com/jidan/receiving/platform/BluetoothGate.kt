package com.jidan.receiving.platform

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 蓝牙开关与授权的快照；界面据此决定“打开蓝牙 / 允许使用蓝牙”（APP-01）。 */
data class GateState(val bluetoothOn: Boolean, val permitted: Boolean) {
    val ready: Boolean get() = bluetoothOn && permitted
}

/**
 * 蓝牙开关与权限（开发规格 9.3：在 :app，不经过适配器）。
 * 模拟版可用 [forceReady] 绕过，便于无真蓝牙环境走查连接 UI。
 *
 * [state] 在三种时机刷新：蓝牙开关变化的广播（[register]）、回到前台（`MainActivity.onResume`，
 * 覆盖从系统设置或授权弹窗回来）、授权弹窗返回。
 */
class BluetoothGate(private val context: Context) {
    /** 模拟版设为 true：视为蓝牙已开且已授权。 */
    var forceReady: Boolean = false

    private val _state = MutableStateFlow(read())
    val state: StateFlow<GateState> = _state.asStateFlow()

    fun isBluetoothOn(): Boolean {
        if (forceReady) return true
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return adapter.isEnabled
    }

    fun hasPermission(): Boolean {
        if (forceReady) return true
        return requiredPermissions(Build.VERSION.SDK_INT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun ready(): Boolean = isBluetoothOn() && hasPermission()

    /** 还没授予的权限（授权弹窗只请求这些）。 */
    fun missingPermissions(): List<String> {
        if (forceReady) return emptyList()
        return requiredPermissions(Build.VERSION.SDK_INT).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** 重新读一遍并发布；没变化时 StateFlow 不会重复通知。 */
    fun refresh() {
        _state.value = read()
    }

    /** 监听系统蓝牙开关；注册一次即可，随进程存活。 */
    fun register() {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) = refresh()
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun read() = GateState(bluetoothOn = isBluetoothOn(), permitted = hasPermission())

    companion object {
        /**
         * Android 12（API 31）起搜索与连接要 BLUETOOTH_SCAN、BLUETOOTH_CONNECT；
         * 更早的版本搜索要定位权限（清单里只在 API 30 及以下声明）。
         */
        fun requiredPermissions(sdkInt: Int): List<String> =
            if (sdkInt >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
