package com.wifitransfer.network

data class TransferProgress(
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val speedBps: Long = 0L,
    val isComplete: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String = ""
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((transferredBytes * 100) / totalBytes).toInt() else 0

    val speedFormatted: String
        get() {
            return when {
                speedBps >= 1_000_000 -> String.format("%.1f MB/s", speedBps / 1_000_000.0)
                speedBps >= 1_000 -> String.format("%.1f KB/s", speedBps / 1_000.0)
                else -> "$speedBps B/s"
            }
        }

    val sizeFormatted: String
        get() = formatSize(transferredBytes) + " / " + formatSize(totalBytes)

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
