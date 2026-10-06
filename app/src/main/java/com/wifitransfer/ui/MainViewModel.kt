package com.wifitransfer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifitransfer.data.AppDatabase
import com.wifitransfer.data.TransferRecord
import com.wifitransfer.network.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getDatabase(app)
    private val dao = db.transferDao()

    private val fileSender = FileSender(app)
    private val fileReceiver = FileReceiver(app)
    val nsdHelper = NsdHelper(app)

    // Discovered devices
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices

    // Transfer progress
    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
    val transferProgress: StateFlow<TransferProgress?> = _transferProgress

    // Transfer history from Room
    val transferHistory = dao.getAllTransfers()

    // Server running state
    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving

    init {
        // NSD device discovery setup
        nsdHelper.onDeviceFound = { device ->
            _devices.value = _devices.value + device
        }
        nsdHelper.onDeviceLost = { device ->
            _devices.value = _devices.value - device
        }
    }

    fun startDiscovery() {
        nsdHelper.discoverDevices()
    }

    fun stopDiscovery() {
        nsdHelper.stopDiscovery()
    }

    /** File bhejo selected device ko */
    fun sendFile(uri: Uri, device: DeviceInfo, deviceName: String) {
        viewModelScope.launch {
            fileSender.sendFile(uri, device.ipAddress)
                .collect { progress ->
                    _transferProgress.value = progress
                    if (progress.isComplete) {
                        dao.insert(
                            TransferRecord(
                                fileName = progress.fileName,
                                fileSize = progress.totalBytes,
                                direction = "SENT",
                                deviceName = deviceName
                            )
                        )
                    }
                }
        }
    }

    /** File receive karna shuru karo */
    fun startReceiving() {
        _isReceiving.value = true
        viewModelScope.launch {
            fileReceiver.startReceiving()
                .collect { progress ->
                    _transferProgress.value = progress
                    if (progress.isComplete) {
                        dao.insert(
                            TransferRecord(
                                fileName = progress.fileName,
                                fileSize = progress.totalBytes,
                                direction = "RECEIVED",
                                deviceName = "Unknown"
                            )
                        )
                    }
                }
        }
    }

    fun stopReceiving() {
        _isReceiving.value = false
        fileReceiver.stop()
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.clearAll()
        }
    }

    override fun onCleared() {
        super.onCleared()
        nsdHelper.stopDiscovery()
        nsdHelper.unregisterService()
        fileReceiver.stop()
    }
}
