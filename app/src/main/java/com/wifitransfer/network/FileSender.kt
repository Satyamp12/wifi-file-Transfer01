package com.wifitransfer.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * TCP Socket se file sender
 * Fast direct transfer - koi server nahi chahiye
 */
class FileSender(private val context: Context) {

    companion object {
        const val TRANSFER_PORT = 9876
        const val BUFFER_SIZE = 65536 // 64KB chunks
    }

    fun sendFile(uri: Uri, deviceIp: String): Flow<TransferProgress> = flow {
        val fileName = getFileName(uri) ?: "unknown_file"
        val fileSize = getFileSize(uri)

        emit(TransferProgress(fileName, fileSize, 0))

        try {
            val socket = Socket(deviceIp, TRANSFER_PORT)
            socket.use {
                val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")

                // Pehle file info bhejo
                outputStream.writeUTF(fileName)
                outputStream.writeLong(fileSize)
                outputStream.flush()

                // Phir actual file data bhejo
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesTransferred = 0L
                var bytesRead: Int
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                inputStream.use { fis ->
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesTransferred += bytesRead

                        // Speed calculate karo
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTime
                        if (timeDiff >= 500) {
                            val speed = ((bytesTransferred - lastBytes) * 1000) / timeDiff
                            lastTime = currentTime
                            lastBytes = bytesTransferred
                            emit(TransferProgress(fileName, fileSize, bytesTransferred, speed))
                        }
                    }
                }

                outputStream.flush()
                emit(TransferProgress(fileName, fileSize, bytesTransferred, 0, isComplete = true))
            }
        } catch (e: Exception) {
            emit(TransferProgress(fileName, fileSize, 0, isFailed = true, errorMessage = e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun getFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            cursor.moveToFirst()
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
        } ?: 0L
    }
}
