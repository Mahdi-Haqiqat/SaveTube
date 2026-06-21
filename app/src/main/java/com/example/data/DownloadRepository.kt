package com.example.data

import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val downloadDao: DownloadDao) {
    val allDownloads: Flow<List<DownloadHistory>> = downloadDao.getAllDownloads()

    suspend fun insert(download: DownloadHistory) {
        downloadDao.insertDownload(download)
    }

    suspend fun delete(download: DownloadHistory) {
        downloadDao.deleteDownload(download)
    }

    suspend fun deleteById(id: Int) {
        downloadDao.deleteDownloadById(id)
    }

    suspend fun getByShortcode(shortcode: String): DownloadHistory? {
        return downloadDao.getDownloadByShortcode(shortcode)
    }
}
