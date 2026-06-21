package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_history ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadHistory)

    @Delete
    suspend fun deleteDownload(download: DownloadHistory)

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteDownloadById(id: Int)

    @Query("SELECT * FROM download_history WHERE shortcode = :shortcode LIMIT 1")
    suspend fun getDownloadByShortcode(shortcode: String): DownloadHistory?
}
