package com.wifitransfer.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket

/**
 * TCP Socket File Receiver
 * Server socket chalata hai aur incoming files accept karta hai
 */
class FileReceiver(private val context: Context) {

    companion object {
        const val TRANSFER_PORT = 9876
        const val BUFFER_SIZE = 65536
    }

    private var serverSocket: ServerSocket? = null
    var isRunning = false

    fun startReceiving(): Flow<TransferProgress> = flow {
        serverSocket = ServerSocket(TRANSFER_PORT)
        isRunning = true

        while (isRunning) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                clientSocket.use { socket ->
                    val inputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))

                    // File info receive karo
                    val fileName = inputStream.readUTF()
                    val fileSize = inputStream.readLong()

                    emit(TransferProgress(fileName, fileSize, 0))

                    // Received files save karo
                    val receivedDir = File(context.getExternalFilesDir(null), "received")
                    if (!receivedDir.exists()) receivedDir.mkdirs()

                    // Duplicate name handle karo
                    var finalName = fileName
                    var counter = 1
                    while (File(receivedDir, finalName).exists()) {
                        val ext = fileName.substringAfterLast('.', "")
                        val base = if (ext.isNotEmpty()) fileName.substringBeforeLast('.') else fileName
                        finalName = if (ext.isNotEmpty()) "$base ($counter).$ext" else "$base ($counter)"
                        counter++
                    }

                    val outFile = File(receivedDir, finalName)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesReceived = 0L
                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L

                    FileOutputStream(outFile).use { fos ->
                        while (bytesReceived < fileSize) {
                            val toRead = minOf(BUFFER_SIZE.toLong(), fileSize - bytesReceived).toInt()
                            val bytesRead = inputStream.read(buffer, 0, toRead)
                            if (bytesRead == -1) break
                            fos.write(buffer, 0, bytesRead)
                            bytesReceived += bytesRead

                            val currentTime = System.currentTimeMillis()
                            val timeDiff = currentTime - lastTime
                            if (timeDiff >= 500) {
                                val speed = ((bytesReceived - lastBytes) * 1000) / timeDiff
                                lastTime = currentTime
                                lastBytes = bytesReceived
                                emit(TransferProgress(finalName, fileSize, bytesReceived, speed))
                            }
                        }
                    }

                    emit(TransferProgress(finalName, fileSize, bytesReceived, 0, isComplete = true))
                }
            } catch (e: Exception) {
                if (isRunning) {
                    emit(TransferProgress("", 0, 0, isFailed = true, errorMessage = e.message ?: "Error"))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}
