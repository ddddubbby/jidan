package com.jidan.receiving.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙开关与权限（开发规格 9.3：在 :app，不经过适配器）。
 * 模拟版可用 [forceReady] 绕过，便于无真蓝牙环境走查连接 UI。
 */
class BluetoothGate(private val context: Context) {
    /** 模拟版设为 true：视为蓝牙已开且已授权。 */
    var forceReady: Boolean = false

    fun isBluetoothOn(): Boolean {
        if (forceReady) return true
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return adapter.isEnabled
    }

    fun hasPermission(): Boolean {
        if (forceReady) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun ready(): Boolean = isBluetoothOn() && hasPermission()
}
