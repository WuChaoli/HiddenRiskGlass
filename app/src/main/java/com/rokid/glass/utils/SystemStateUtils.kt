package com.rokid.glass.utils

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.rokid.glesse.R

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
     * 获取当前已连接 Wi-Fi 的 SSID。
     *
     * @return 成功返回去引号后的 SSID，未连接时返回 null
     */
    @JvmStatic
    fun getCurrentWifiSsid(context: Context): String? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val wifiInfoFromCapabilities = connectivityManager
            ?.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?.transportInfo as? WifiInfo
        val capabilitySsid = sanitizeSsid(wifiInfoFromCapabilities?.ssid)
        if (capabilitySsid != null) {
            return capabilitySsid
        }

        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        return sanitizeSsid(wifiManager?.connectionInfo?.ssid)
    }

    /**
     * 判断系统当前网络是否可用于远程识别。
     * 优先使用系统验证过的网络能力，部分眼镜固件拿不到 VALIDATED 时回退到 Wi-Fi 连接状态。
     */
    @JvmStatic
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivityManager
            ?.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return getCurrentWifiSsid(context) != null

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val hasKnownTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return (hasInternet && (validated || hasKnownTransport)) || getCurrentWifiSsid(context) != null
    }

    /**
     * 获取当前 Wi-Fi 状态对应的图标资源。
     */
    @JvmStatic
    fun getWifiStatusIconRes(context: Context): Int {
        if (!isWifiEnabled(context)) {
            return R.mipmap.status_wifi_close
        }

        val wifiInfo = getCurrentWifiInfo(context)
        val ssid = sanitizeSsid(wifiInfo?.ssid)
        if (ssid == null) {
            return R.mipmap.status_wifi_un_connect
        }

        val level = WifiManager.calculateSignalLevel(wifiInfo?.rssi ?: Int.MIN_VALUE, 5)
        return when (level.coerceIn(0, 4)) {
            0 -> R.mipmap.status_wifi_0
            1 -> R.mipmap.status_wifi_1
            2 -> R.mipmap.status_wifi_2
            3 -> R.mipmap.status_wifi_3
            else -> R.mipmap.status_wifi_4
        }
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

    private fun getCurrentWifiInfo(context: Context): WifiInfo? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val wifiInfoFromCapabilities = connectivityManager
            ?.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?.transportInfo as? WifiInfo
        if (wifiInfoFromCapabilities != null) {
            return wifiInfoFromCapabilities
        }

        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        return wifiManager?.connectionInfo
    }

    private fun sanitizeSsid(rawSsid: String?): String? {
        val ssid = rawSsid
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return when (ssid) {
            null, WifiManager.UNKNOWN_SSID, "<unknown ssid>" -> null
            else -> ssid
        }
    }
}
