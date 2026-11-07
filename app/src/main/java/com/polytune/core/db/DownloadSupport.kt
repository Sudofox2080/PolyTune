package com.polytune.core.db

import java.util.UUID

/** 任务 id 生成与进度换算。状态常量见 [DownloadState]。 */
fun DownloadTaskEntity.progressFraction(): Float = when {
    state == DownloadState.DONE -> 1f
    totalBytes <= 0 -> 0f
    else -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** 已下载的可读大小，例如 12.4 MB。 */
fun DownloadTaskEntity.sizeText(): String = when {
    totalBytes > 0 -> "${humanSize(downloadedBytes)} / ${humanSize(totalBytes)}"
    downloadedBytes > 0 -> humanSize(downloadedBytes)
    else -> ""
}

fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

fun newTaskId(): String = UUID.randomUUID().toString()
