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
    private var pendingSharedUris: List<Uri> = emptyList() // Share se aaye URIs

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val device = selectedDevice ?: run {
                Toast.makeText(this, "Device select nahi hai!", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val data = result.data

            val urisToSend = mutableListOf<Uri>()

            // Single file
            data?.data?.let { uri -> urisToSend.add(uri) }

            // Multiple files
            data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    urisToSend.add(clipData.getItemAt(i).uri)
                }
            }

            if (urisToSend.isEmpty()) {
                Toast.makeText(this, "Koi file select nahi ki", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            // Permission lene ki koshish karo (optional - fail hone pe bhi chalega)
            urisToSend.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Kuch URIs persistable nahi hote - ignore karo
                }
            }

            // Bhejo
            urisToSend.forEach { uri -> viewModel.sendFile(uri, device, device.name) }
            Toast.makeText(this, "📤 ${urisToSend.size} file(s) bhej raha hai...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            pickFile.launch(Intent.createChooser(intent, "Files select karo"))
        } catch (e: Exception) {
            // Fallback - simple file picker
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                pickFile.launch(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "File picker open nahi hua: ${e2.message}", Toast.LENGTH_LONG).show()
            }
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

        // Kisi aur app se share karke aaya hai?
        handleIncomingShare(intent)
    }

    // singleTask mode mein - nayi share intent yahan aati hai
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    // Dusri app se share hone pe ye chalega
    private fun handleIncomingShare(intent: Intent?) {
        val action = intent?.action ?: return

        // Sirf share intents handle karo - MAIN/LAUNCHER ignore karo
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val uris = mutableListOf<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                // File URI
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                streamUri?.let { uris.add(it) }

                // Text share (koi URI nahi) - ignore
                if (uris.isEmpty()) return
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val multiUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                multiUris?.let { uris.addAll(it) }
                if (uris.isEmpty()) return
            }
        }

        // URIs mil gayi - save karo
        pendingSharedUris = uris

        // Devices check karo
        val devices = viewModel.devices.value
        if (devices.isNotEmpty()) {
            // Devices already hain - seedha dialog dikhao
            showDeviceSelectDialog(uris)
        } else {
            // Devices abhi nahi mile - message dikhao
            Toast.makeText(
                this,
                "✅ ${uris.size} file(s) ready!\n📡 Neeche device dhundh raha hai...\nDevice milne pe 'Send' dabao",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showDeviceSelectDialog(uris: List<Uri>) {
        val devices = viewModel.devices.value
        if (devices.isEmpty()) {
            // Devices nahi mile - save karke baad mein bhejo
            pendingSharedUris = uris
            Toast.makeText(
                this,
                "📡 Device dhund raha hai... Jab device mile to Send button dabao",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (devices.size == 1) {
            val device = devices[0]
            uris.forEach { uri -> viewModel.sendFile(uri, device, device.name) }
            Toast.makeText(this, "📤 ${uris.size} file(s) bhej raha hai...", Toast.LENGTH_SHORT).show()
        } else {
            val names = devices.map { "${it.name} (${it.ipAddress})" }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("📱 Kisko bhejna hai?")
                .setItems(names) { _, which ->
                    val device = devices[which]
                    uris.forEach { uri -> viewModel.sendFile(uri, device, device.name) }
                    Toast.makeText(this, "📤 Bhej raha hai...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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
                openFilePicker()
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
        openFilePicker()
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
                    // Agar share se pending files hain - seedha bhejo
                    if (pendingSharedUris.isNotEmpty()) {
                        pendingSharedUris.forEach { uri ->
                            viewModel.sendFile(uri, device, device.name)
                        }
                        Toast.makeText(
                            this@MainActivity,
                            "📤 ${pendingSharedUris.size} file(s) ${device.name} ko bhej raha hai...",
                            Toast.LENGTH_SHORT
                        ).show()
                        pendingSharedUris = emptyList() // clear karo
                    } else {
                        // Normal flow - file picker kholo
                        onSendClick(device)
                    }
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

            // Hamesha chooser use karo - resolveActivity Android 11+ pe kaam nahi karta
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                startActivity(Intent.createChooser(intent, "$fileName kholne ke liye app select karo"))
            } catch (e: Exception) {
                // Mime type se nahi khula - */* try karo
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(Intent.createChooser(fallbackIntent, "Open with"))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Koi app nahi mili is file ke liye", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
