package com.example.ui

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DownloadHistory
import com.example.data.DownloadRepository
import com.example.utils.InstagramDownloaderEngine
import com.example.utils.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"

    private val db = AppDatabase.getDatabase(application)
    private val repository = DownloadRepository(db.downloadDao())
    val settings = SettingsPreferences(application)

    // Process-resilient Application scope to keep background downloads running upon exit/minimise
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Exposed States
    private val _inputUrl = MutableStateFlow("")
    val inputUrl = _inputUrl.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0.0f)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage = _statusMessage.asStateFlow()

    private val _instagramCookies = MutableStateFlow(settings.instagramCookies)
    val instagramCookies = _instagramCookies.asStateFlow()

    private val _isGeminiEnabled = MutableStateFlow(settings.isGeminiFallbackEnabled)
    val isGeminiEnabled = _isGeminiEnabled.asStateFlow()

    private val _isAutoClipboard = MutableStateFlow(settings.isAutoClipboardEnabled)
    val isAutoClipboard = _isAutoClipboard.asStateFlow()

    // Last Parsed Post Preview
    private val _previewPost = MutableStateFlow<InstagramDownloaderEngine.InstagramPost?>(null)
    val previewPost = _previewPost.asStateFlow()

    // Download Mode state
    private val _downloadMode = MutableStateFlow(DownloadMode.POST)
    val downloadMode = _downloadMode.asStateFlow()

    // Navigation state
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab = _selectedTab.asStateFlow()

    // Flag to temporarily skip clipboard reading when shared via Android intent
    var skipClipboardOnce = false

    fun setDownloadMode(mode: DownloadMode) {
        _downloadMode.value = mode
        if (mode == DownloadMode.PROFILE) {
            val currentText = _inputUrl.value
            val extractedUser = extractUsernameFromUrl(currentText)
            if (extractedUser.isNotEmpty()) {
                _inputUrl.value = extractedUser
                showToast("Extracted profile username: @$extractedUser")
            } else if (currentText.startsWith("http://", ignoreCase = true) || currentText.startsWith("https://", ignoreCase = true) || currentText.contains("instagram.com", ignoreCase = true)) {
                _inputUrl.value = ""
                showToast("URLs are not allowed in the Profile section. Enter username only.")
            }
        }
    }

    private fun extractUsernameFromUrl(url: String): String {
        var trimmed = url.trim().removeSuffix("/")
        if (trimmed.contains("instagram.com/")) {
            val partAfter = trimmed.substringAfter("instagram.com/")
            val pathSegment = partAfter.substringBefore("?").trim('/')
            val segments = pathSegment.split("/")
            if (segments.isNotEmpty()) {
                val candidate = segments[0]
                if (candidate != "p" && candidate != "reel" && candidate != "stories" && candidate != "tv" && candidate != "explore") {
                    return candidate
                }
            }
        }
        return ""
    }

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    // Room Database Download History Flow
    val downloadHistory: StateFlow<List<DownloadHistory>> = repository.allDownloads
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onUrlChange(newUrl: String) {
        val trimmed = newUrl.trim()
        val isLink = trimmed.startsWith("http://", ignoreCase = true) || 
                     trimmed.startsWith("https://", ignoreCase = true) || 
                     trimmed.contains("instagram.com", ignoreCase = true)

        if (_downloadMode.value == DownloadMode.PROFILE) {
            if (isLink) {
                val extractedUser = extractUsernameFromUrl(trimmed)
                if (extractedUser.isNotEmpty()) {
                    _inputUrl.value = extractedUser
                    showToast("Extracted profile username: @$extractedUser")
                } else {
                    showToast("URLs are not allowed in the Profile section. Enter username only.")
                }
                return
            } else {
                _inputUrl.value = trimmed
            }
        } else {
            val extractedUrl = extractUrlFromText(newUrl)
            _inputUrl.value = extractedUrl
            if (extractedUrl.isNotEmpty()) {
                autoDetectMode(extractedUrl)
            }
        }
    }

    private fun extractUrlFromText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.split("\\s+".toRegex()).firstOrNull() ?: trimmed
        }
        
        // Expose matching logic for sharing captions from apps (e.g. "Check out this photo... http://...")
        val pattern = java.util.regex.Pattern.compile("(https?://[^\\s]+)")
        val matcher = pattern.matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1) ?: trimmed
        }
        
        return trimmed
    }

    private fun autoDetectMode(url: String) {
        val trimmed = url.trim()
        if (trimmed.contains("instagram.com", ignoreCase = true)) {
            // Automatically switch to Dowload Dashboard tab (0) to show action
            _selectedTab.value = 0

            when {
                trimmed.contains("/p/", ignoreCase = true) || 
                trimmed.contains("/reel/", ignoreCase = true) || 
                trimmed.contains("/tv/", ignoreCase = true) -> {
                    _downloadMode.value = DownloadMode.POST
                }
                else -> {
                    val cleanPath = trimmed.substringAfter("instagram.com/").substringBefore("?").trim('/')
                    if (cleanPath.isNotEmpty() && !cleanPath.contains("/")) {
                        _downloadMode.value = DownloadMode.PROFILE
                    }
                }
            }
        }
    }

    fun onCookiesChange(newCookies: String) {
        _instagramCookies.value = newCookies
        settings.instagramCookies = newCookies
    }

    fun toggleGemini(enabled: Boolean) {
        _isGeminiEnabled.value = enabled
        settings.isGeminiFallbackEnabled = enabled
    }

    fun toggleAutoClipboard(enabled: Boolean) {
        _isAutoClipboard.value = enabled
        settings.isAutoClipboardEnabled = enabled
    }

    fun clearPreview() {
        _previewPost.value = null
    }

    fun deleteHistoryItem(item: DownloadHistory) {
        viewModelScope.launch {
            try {
                // Delete actual local file to save storage
                val file = File(item.localPath)
                if (file.exists()) {
                    file.delete()
                }
                repository.delete(item)
                showToast("File deleted from history.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete item: ", e)
            }
        }
    }

    fun startDownload(context: Context, targetUrl: String) {
        val link = targetUrl.trim()
        if (link.isEmpty()) {
            _statusMessage.value = "Please enter an Instagram URL."
            return
        }

        val shortcode = InstagramDownloaderEngine.extractShortcode(link)
        if (shortcode == null) {
            _statusMessage.value = "Invalid Instagram link template."
            return
        }

        applicationScope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0.0f
            _statusMessage.value = "Connecting to Instagram..."
            
            val cookiesToSend = if (_instagramCookies.value.isNotEmpty()) _instagramCookies.value else null
            
            // 1. Fetch Instagram Metadata
            val result = InstagramDownloaderEngine.fetchInstagramPost(context, link, cookiesToSend)
            
            result.onSuccess { post ->
                _previewPost.value = post
                _statusMessage.value = "Post parsed successfully! Saving files..."
                
                var totalSuccessful = 0
                val downloadedUnits = mutableListOf<File>()

                // 2. Download each stream url
                post.urls.forEachIndexed { index, mediaUrl ->
                    _statusMessage.value = "Downloading file ${index + 1} of ${post.urls.size}..."
                    
                    val isVideo = post.mediaType == "video" || (post.mediaType == "carousel" && mediaUrl.contains(".mp4"))
                    val extension = if (isVideo) "mp4" else "jpg"
                    val fileSuffix = if (post.urls.size > 1) "_${index + 1}" else ""
                    val finalFilename = "instagram_${post.shortcode}${fileSuffix}.${extension}"

                    val downloadResult = InstagramDownloaderEngine.downloadMediaFile(
                        context = context,
                        mediaUrl = mediaUrl,
                        filename = finalFilename,
                        isVideo = isVideo,
                        onProgress = { progress ->
                            // Overall progress accounts for multi-file carousel downloads
                            val segmentWeight = 1.0f / post.urls.size
                            val currentBaseline = index * segmentWeight
                            _downloadProgress.value = currentBaseline + (progress * segmentWeight)
                        }
                    )

                    downloadResult.onSuccess { savedFile ->
                        downloadedUnits.add(savedFile)
                        totalSuccessful++
                    }.onFailure { err ->
                        Log.e(TAG, "Item download failed: ", err)
                    }
                }

                _isDownloading.value = false
                _downloadProgress.value = 1.0f
                
                if (totalSuccessful > 0) {
                    _statusMessage.value = "✔ Successfully saved $totalSuccessful media file(s) to Downloads!"
                    _inputUrl.value = "" // clear input on success
                    showToast("Downloaded smoothly to Downloads/SaveTube folder!")

                    // Save ONE history entry for the entire carousel/post collaboration/collection
                    val primaryFile = downloadedUnits.firstOrNull()
                    if (primaryFile != null) {
                        val isVideo = post.mediaType == "video" || post.urls.any { it.contains(".mp4") }
                        val historyEntry = DownloadHistory(
                            url = link,
                            shortcode = post.shortcode,
                            mediaType = if (isVideo) "video" else "image",
                            localPath = primaryFile.absolutePath,
                            filename = primaryFile.name,
                            fileSize = downloadedUnits.sumOf { it.length() },
                            thumbnailUrl = if (isVideo) null else primaryFile.absolutePath,
                            caption = post.caption,
                            username = post.username
                        )
                        repository.insert(historyEntry)
                    }
                } else {
                    _statusMessage.value = "❌ Download failed or was interrupted by Instagram blocks."
                    showToast("Download failed. Make sure VPN/proxy is configured properly.")
                }

            }.onFailure { exception ->
                _isDownloading.value = false
                _downloadProgress.value = 0.0f
                _statusMessage.value = "Error: ${exception.message ?: "Extraction failed"}"
                showToast("Failed: Check URL or settings.")
            }
        }
    }

    fun startProfileDownload(context: Context, usernameInput: String) {
        val username = usernameInput.trim().removePrefix("@").split("?")[0]
        if (username.isEmpty()) {
            _statusMessage.value = "Please enter an Instagram username or profile link."
            return
        }

        applicationScope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0.0f
            _statusMessage.value = "Connecting to Instagram profile for @$username..."
            _previewPost.value = null
            
            val cookiesToSend = if (_instagramCookies.value.isNotEmpty()) _instagramCookies.value else null
            val result = InstagramDownloaderEngine.fetchInstagramProfilePicture(context, username, cookiesToSend)
            
            result.onSuccess { post ->
                _previewPost.value = post
                _statusMessage.value = "Profile picture parsed! Archiving photo..."

                if (post.urls.isEmpty()) {
                    _isDownloading.value = false
                    _statusMessage.value = "Error: Profile has no valid picture url."
                    return@onSuccess
                }

                val mediaUrl = post.urls.first()
                val finalFilename = "instagram_profile_${username}.jpg"

                val downloadResult = InstagramDownloaderEngine.downloadMediaFile(
                    context = context,
                    mediaUrl = mediaUrl,
                    filename = finalFilename,
                    isVideo = false,
                    onProgress = { progress -> _downloadProgress.value = progress }
                )

                downloadResult.onSuccess { savedFile ->
                    val historyEntry = DownloadHistory(
                        url = "https://www.instagram.com/$username/",
                        shortcode = "profile_$username",
                        mediaType = "image",
                        localPath = savedFile.absolutePath,
                        filename = finalFilename,
                        fileSize = savedFile.length(),
                        thumbnailUrl = savedFile.absolutePath,
                        caption = "@$username profile photo",
                        username = username
                    )
                    repository.insert(historyEntry)

                    _isDownloading.value = false
                    _downloadProgress.value = 1.0f
                    _statusMessage.value = "✔ Successfully saved profile picture to Downloads!"
                    _inputUrl.value = ""
                    showToast("Downloaded profile picture successfully!")
                }.onFailure { err ->
                    _isDownloading.value = false
                    _statusMessage.value = "Error: Failed to save file - ${err.message}"
                    showToast("Failed to save profile picture.")
                }
            }.onFailure { exception ->
                _isDownloading.value = false
                _downloadProgress.value = 0.0f
                _statusMessage.value = "Error: ${exception.message ?: "Failed to parse profile"}"
                showToast("Failed: ${exception.message}")
            }
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
        }
    }
}

enum class DownloadMode {
    POST, PROFILE
}
