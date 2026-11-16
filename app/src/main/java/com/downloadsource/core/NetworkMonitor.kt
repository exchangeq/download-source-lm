package com.downloadsource.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NetworkMonitor {
    private val _isWifi = MutableStateFlow(true)
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    fun start(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // 核心功能 1：网络恢复时，自动启动所有排队中的任务
                DownloadManager.autoResumeTasks(ctx)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _isWifi.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
            override fun onLost(network: Network) { _isWifi.value = false }
        })
    }
}
