package com.wifitransfer.network

data class DeviceInfo(
    val name: String,
    val ipAddress: String,
    val port: Int = 8080,
    val isTV: Boolean = false
) {
    val url get() = "http://$ipAddress:$port"
}
