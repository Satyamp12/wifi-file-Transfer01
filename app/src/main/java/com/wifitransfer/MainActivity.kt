package com.wifitransfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.wifitransfer.data.TransferRecord
import com.wifitransfer.network.DeviceInfo
import com.wifitransfer.network.TransferProgress
import com.wifitransfer.ui.MainViewModel
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var tvDeviceName: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnSend: MaterialButton
    private lateinit var btnReceive: MaterialButton
    private lateinit var progressCard: MaterialCardView
    private lateinit var tvFileName: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvProgress: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvSize: TextView
    private lateinit var rvDevices: RecyclerView
    private lateinit var tvNoDevices: TextView
    private lateinit var rvHistory: RecyclerView
    private lateinit var btnClearHistory: MaterialButton

    private val deviceAdapter = DeviceAdapter { device -> pickFileForDevice(device) }
    private val historyAdapter = HistoryAdapter()
    private var selectedDevice: DeviceInfo? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            val device = selectedDevice ?: return@registerForActivityResult
            uris.forEach { uri -> viewModel.sendFile(uri, device, device.name) }
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> setupApp() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        observeViewModel()
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvStatus = findViewById(R.id.tvStatus)
        btnSend = findViewById(R.id.btnSend)
        btnReceive = findViewById(R.id.btnReceive)
        progressCard = findViewById(R.id.progressCard)
        tvFileName = findViewById(R.id.tvFileName)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvSize = findViewById(R.id.tvSize)
        rvDevices = findViewById(R.id.rvDevices)
        tvNoDevices = findViewById(R.id.tvNoDevices)
        rvHistory = findViewById(R.id.rvHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter

        // Device name aur IP show karo
        tvDeviceName.text = "📱 ${android.os.Build.MODEL}"
        tvIpAddress.text = "IP: ${getLocalIpAddress()}"

        btnSend.setOnClickListener {
            if (deviceAdapter.itemCount == 0) {
                Toast.makeText(this, "Pehle koi device dhundo!", Toast.LENGTH_SHORT).show()
            } else {
                pickFile.launch(arrayOf("*/*"))
            }
        }

        btnReceive.setOnClickListener {
            if (viewModel.isReceiving.value) {
                viewModel.stopReceiving()
                btnReceive.text = "📥 Receive"
                tvStatus.text = "● Stopped"
                tvStatus.setTextColor(getColor(R.color.text_muted))
            } else {
                viewModel.startReceiving()
                btnReceive.text = "⏹ Stop"
                tvStatus.text = "● Receiving..."
                tvStatus.setTextColor(getColor(R.color.success))
                Toast.makeText(this, "File receive ke liye ready!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearHistory.setOnClickListener { viewModel.clearHistory() }

        // FAB - QR code
        findViewById<View>(R.id.fabQR).setOnClickListener {
            showQRDialog()
        }

        // Toolbar menu
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
        if (perms.isNotEmpty()) {
            requestPermissions.launch(perms.toTypedArray())
        } else {
            setupApp()
        }
    }

    private fun setupApp() {
        val ip = getLocalIpAddress()
        val deviceName = android.os.Build.MODEL
        viewModel.nsdHelper.registerService(9876, deviceName)
        viewModel.startDiscovery()
        tvIpAddress.text = "IP: $ip"
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.devices.collect { devices ->
                deviceAdapter.updateDevices(devices)
                tvNoDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.transferProgress.collect { progress ->
                progress?.let { updateProgress(it) }
            }
        }

        lifecycleScope.launch {
            viewModel.transferHistory.collect { history ->
                historyAdapter.updateHistory(history)
            }
        }
    }

    private fun updateProgress(progress: TransferProgress) {
        progressCard.visibility = View.VISIBLE
        tvFileName.text = progress.fileName
        progressBar.progress = progress.progressPercent
        tvProgress.text = "${progress.progressPercent}%"
        tvSpeed.text = progress.speedFormatted
        tvSize.text = progress.sizeFormatted

        if (progress.isComplete) {
            tvProgress.text = "✅ Done!"
            tvSpeed.text = ""
            Toast.makeText(this, "'${progress.fileName}' transfer ho gaya!", Toast.LENGTH_SHORT).show()
        } else if (progress.isFailed) {
            tvProgress.text = "❌ Failed"
            Toast.makeText(this, "Transfer fail: ${progress.errorMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickFileForDevice(device: DeviceInfo) {
        selectedDevice = device
        pickFile.launch(arrayOf("*/*"))
    }

    private fun showQRDialog() {
        val ip = getLocalIpAddress()
        Toast.makeText(this, "IP: $ip\nPort: 9876\nDusre phone se connect karo!", Toast.LENGTH_LONG).show()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return "Unknown"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                viewModel.stopDiscovery()
                viewModel.startDiscovery()
                Toast.makeText(this, "Scanning devices...", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopReceiving()
    }

    // ============ Device Adapter ============
    inner class DeviceAdapter(
        private val onSendClick: (DeviceInfo) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceHolder>() {

        private var devices = listOf<DeviceInfo>()

        fun updateDevices(list: List<DeviceInfo>) {
            devices = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DeviceHolder {
            val view = layoutInflater.inflate(R.layout.item_device, parent, false)
            return DeviceHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceHolder, position: Int) {
            holder.bind(devices[position])
        }

        override fun getItemCount() = devices.size

        inner class DeviceHolder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(device: DeviceInfo) {
                itemView.findViewById<TextView>(R.id.tvDeviceName).text = device.name
                itemView.findViewById<TextView>(R.id.tvDeviceIp).text = device.ipAddress
                itemView.findViewById<TextView>(R.id.tvDeviceIcon).text = if (device.isTV) "📺" else "📱"
                itemView.findViewById<MaterialButton>(R.id.btnSendToDevice).setOnClickListener {
                    onSendClick(device)
                }
            }
        }
    }

    // ============ History Adapter ============
    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.HistoryHolder>() {

        private var history = listOf<TransferRecord>()

        fun updateHistory(list: List<TransferRecord>) {
            history = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): HistoryHolder {
            val view = layoutInflater.inflate(R.layout.item_history, parent, false)
            return HistoryHolder(view)
        }

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            holder.bind(history[position])
        }

        override fun getItemCount() = history.size

        inner class HistoryHolder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(record: TransferRecord) {
                itemView.findViewById<TextView>(R.id.tvDirectionIcon).text =
                    if (record.direction == "SENT") "📤" else "📥"
                itemView.findViewById<TextView>(R.id.tvHistoryFileName).text = record.fileName
                itemView.findViewById<TextView>(R.id.tvHistoryMeta).text =
                    "${formatSize(record.fileSize)} • ${record.deviceName}"
                val date = Date(record.timestamp)
                val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
                itemView.findViewById<TextView>(R.id.tvHistoryTime).text = fmt.format(date)

                // FILE OPEN - click karne pe file khulega
                itemView.setOnClickListener {
                    if (record.direction == "RECEIVED") {
                        openReceivedFile(record.fileName)
                    }
                }

                // Long press pe share option
                itemView.setOnLongClickListener {
                    if (record.direction == "RECEIVED") {
                        shareReceivedFile(record.fileName)
                    }
                    true
                }
            }

            private fun formatSize(bytes: Long): String {
                return when {
                    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
                    bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
                    else -> "$bytes B"
                }
            }
        }
    }

    // ============ File Open & Share ============

    private fun openReceivedFile(fileName: String) {
        try {
            val receivedDir = File(getExternalFilesDir(null), "received")
            val file = File(receivedDir, fileName)
            if (!file.exists()) {
                Toast.makeText(this, "File nahi mili: $fileName", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val mime = getMimeType(fileName)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Koi app hai jo ye file khol sake?
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Chooser dikhao
                startActivity(Intent.createChooser(intent, "Open with..."))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "File open nahi ho saki: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareReceivedFile(fileName: String) {
        try {
            val receivedDir = File(getExternalFilesDir(null), "received")
            val file = File(receivedDir, fileName)
            if (!file.exists()) return

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(fileName)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share $fileName"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share nahi ho saka", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "txt" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            else -> "*/*"
        }
    }
}
