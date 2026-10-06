package com.wifitransfer.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * NSD (Network Service Discovery) Helper
 * Same WiFi pe automatically devices dhundta hai - koi manual IP enter nahi karna
 */
class NsdHelper(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    val discoveredDevices = mutableListOf<DeviceInfo>()
    var onDeviceFound: ((DeviceInfo) -> Unit)? = null
    var onDeviceLost: ((DeviceInfo) -> Unit)? = null

    companion object {
        const val SERVICE_TYPE = "_wifitransfer._tcp."
        const val SERVICE_NAME = "WiFiTransfer"
        const val TAG = "NsdHelper"
    }

    /** Apna device advertise karo network pe */
    fun registerService(port: Int, deviceName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME-$deviceName"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering: ${e.message}")
        }
    }

    /** Network pe dusre devices dhundo */
    fun discoverDevices() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val ip = info.host?.hostAddress ?: return
                            val device = DeviceInfo(
                                name = info.serviceName,
                                ipAddress = ip,
                                port = info.port
                            )
                            discoveredDevices.add(device)
                            onDeviceFound?.invoke(device)
                            Log.d(TAG, "Device found: $ip:${info.port}")
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val removed = discoveredDevices.find { it.name == serviceInfo.serviceName }
                removed?.let {
                    discoveredDevices.remove(it)
                    onDeviceLost?.invoke(it)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering: ${e.message}")
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery: ${e.message}")
        }
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering: ${e.message}")
        }
    }
}
