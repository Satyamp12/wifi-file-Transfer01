package com.wifitransfer

/**
 * Ek shared file ka data model.
 * Sender phone se select ki gayi file ya receiver se aayi file.
 */
data class FileItem(
    val id: String,
    val name: String,
    val uri: String,
    val size: Long,
    val mimeType: String
) {
    fun toJson(): String {
        val safeName = name.replace("\\", "\\\\").replace("\"", "\\\"")
        val safeMime = mimeType.replace("\"", "\\\"")
        return """{"id":"$id","name":"$safeName","size":$size,"mimeType":"$safeMime"}"""
    }
}
