package com.wifitransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground Service - HTTP server aur WiFi hotspot chalata hai.
 * Background me server ko alive rakhta hai.
 *
 * User "Start" dabate hi ye service start hoti hai,
 * "Stop" dabate hi band hoti hai.
 */
class FileShareService : Service() {

    private var server: FileServer? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    companion object {
        const val ACTION_START = "com.wifitransfer.START"
        const val ACTION_STOP = "com.wifitransfer.STOP"
        const val CHANNEL_ID = "wifi_transfer_channel"
        const val NOTIFICATION_ID = 1001

        // Activity se access ke liye state
        var isRunning = false
        var serverUrl = ""
        var hotspotSsid = ""
        var hotspotPassword = ""
        var hotspotActive = false
        var hotspotError = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, createNotification("Server start ho raha hai..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Server start ho raha hai..."))
        }

        try {
            server = FileServer(this, 8080)
            server?.start(5000, false)
            isRunning = true
            serverUrl = "http://0.0.0.0:8080" // placeholder, update after hotspot
            updateServerUrl()
            tryStartHotspot()
            updateNotification()
        } catch (e: Exception) {
            isRunning = false
            updateNotification("Server error: ${e.message}")
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            // ignore
        }
        server = null

        try {
            hotspotReservation?.close()
        } catch (e: Exception) {
            // ignore
        }
        hotspotReservation = null
        hotspotActive = false

        isRunning = false
        serverUrl = ""
        hotspotSsid = ""
        hotspotPassword = ""
    }

    /**
     * WiFi Hotspot start karne ki koshish (LocalOnlyHotspot - bina internet ke).
     * Agar device support nahi karta, to user ko manual hotspot on karne bolege.
     */
    private fun tryStartHotspot() {
        hotspotError = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspotReservation = reservation
                        hotspotActive = true
                        try {
                            val config = reservation.wifiConfiguration
                            hotspotSsid = config?.SSID ?: ""
                            hotspotPassword = config?.preSharedKey ?: ""
                        } catch (e: Exception) {
                            // Kuch devices pe config access nahi hota
                        }
                        updateServerUrl()
                        updateNotification()
                        broadcastUpdate()
                    }

                    override fun onStopped() {
                        hotspotReservation = null
                        hotspotActive = false
                        updateNotification()
                        broadcastUpdate()
                    }

                    override fun onFailed(reason: Int) {
                        hotspotActive = false
                        hotspotError = when (reason) {
                            1 -> "Hotspot already on hai. Settings se band karke dobara try karo."
                            2 -> "Hotspot start nahi hua. Manual hotspot on karo."
                            3 -> "Hotspot unavailable. Phone ke Settings > Hotspot manually on karo."
                            else -> "Hotspot start fail. Manual hotspot on karo (Settings)."
                        }
                        updateServerUrl()
                        updateNotification()
                        broadcastUpdate()
                    }
                }, null)
            } catch (e: SecurityException) {
                hotspotError = "Location permission chahiye hotspot ke liye. App settings me permission do."
                updateNotification()
                broadcastUpdate()
            } catch (e: Exception) {
                hotspotError = "Hotspot auto-start fail. Phone ke Settings > Hotspot manually on karo."
                updateNotification()
                broadcastUpdate()
            }
        } else {
            hotspotError = "Android 8+ chahiye auto-hotspot ke liye. Manual hotspot on karo."
            updateNotification()
            broadcastUpdate()
        }
    }

    private fun updateServerUrl() {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        serverUrl = "http://${addr.hostAddress}:8080"
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        serverUrl = "http://localhost:8080"
    }

    // ========================================
    // NOTIFICATIONS
    // ========================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi File Transfer",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "File transfer server status"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val stopIntent = Intent(this, FileShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi File Transfer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)

        return builder.build()
    }

    private fun updateNotification(customText: String? = null) {
        val text = customText ?: buildNotificationText()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun buildNotificationText(): String {
        val sb = StringBuilder()
        if (serverUrl.isNotEmpty() && serverUrl != "http://localhost:8080") {
            sb.append("Server: $serverUrl")
        } else {
            sb.append("Server running - IP detect ho raha hai...")
        }
        if (hotspotActive && hotspotSsid.isNotEmpty()) {
            sb.append(" | Hotspot: $hotspotSsid")
        }
        if (hotspotError.isNotEmpty()) {
            sb.append(" | $hotspotError")
        }
        return sb.toString()
    }

    // ========================================
    // BROADCAST
    // ========================================

    private fun broadcastUpdate() {
        val intent = Intent("com.wifitransfer.STATE_UPDATE")
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
