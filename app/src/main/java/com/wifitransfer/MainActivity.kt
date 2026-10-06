package com.wifitransfer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Main Activity - app ka UI.
 *
 * Flow:
 * 1. User "Start Server" dabata hai
 * 2. App WiFi hotspot banata hai + HTTP server chalata hai
 * 3. QR code aur URL dikhata hai
 * 4. User files select karke share karta hai
 * 5. Dusra phone Chrome browser me URL khol ke files transfer karta hai
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnAddFiles: Button
    private lateinit var btnOpenReceived: Button
    private lateinit var serverInfoLayout: LinearLayout
    private lateinit var textServerUrl: TextView
    private lateinit var textHotspotInfo: TextView
    private lateinit var imageQR: ImageView
    private lateinit var textFileCount: TextView
    private lateinit var recyclerFiles: RecyclerView
    private lateinit var emptyState: TextView

    private val fileAdapter = FileListAdapter { fileId -> removeFile(fileId) }

    // File picker result
    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            // Persist permission to access the file
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // ignore
                }
                SharedFilesManager.addSharedFile(this, uri)
            }
            refreshFileList()
            Toast.makeText(this, "${uris.size} file(s) add ho gayi!", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission result
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startServerService()
        } else {
            Toast.makeText(this, "Permissions chahiye server ke liye", Toast.LENGTH_LONG).show()
        }
    }

    // Broadcast receiver for service state updates
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        refreshFileList()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.wifitransfer.STATE_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun initViews() {
        btnStart = findViewById(R.id.btnStart)
        btnAddFiles = findViewById(R.id.btnAddFiles)
        btnOpenReceived = findViewById(R.id.btnOpenReceived)
        serverInfoLayout = findViewById(R.id.serverInfoLayout)
        textServerUrl = findViewById(R.id.textServerUrl)
        textHotspotInfo = findViewById(R.id.textHotspotInfo)
        imageQR = findViewById(R.id.imageQR)
        textFileCount = findViewById(R.id.textFileCount)
        recyclerFiles = findViewById(R.id.recyclerFiles)
        emptyState = findViewById(R.id.emptyState)

        recyclerFiles.layoutManager = LinearLayoutManager(this)
        recyclerFiles.adapter = fileAdapter
    }

    private fun setupClickListeners() {
        btnStart.setOnClickListener {
            if (FileShareService.isRunning) {
                stopServerService()
            } else {
                checkAndStart()
            }
        }

        btnAddFiles.setOnClickListener {
            pickFiles.launch(arrayOf("*/*"))
        }

        btnOpenReceived.setOnClickListener {
            // Received files folder khol do
            val intent = Intent(Intent.ACTION_VIEW)
            val receivedDir = SharedFilesManager.getReceivedDir(this)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", receivedDir
            )
            intent.setDataAndType(uri, "resource/folder")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Folder open na ho to files list dikhao
                showReceivedFiles()
            }
        }
    }

    // ========================================
    // SERVER CONTROL
    // ========================================

    private fun checkAndStart() {
        val perms = mutableListOf<String>()

        // Location permission - hotspot ke liye
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Notification permission - Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (perms.isNotEmpty()) {
            requestPermissions.launch(perms.toTypedArray())
        } else {
            startServerService()
        }
    }

    private fun startServerService() {
        val intent = Intent(this, FileShareService::class.java).apply {
            action = FileShareService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Server start ho raha hai...", Toast.LENGTH_SHORT).show()
    }

    private fun stopServerService() {
        val intent = Intent(this, FileShareService::class.java).apply {
            action = FileShareService.ACTION_STOP
        }
        startService(intent)
    }

    // ========================================
    // UI UPDATE
    // ========================================

    private fun updateUI() {
        if (FileShareService.isRunning) {
            btnStart.text = "⏹  Server Band Karo"
            btnStart.setBackgroundColor(Color.parseColor("#FF4757"))
            serverInfoLayout.visibility = View.VISIBLE
            btnAddFiles.visibility = View.VISIBLE
            btnOpenReceived.visibility = View.VISIBLE

            // Server URL
            val url = FileShareService.serverUrl
            textServerUrl.text = if (url.isNotEmpty() && url != "http://localhost:8080") {
                "Receiver phone me ye URL kholo:\n$url"
            } else {
                "Server chal raha hai... IP detect ho raha hai"
            }

            // Hotspot info
            val sb = StringBuilder()
            if (FileShareService.hotspotActive && FileShareService.hotspotSsid.isNotEmpty()) {
                sb.append("📶 WiFi Hotspot: ${FileShareService.hotspotSsid}")
                if (FileShareService.hotspotPassword.isNotEmpty()) {
                    sb.append("\n🔑 Password: ${FileShareService.hotspotPassword}")
                }
                sb.append("\n\nDusre phone se is WiFi se connect karo")
            } else if (FileShareService.hotspotError.isNotEmpty()) {
                sb.append("⚠️ ${FileShareService.hotspotError}")
            } else {
                sb.append("📶 Hotspot start ho raha hai...")
            }
            textHotspotInfo.text = sb.toString()

            // QR code
            if (url.isNotEmpty() && url != "http://localhost:8080") {
                val qrBitmap = generateQR(url, 400)
                imageQR.setImageBitmap(qrBitmap)
                imageQR.visibility = View.VISIBLE
            }
        } else {
            btnStart.text = "▶  Server Start Karo"
            btnStart.setBackgroundColor(Color.parseColor("#4F8CFF"))
            serverInfoLayout.visibility = View.GONE
            btnAddFiles.visibility = View.GONE
            btnOpenReceived.visibility = View.GONE
        }
    }

    // ========================================
    // FILE LIST
    // ========================================

    private fun refreshFileList() {
        val files = SharedFilesManager.getSharedFiles()
        fileAdapter.update(files)

        if (files.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerFiles.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerFiles.visibility = View.VISIBLE
        }

        val totalSize = files.sumOf { it.size }
        textFileCount.text = "${files.size} file(s) • ${formatSize(totalSize)}"
    }

    private fun removeFile(fileId: String) {
        SharedFilesManager.removeSharedFile(fileId)
        refreshFileList()
    }

    private fun showReceivedFiles() {
        val files = SharedFilesManager.getReceivedFiles(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "Abhi koi received file nahi hai", Toast.LENGTH_SHORT).show()
        } else {
            val names = files.joinToString("\n") { "• ${it.name} (${formatSize(it.size)})" }
            Toast.makeText(this, "Received files:\n$names", Toast.LENGTH_LONG).show()
        }
    }

    // ========================================
    // QR CODE GENERATION
    // ========================================

    private fun generateQR(text: String, size: Int): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    // ========================================
    // FILE LIST ADAPTER
    // ========================================

    inner class FileListAdapter(
        private val onRemove: (String) -> Unit
    ) : RecyclerView.Adapter<FileListAdapter.FileHolder>() {

        private var items = listOf<FileItem>()

        fun update(files: List<FileItem>) {
            items = files
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FileHolder {
            val view = layoutInflater.inflate(R.layout.item_file, parent, false)
            return FileHolder(view)
        }

        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val file = items[position]
            holder.bind(file)
        }

        override fun getItemCount() = items.size

        inner class FileHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val nameText: TextView = view.findViewById(R.id.fileName)
            private val sizeText: TextView = view.findViewById(R.id.fileSize)
            private val removeBtn: Button = view.findViewById(R.id.btnRemoveFile)

            fun bind(file: FileItem) {
                nameText.text = file.name
                sizeText.text = formatSize(file.size)
                removeBtn.setOnClickListener { onRemove(file.id) }
            }
        }
    }

    // ========================================
    // HELPERS
    // ========================================

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val i = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
            .coerceAtMost(units.size - 1)
        return String.format("%.1f %s", bytes / Math.pow(1024.0, i.toDouble()), units[i])
    }
}
