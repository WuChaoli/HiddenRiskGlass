package com.rokid.glass.utils

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager

/**
 * Created by wjm on 2025/6/23
 */
object SystemStateUtils {

    /**
     * 检查 Wi-Fi 是否已启用
     *
     * @param context 上下文
     * @return Wi-Fi 已启用返回 true，否则返回 false
     */
    @JvmStatic
    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    /**
     * 检查蓝牙是否已启用
     *
     * @return 蓝牙已启用返回 true，否则返回 false
     */
    @JvmStatic
    fun isBluetoothEnabled(): Boolean {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return bluetoothAdapter?.isEnabled == true
    }
}