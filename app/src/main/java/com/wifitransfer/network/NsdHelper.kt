package com.wifitransfer.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * NSD (Network Service Discovery) Helper
 * Same WiFi pe automatically devices dhundta hai
 * Apna device list mein nahi aata - sirf dusre devices
 */
class NsdHelper(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isDiscovering = false
    private var registeredServiceName = ""

    val discoveredDevices = mutableListOf<DeviceInfo>()
    var onDeviceFound: ((DeviceInfo) -> Unit)? = null
    var onDeviceLost: ((DeviceInfo) -> Unit)? = null

    companion object {
        const val SERVICE_TYPE = "_wifitransfer._tcp."
        const val SERVICE_NAME = "WFT"
        const val TAG = "NsdHelper"
    }

    /** Local IP address nikalo */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "IP error: ${e.message}") }
        return ""
    }

    /** Apna device advertise karo network pe */
    fun registerService(port: Int, deviceName: String) {
        // Safe name - special chars remove karo
        val safeName = "$SERVICE_NAME-${deviceName.replace("[^a-zA-Z0-9]".toRegex(), "")}-${port}"
        registeredServiceName = safeName

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = safeName
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.d(TAG, "Registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Register error: ${e.message}")
        }
    }

    /** Network pe dusre devices dhundo */
    fun discoverDevices() {
        if (isDiscovering) return
        isDiscovering = true

        val localIp = getLocalIpAddress()
        Log.d(TAG, "Local IP: $localIp - will filter this out")

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")

                // Apna service skip karo
                if (serviceInfo.serviceName == registeredServiceName ||
                    serviceInfo.serviceName.startsWith(registeredServiceName)) {
                    Log.d(TAG, "Skipping own service")
                    return
                }

                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = info.host?.hostAddress ?: return

                        // Apna IP filter karo - apna device list mein nahi aana chahiye
                        if (ip == localIp) {
                            Log.d(TAG, "Skipping own IP: $ip")
                            return
                        }

                        // Already discovered check karo
                        if (discoveredDevices.any { it.ipAddress == ip }) {
                            Log.d(TAG, "Already in list: $ip")
                            return
                        }

                        // Service name se device name nikalo
                        val rawName = info.serviceName
                        val friendlyName = rawName
                            .removePrefix("$SERVICE_NAME-")
                            .substringBeforeLast("-") // port number hata do
                            .ifEmpty { rawName }

                        val device = DeviceInfo(
                            name = friendlyName,
                            ipAddress = ip,
                            port = info.port
                        )
                        discoveredDevices.add(device)
                        onDeviceFound?.invoke(device)
                        Log.d(TAG, "Device added: $friendlyName @ $ip:${info.port}")
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val removed = discoveredDevices.find {
                    serviceInfo.serviceName.contains(it.name)
                }
                removed?.let {
                    discoveredDevices.remove(it)
                    onDeviceLost?.invoke(it)
                    Log.d(TAG, "Device lost: ${it.name}")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Log.e(TAG, "Start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            isDiscovering = false
            Log.e(TAG, "Discover error: ${e.message}")
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            isDiscovering = false
        } catch (e: Exception) {
            Log.e(TAG, "Stop discovery error: ${e.message}")
        }
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Unregister error: ${e.message}")
        }
    }
}
