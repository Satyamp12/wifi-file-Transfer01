package com.wifitransfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Embedded HTTP Server (NanoHTTPD based)
 * ---------------------------------------
 * Phone ke andar ye server chalega. Receiver phone Chrome browser
 * me iska IP khol ke files download/upload kar sakta hai.
 *
 * Routes:
 *   GET  /                  → Web UI (HTML page)
 *   GET  /css/, /js/         → Web assets
 *   GET  /api/info          → Server IP, port info
 *   GET  /api/qr            → QR code image (server URL ka)
 *   GET  /api/files         → Shared files ki list (JSON)
 *   GET  /api/download?id=  → Shared file download
 *   POST /api/upload        → File upload (receiver se aata hai)
 *   GET  /api/uploads       → Received files ki list
 *   GET  /api/uploads/download?name= → Received file download
 *   DELETE /api/files?id=   → Shared file remove
 *   DELETE /api/uploads?name= → Received file delete
 */
class FileServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                // ===== Web Assets =====
                uri == "/" -> serveAsset("index.html", "text/html")
                uri.startsWith("/css/") -> serveAsset("css/" + uri.removePrefix("/css/"), getMime(uri))
                uri.startsWith("/js/") -> serveAsset("js/" + uri.removePrefix("/js/"), getMime(uri))

                // ===== API: Server Info =====
                uri == "/api/info" && method == Method.GET -> handleInfo()

                // ===== API: QR Code =====
                uri == "/api/qr" && method == Method.GET -> handleQR()

                // ===== API: Shared Files (sender ne share kiye) =====
                uri == "/api/files" && method == Method.GET -> handleListShared()
                uri == "/api/download" && method == Method.GET -> handleDownloadShared(session)
                uri == "/api/files" && method == Method.DELETE -> handleRemoveShared(session)

                // ===== API: Received Files (receiver ne upload kiye) =====
                uri == "/api/uploads" && method == Method.GET -> handleListReceived()
                uri == "/api/uploads/download" && method == Method.GET -> handleDownloadReceived(session)
                uri == "/api/uploads" && method == Method.DELETE -> handleRemoveReceived(session)

                // ===== API: Upload =====
                uri == "/api/upload" && method == Method.POST -> handleUpload(session)

                // ===== 404 =====
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/plain", "404 Not Found: $uri"
                )
            }
        } catch (e: Exception) {
            jsonError("Server error: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }

    // ========================================
    // ASSET SERVING
    // ========================================

    private fun serveAsset(path: String, mimeType: String): Response {
        return try {
            val input = context.assets.open("web/$path")
            newChunkedResponse(Response.Status.OK, mimeType, input)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $path")
        }
    }

    // ========================================
    // API HANDLERS
    // ========================================

    private fun handleInfo(): Response {
        val ip = getServerIP()
        val json = JSONObject()
        json.put("url", "http://$ip:$myPort")
        json.put("ip", ip)
        json.put("port", myPort)
        return jsonResponse(json)
    }

    private fun handleQR(): Response {
        val ip = getServerIP()
        val url = "http://$ip:$listeningPort"
        val bitmap = generateQR(url, 300)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val data = baos.toByteArray()
        return newFixedLengthResponse(
            Response.Status.OK, "image/png",
            ByteArrayInputStream(data), data.size.toLong()
        )
    }

    private fun handleListShared(): Response {
        val files = SharedFilesManager.getSharedFiles()
        val json = JSONObject()
        val arr = JSONArray()
        for (f in files) {
            val item = JSONObject()
            item.put("id", f.id)
            item.put("name", f.name)
            item.put("size", f.size)
            item.put("sizeFormatted", formatSize(f.size))
            item.put("mimeType", f.mimeType)
            item.put("type", "shared")
            arr.put(item)
        }
        json.put("files", arr)
        json.put("total", files.size)
        return jsonResponse(json)
    }

    private fun handleListReceived(): Response {
        val files = SharedFilesManager.getReceivedFiles(context)
        val json = JSONObject()
        val arr = JSONArray()
        for (f in files) {
            val item = JSONObject()
            item.put("id", f.id)
            item.put("name", f.name)
            item.put("size", f.size)
            item.put("sizeFormatted", formatSize(f.size))
            item.put("mimeType", f.mimeType)
            item.put("type", "received")
            arr.put(item)
        }
        json.put("files", arr)
        json.put("total", files.size)
        return jsonResponse(json)
    }

    private fun handleDownloadShared(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonError("File ID missing")

        val file = SharedFilesManager.getSharedFile(id)
            ?: return jsonError("File not found", Response.Status.NOT_FOUND)

        return try {
            val uri = android.net.Uri.parse(file.uri)
            val input = context.contentResolver.openInputStream(uri)
                ?: return jsonError("Cannot open file")

            val response = newChunkedResponse(Response.Status.OK, file.mimeType, input)
            response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            return response
        } catch (e: Exception) {
            jsonError("Download error: ${e.message}")
        }
    }

    private fun handleDownloadReceived(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()
            ?: return jsonError("File name missing")

        val file = File(SharedFilesManager.getReceivedDir(context), name)
        if (!file.exists()) return jsonError("File not found", Response.Status.NOT_FOUND)

        val input = FileInputStream(file)
        val mime = guessMime(name)
        val response = newChunkedResponse(Response.Status.OK, mime, input)
        response.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        return response
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)

        val tempPath = files["files"]
            ?: return jsonError("No file uploaded")

        // Original filename query parameter se lo (web UI bhejta hai)
        val originalName = session.parameters["filename"]?.firstOrNull()
            ?: "upload_${System.currentTimeMillis()}"

        val tempFile = File(tempPath)
        if (!tempFile.exists()) return jsonError("Temp file not found")

        // Duplicate name handle karo
        val receivedDir = SharedFilesManager.getReceivedDir(context)
        var finalName = originalName
        var counter = 1
        while (File(receivedDir, finalName).exists()) {
            val ext = originalName.substringAfterLast('.', "")
            val base = if (ext.isNotEmpty()) originalName.substringBeforeLast('.') else originalName
            finalName = if (ext.isNotEmpty()) "$base ($counter).$ext" else "$base ($counter)"
            counter++
        }

        val destFile = File(receivedDir, finalName)
        tempFile.copyTo(destFile, overwrite = true)
        tempFile.delete()

        val json = JSONObject()
        json.put("success", true)
        json.put("name", finalName)
        json.put("size", destFile.length())
        json.put("sizeFormatted", formatSize(destFile.length()))
        return jsonResponse(json)
    }

    private fun handleRemoveShared(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonError("File ID missing")
        SharedFilesManager.removeSharedFile(id)
        val json = JSONObject()
        json.put("success", true)
        return jsonResponse(json)
    }

    private fun handleRemoveReceived(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()
            ?: return jsonError("File name missing")
        SharedFilesManager.removeReceivedFile(context, name)
        val json = JSONObject()
        json.put("success", true)
        return jsonResponse(json)
    }

    // ========================================
    // HELPERS
    // ========================================

    private fun getServerIP(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "0.0.0.0"
    }

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

    private fun getMime(uri: String): String {
        val ext = uri.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val i = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
            .coerceAtMost(units.size - 1)
        return String.format("%.1f %s", bytes / Math.pow(1024.0, i.toDouble()), units[i])
    }

    private fun jsonResponse(json: JSONObject): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun jsonError(message: String, status: Response.Status = Response.Status.BAD_REQUEST): Response {
        val json = JSONObject()
        json.put("error", message)
        return newFixedLengthResponse(status, "application/json", json.toString())
    }
}
