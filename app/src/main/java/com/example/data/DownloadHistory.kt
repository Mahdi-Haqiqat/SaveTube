package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_history")
data class DownloadHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val shortcode: String,
    val mediaType: String, // "video" or "image"
    val localPath: String, // Path to file on device
    val filename: String,
    val fileSize: Long = 0,
    val thumbnailUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val caption: String? = null,
    val username: String? = "instagram_user"
)
