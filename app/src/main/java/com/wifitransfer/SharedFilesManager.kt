package com.wifitransfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared aur received files manage karta hai.
 * Shared files = sender ne share kiye (receiver download karega)
 * Received files = receiver ne upload kiye (sender phone pe save honge)
 */
object SharedFilesManager {

    private val sharedFiles = CopyOnWriteArrayList<FileItem>()

    /** Sender ke selected files list me add karo */
    fun addSharedFile(context: Context, uri: Uri): FileItem? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                it.moveToFirst()
                val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0 && !it.isNull(nameIdx)) it.getString(nameIdx) else "file_${System.currentTimeMillis()}"
                val size = if (sizeIdx >= 0 && !it.isNull(sizeIdx)) it.getLong(sizeIdx) else 0L
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val item = FileItem(
                    id = "s${System.currentTimeMillis()}${sharedFiles.size}",
                    name = name,
                    uri = uri.toString(),
                    size = size,
                    mimeType = mime
                )
                sharedFiles.add(item)
                item
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getSharedFiles(): List<FileItem> = sharedFiles.toList()

    fun getSharedFile(id: String): FileItem? = sharedFiles.find { it.id == id }

    fun removeSharedFile(id: String) {
        sharedFiles.removeAll { it.id == id }
    }

    fun clearShared() {
        sharedFiles.clear()
    }

    /** Received files ka directory - app-specific storage, no permission needed */
    fun getReceivedDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "received")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Received files ki list */
    fun getReceivedFiles(context: Context): List<FileItem> {
        val dir = getReceivedDir(context)
        return dir.listFiles()?.map { file ->
            val mime = guessMimeType(file.name)
            FileItem(
                id = file.name,
                name = file.name,
                uri = file.absolutePath,
                size = file.length(),
                mimeType = mime
            )
        }?.sortedByDescending { it.name } ?: emptyList()
    }

    fun removeReceivedFile(context: Context, name: String) {
        val file = File(getReceivedDir(context), name)
        if (file.exists()) file.delete()
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "avi", "mkv" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
