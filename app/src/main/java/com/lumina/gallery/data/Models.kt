package com.lumina.gallery.data

import android.net.Uri

enum class SortField(val label: String) {
    MODIFIED("Last modified"), NAME("Name"), PATH("Path"), SIZE("Size"), TAKEN("Date taken"), RANDOM("Random")
}

enum class MediaFilter(val label: String) {
    ALL("All Media"), PHOTOS("Photos"), VIDEOS("Videos"), GIFS("GIFs"), SCREENSHOTS("Screenshots"), FAVORITES("Favorites")
}

data class Media(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val isVideo: Boolean,
    val size: Long,
    val modified: Long,      // seconds
    val taken: Long,         // millis, 0 when unknown
    val bucketId: Long,
    val bucket: String,
    val relPath: String,
    val duration: Long,      // millis
    val width: Int,
    val height: Int,
    val favorite: Boolean
) {
    val isPdf: Boolean get() = mime == "application/pdf"
    val isGif: Boolean get() = mime == "image/gif"
    val isImage: Boolean get() = !isVideo && !isPdf
    val isScreenshot: Boolean get() = relPath.contains("screenshot", true) || name.startsWith("Screenshot", true)
    val time: Long get() = if (taken > 0) taken else modified * 1000
}

data class Album(
    val id: Long,
    val name: String,
    val path: String,
    val count: Int,
    val cover: Media?,
    val isBin: Boolean = false
)

fun Media.matches(f: MediaFilter): Boolean = when (f) {
    MediaFilter.ALL -> !isPdf
    MediaFilter.PHOTOS -> isImage && !isGif
    MediaFilter.VIDEOS -> isVideo
    MediaFilter.GIFS -> isGif
    MediaFilter.SCREENSHOTS -> !isPdf && isScreenshot
    MediaFilter.FAVORITES -> !isPdf && favorite
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}
