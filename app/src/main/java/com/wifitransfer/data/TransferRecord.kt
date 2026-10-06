package com.wifitransfer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_history")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val fileName: String,
    val fileSize: Long,
    val direction: String, // "SENT" or "RECEIVED"
    val deviceName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SUCCESS" // SUCCESS, FAILED
)
