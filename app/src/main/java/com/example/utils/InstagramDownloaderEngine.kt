package com.example.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object InstagramDownloaderEngine {
    private const val TAG = "InstaDownloaderEngine"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class InstagramPost(
        val mediaType: String, // "video", "image", or "carousel"
        val urls: List<String>, // List of download URLs (mp4 or jpg)
        val caption: String,
        val username: String,
        val shortcode: String
    )

    fun cleanUrl(url: String): String {
        return url.trim().split("?")[0]
    }

    fun extractShortcode(url: String): String? {
        val cleaned = cleanUrl(url)
        val patterns = listOf(
            Pattern.compile("/p/([^/]+)"),
            Pattern.compile("/reel/([^/]+)"),
            Pattern.compile("/tv/([^/]+)"),
            Pattern.compile("instagram.com/([^/]+)")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(cleaned)
            if (matcher.find()) {
                val group = matcher.group(1)
                // If the group is "p", "reel", or "tv" due to broad parsing, skip
                if (group != "p" && group != "reel" && group != "tv") {
                    return group
                }
            }
        }
        return null
    }

    // Helper to clean escaped strings inside JSON/HTML script tags
    fun unescapeString(str: String): String {
        var result = str.replace("\\/", "/")
        result = result.replace("\\u0026", "&")
        result = result.replace("&amp;", "&")
        result = result.replace("\\u0040", "@")
        result = result.replace("\\\"", "\"")
        // Resolve unicode sequences dynamically
        val unicodeMatcher = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(result)
        val sb = StringBuffer()
        while (unicodeMatcher.find()) {
            val hexVal = unicodeMatcher.group(1)?.toInt(16) ?: 0
            unicodeMatcher.appendReplacement(sb, hexVal.toChar().toString())
        }
        unicodeMatcher.appendTail(sb)
        return sb.toString()
    }

    private fun extractSidecarJson(html: String): String? {
        val startIndex = html.indexOf("\"edge_sidecar_to_children\"")
        if (startIndex == -1) return null
        
        val startBrace = html.indexOf("{", startIndex)
        if (startBrace == -1) return null
        
        var openBrackets = 1
        var currentIndex = startBrace + 1
        val length = html.length
        
        while (currentIndex < length && openBrackets > 0) {
            val char = html[currentIndex]
            if (char == '{') {
                openBrackets++
            } else if (char == '}') {
                openBrackets--
            }
            currentIndex++
        }
        
        if (openBrackets == 0) {
            return html.substring(startBrace, currentIndex)
        }
        return null
    }

    private fun extractCarouselMediaJson(html: String): String? {
        val startIndex = html.indexOf("\"carousel_media\"")
        if (startIndex == -1) return null
        
        val startBrace = html.indexOf("[", startIndex)
        if (startBrace == -1) return null
        
        var openBrackets = 1
        var currentIndex = startBrace + 1
        val length = html.length
        
        while (currentIndex < length && openBrackets > 0) {
            val char = html[currentIndex]
            if (char == '[') {
                openBrackets++
            } else if (char == ']') {
                openBrackets--
            }
            currentIndex++
        }
        
        if (openBrackets == 0) {
            return html.substring(startBrace, currentIndex)
        }
        return null
    }

    fun parseCarouselFromHtml(htmlContent: String): List<String>? {
        // Attempt 1: "edge_sidecar_to_children"
        try {
            val sidecarJson = extractSidecarJson(htmlContent)
            if (sidecarJson != null) {
                val sidecarObj = JSONObject(sidecarJson)
                val edges = sidecarObj.optJSONArray("edges")
                if (edges != null && edges.length() > 0) {
                    val urls = mutableListOf<String>()
                    for (i in 0 until edges.length()) {
                        val edge = edges.optJSONObject(i) ?: continue
                        val node = edge.optJSONObject("node") ?: continue
                        
                        val isVideo = node.optBoolean("is_video", false)
                        var mediaUrl = ""
                        if (isVideo) {
                            mediaUrl = node.optString("video_url")
                        }
                        if (mediaUrl.isEmpty()) {
                            val displayResources = node.optJSONArray("display_resources")
                            if (displayResources != null && displayResources.length() > 0) {
                                val bestResource = displayResources.optJSONObject(displayResources.length() - 1)
                                if (bestResource != null) {
                                    mediaUrl = bestResource.optString("src")
                                }
                            }
                            if (mediaUrl.isEmpty()) {
                                mediaUrl = node.optString("display_url")
                            }
                        }
                        
                        if (mediaUrl.isNotEmpty()) {
                            urls.add(unescapeString(mediaUrl))
                        }
                    }
                    if (urls.isNotEmpty()) {
                        return urls
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse edge_sidecar_to_children: ${e.message}", e)
        }

        // Attempt 2: "carousel_media"
        try {
            val carouselJson = extractCarouselMediaJson(htmlContent)
            if (carouselJson != null) {
                val carouselArray = JSONArray(carouselJson)
                if (carouselArray.length() > 0) {
                    val urls = mutableListOf<String>()
                    for (i in 0 until carouselArray.length()) {
                        val item = carouselArray.optJSONObject(i) ?: continue
                        var mediaUrl = ""
                        
                        val videoVersions = item.optJSONArray("video_versions")
                        if (videoVersions != null && videoVersions.length() > 0) {
                            val bestVideo = videoVersions.optJSONObject(0)
                            if (bestVideo != null) {
                                mediaUrl = bestVideo.optString("url")
                            }
                        }
                        
                        if (mediaUrl.isEmpty()) {
                            val imageVersions = item.optJSONObject("image_versions2")
                            val candidates = imageVersions?.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val bestImage = candidates.optJSONObject(0)
                                if (bestImage != null) {
                                    mediaUrl = bestImage.optString("url")
                                }
                            }
                        }
                        
                        if (mediaUrl.isEmpty()) {
                            mediaUrl = item.optString("display_url")
                        }
                        
                        if (mediaUrl.isNotEmpty()) {
                            urls.add(unescapeString(mediaUrl))
                        }
                    }
                    if (urls.isNotEmpty()) {
                        return urls
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse carousel_media: ${e.message}", e)
        }

        return null
    }

    fun extractRelevantHtmlSnippet(htmlContent: String): String {
        val targets = listOf("\"shortcode_media\"", "\"schedule_media\"", "\"edge_sidecar_to_children\"", "\"carousel_media\"", "display_url")
        var bestIndex = -1
        for (target in targets) {
            val idx = htmlContent.indexOf(target)
            if (idx != -1) {
                bestIndex = idx
                break
            }
        }
        
        if (bestIndex == -1) {
            return if (htmlContent.length > 50000) htmlContent.substring(0, 50000) else htmlContent
        }
        
        val start = (bestIndex - 5000).coerceAtLeast(0)
        val end = (bestIndex + 35000).coerceAtLeast(0).coerceAtMost(htmlContent.length)
        return htmlContent.substring(start, end)
    }

    fun extractRelevantProfileSnippet(htmlContent: String): String {
        val targets = listOf("\"profile_pic_url_hd\"", "\"profile_pic_url\"", "\"is_private\"", "logging_page_id")
        var bestIndex = -1
        for (target in targets) {
            val idx = htmlContent.indexOf(target)
            if (idx != -1) {
                bestIndex = idx
                break
            }
        }
        if (bestIndex == -1) {
            return if (htmlContent.length > 40000) htmlContent.substring(0, 40000) else htmlContent
        }
        val start = (bestIndex - 5000).coerceAtLeast(0)
        val end = (bestIndex + 30000).coerceAtLeast(0).coerceAtMost(htmlContent.length)
        return htmlContent.substring(start, end)
    }

    fun extractRelevantStorySnippet(htmlContent: String): String {
        val targets = listOf("\"video_resources\"", "\"display_url\"", "reel_id", "\"story_media\"", "stories")
        var bestIndex = -1
        for (target in targets) {
            val idx = htmlContent.indexOf(target)
            if (idx != -1) {
                bestIndex = idx
                break
            }
        }
        if (bestIndex == -1) {
            return if (htmlContent.length > 40000) htmlContent.substring(0, 40000) else htmlContent
        }
        val start = (bestIndex - 5000).coerceAtLeast(0)
        val end = (bestIndex + 30000).coerceAtLeast(0).coerceAtMost(htmlContent.length)
        return htmlContent.substring(start, end)
    }

    suspend fun parseWithRegex(url: String, htmlContent: String, shortcode: String): InstagramPost? = withContext(Dispatchers.IO) {
        try {
            // 1. Check og:video
            val videoMatcher = Pattern.compile("<meta[^>]*property=\"og:video\"[^>]*content=\"([^\"]+)\"").matcher(htmlContent)
            var videoUrl = if (videoMatcher.find()) unescapeString(videoMatcher.group(1) ?: "") else null

            // 1b. Check og:video:secure_url
            if (videoUrl.isNullOrEmpty()) {
                val videoSecureMatcher = Pattern.compile("<meta[^>]*property=\"og:video:secure_url\"[^>]*content=\"([^\"]+)\"").matcher(htmlContent)
                if (videoSecureMatcher.find()) {
                    videoUrl = unescapeString(videoSecureMatcher.group(1) ?: "")
                }
            }

            // 1c. Check twitter:player
            if (videoUrl.isNullOrEmpty()) {
                val twitterVideoMatcher = Pattern.compile("<meta[^>]*name=\"twitter:player\"[^>]*content=\"([^\"]+)\"").matcher(htmlContent)
                if (twitterVideoMatcher.find()) {
                    videoUrl = unescapeString(twitterVideoMatcher.group(1) ?: "")
                }
            }

            // 1d. Extract "video_url" directly from JSON script blocks
            if (videoUrl.isNullOrEmpty()) {
                val jsonVideoMatcher = Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\"").matcher(htmlContent)
                if (jsonVideoMatcher.find()) {
                    videoUrl = unescapeString(jsonVideoMatcher.group(1) ?: "")
                }
            }

            // 1e. Extract "src" of video_resources from JSON script blocks
            if (videoUrl.isNullOrEmpty()) {
                val videoResourcesMatcher = Pattern.compile("\"video_resources\"\\s*:\\s*\\[[^\\]]*\"src\"\\s*:\\s*\"([^\"]+)\"").matcher(htmlContent)
                if (videoResourcesMatcher.find()) {
                    videoUrl = unescapeString(videoResourcesMatcher.group(1) ?: "")
                }
            }

            // 1f. Match raw mp4 streaming asset inside quotes as a wildcard fallback
            if (videoUrl.isNullOrEmpty()) {
                val anyMp4Matcher = Pattern.compile("\"(https:\\\\?/\\\\?/[^\"]+?\\.mp4[^\"]*?)\"").matcher(htmlContent)
                if (anyMp4Matcher.find()) {
                    videoUrl = unescapeString(anyMp4Matcher.group(1) ?: "")
                }
            }

            // 2. Check og:image
            val imageMatcher = Pattern.compile("<meta[^>]*property=\"og:image\"[^>]*content=\"([^\"]+)\"").matcher(htmlContent)
            val imageUrl = if (imageMatcher.find()) unescapeString(imageMatcher.group(1) ?: "") else null

            // 3. Check og:title / Description for Caption
            var caption = ""
            val titleOptMatcher = Pattern.compile("<meta[^>]*property=\"og:title\"[^>]*content=\"([^\"]+)\"").matcher(htmlContent)
            if (titleOptMatcher.find()) {
                val candidate = unescapeString(titleOptMatcher.group(1) ?: "")
                if (!candidate.startsWith("http") && candidate.length < 250) {
                    caption = candidate.substringBefore(" on Instagram:")
                }
            }

            if (caption.isEmpty() || caption == "Instagram Post") {
                val descOptMatcher = Pattern.compile("<meta[^>]*property=\"og:description\"[^>]*content=\"([^\"]+)\"").matcher(htmlContent)
                if (descOptMatcher.find()) {
                    val candidate = unescapeString(descOptMatcher.group(1) ?: "")
                    if (!candidate.startsWith("http") && candidate.length < 250) {
                        caption = candidate
                    }
                }
            }

            // Cleanup caption if it contains CDN credentials, query signs or random long alphanumeric sequences
            if (caption.isEmpty() || caption.startsWith("http") || caption.contains("/") || caption.contains("#&") || caption.contains("?") || caption.length > 200 || caption.contains("sig=") || caption.contains("&oe=")) {
                caption = if (videoUrl != null && videoUrl.isNotEmpty()) "Instagram Video" else "Instagram Photo"
            }

            var downloads = mutableListOf<String>()
            var parsedType = "image"

            // A. Attempt robust JSON Carousel parsing first
            val carouselUrls = parseCarouselFromHtml(htmlContent)
            if (carouselUrls != null && carouselUrls.isNotEmpty()) {
                downloads = carouselUrls.toMutableList()
                parsedType = "carousel"
            } else {
                // B. Fallback to single video or single image
                if (videoUrl != null && videoUrl.isNotEmpty()) {
                    downloads.add(videoUrl)
                    parsedType = "video"
                } else if (imageUrl != null && imageUrl.isNotEmpty()) {
                    downloads.add(imageUrl)
                    parsedType = "image"
                }
            }

            if (downloads.isNotEmpty()) {
                val extractedUser = extractUsernameFromHtml(htmlContent) ?: "instagram_user"
                return@withContext InstagramPost(
                    mediaType = parsedType,
                    urls = downloads,
                    caption = caption,
                    username = extractedUser,
                    shortcode = shortcode
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Regex parsing failed: ", e)
        }
        return@withContext null
    }

    private fun extractUsernameFromHtml(htmlContent: String): String? {
        // Pattern 1: owner username structure {"username":"xxxx"} inside post info
        val ownerPattern = Pattern.compile("\"owner\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"username\"\\s*:\\s*\"([^\"]+)\"")
        val ownerMatcher = ownerPattern.matcher(htmlContent)
        if (ownerMatcher.find()) return ownerMatcher.group(1)

        // Pattern 2: simple "username":"xxxx" pattern (highly standard in sharedJS)
        val simpleUserPattern = Pattern.compile("\"username\"\\s*:\\s*\"([a-zA-Z0-9_\\.]+)\"")
        val userMatcher = simpleUserPattern.matcher(htmlContent)
        while (userMatcher.find()) {
            val candidate = userMatcher.group(1) ?: ""
            if (candidate.isNotEmpty() && candidate != "instagram_user" && candidate.length < 40) return candidate
        }

        // Pattern 3: og:description contains: "See Instagram photos and videos from Name (@username)"
        val descPattern = Pattern.compile("See Instagram photos and videos from [^(]*\\(@([a-zA-Z0-9_\\.]+)\\)")
        val descMatcher = descPattern.matcher(htmlContent)
        if (descMatcher.find()) return descMatcher.group(1)

        // Pattern 4: any instagram link pattern
        val linkPattern = Pattern.compile("instagram\\.com/([a-zA-Z0-9_\\.]+)/")
        val linkMatcher = linkPattern.matcher(htmlContent)
        while (linkMatcher.find()) {
            val cand = linkMatcher.group(1)
            if (cand != null && cand != "p" && cand != "reel" && cand != "stories" && cand != "tv" && cand != "explore") {
                return cand
            }
        }
        return null
    }

    suspend fun parseWithGemini(htmlContent: String, shortcode: String): InstagramPost? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API Key is not set or placeholder.")
            return@withContext null
        }

        // Keep content lightweight by focusing on the relevant data block
        val htmlSelection = extractRelevantHtmlSnippet(htmlContent)

        val prompt = """
            You are an expert Instagram HTML Parser. Your task is to inspect the provided raw HTML/JSON snippet of an Instagram post page, and extract the direct static download URL(s) for the media.
            
            Rules:
            1. If it is a video or reel: Look for the actual direct .mp4 video URL (often in <meta property="og:video"> or "video_url").
            2. If it is a single image: Look for the highest-resolution .jpg/webp display URL (often in <meta property="og:image"> or "display_url").
            3. If it is a carousel/multi-image post: Extract multiple media URLs in order.
            4. Be extremely precise: do not return any HTML container pages - only static direct content URLs (.mp4 or .jpg/webp).
            5. Clean and unescape any unicode sequences (like \u0026 to &).
            
            Provided HTML excerpt:
            $htmlSelection
            
            Identify the contents and output ONLY a valid parseable JSON in this exact structure, with no markdown code blocks or wrapper markers:
            {
              "media_type": "video" or "image" or "carousel",
              "urls": ["http_stream_url_1", "http_stream_url_2", ...],
              "caption": "caption content",
              "username": "profile_name"
            }
        """.trimIndent()

        val jsonRequest = JSONObject()
        val contentsArray = JSONArray()
        val partsArray = JSONArray()
        val partObject = JSONObject()
        partObject.put("text", prompt)
        partsArray.put(partObject)
        
        val contentObject = JSONObject()
        contentObject.put("parts", partsArray)
        contentsArray.put(contentObject)
        jsonRequest.put("contents", contentsArray)

        // Structure config
        val generationConfig = JSONObject()
        val responseFormat = JSONObject()
        responseFormat.put("mimeType", "application/json")
        generationConfig.put("responseFormat", responseFormat)
        jsonRequest.put("generationConfig", generationConfig)

        val requestBody = jsonRequest.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val resBody = response.body?.string() ?: ""
                val resJson = JSONObject(resBody)
                val text = resJson.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val parsedOutput = JSONObject(text.trim())
                val mediaType = parsedOutput.optString("media_type", "image")
                val jsonUrls = parsedOutput.optJSONArray("urls")
                val urls = mutableListOf<String>()
                if (jsonUrls != null) {
                    for (i in 0 until jsonUrls.length()) {
                        urls.add(jsonUrls.getString(i))
                    }
                }
                val caption = parsedOutput.optString("caption", "Instagram Post")
                val username = parsedOutput.optString("username", "instagram_user")

                if (urls.isNotEmpty()) {
                    return@withContext InstagramPost(
                        mediaType = mediaType,
                        urls = urls,
                        caption = caption,
                        username = username,
                        shortcode = shortcode
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini parser exception: ", e)
        }
        return@withContext null
    }

    fun extractUsernameFromStoryUrl(url: String): String? {
        val cleaned = cleanUrl(url)
        val storyPattern = Pattern.compile("instagram.com/stories/([^/]+)")
        val matcher = storyPattern.matcher(cleaned)
        return if (matcher.find()) {
            val username = matcher.group(1)
            if (username != "stories") username else null
        } else {
            null
        }
    }

    fun isProfilePictureUrl(url: String): Boolean {
        val lower = url.lowercase()
        val isSmallSize = lower.contains("150x150") || 
                           lower.contains("320x320") || 
                           lower.contains("50x50") || 
                           lower.contains("100x100") || 
                           lower.contains("200x200") || 
                           lower.contains("/c0.")
        val isAvatarOrPic = lower.contains("avatar") || 
                            lower.contains("profile_pic") || 
                            lower.contains("default_profile") || 
                            lower.contains("anonymous_user") ||
                            lower.contains("t51.2885-19") ||
                            lower.contains("t51.3637-19")
        return isSmallSize || (isAvatarOrPic && !lower.contains("story"))
    }

    suspend fun parseProfileWithGemini(htmlContent: String, username: String): InstagramPost? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API Key is not set or placeholder.")
            return@withContext null
        }

        // Keep content lightweight by focusing on the relevant profile keys
        val htmlSelection = extractRelevantProfileSnippet(htmlContent)

        val prompt = """
            You are parsing an Instagram Profile page HTML snippet for the account '$username'.
            Inspect the HTML and extract:
            1. The high-resolution profile photo URL (often labeled 'profile_pic_url_hd', 'profile_pic_url', or in og:image metadata).
            2. Check if the profile is private (look for "is_private": true or text mentioning private).
            
            Snippet:
            $htmlSelection
            
            Identify the information and output ONLY a valid parseable JSON in this exact structure, with no markdown code blocks or wrapper markers:
            {
              "media_type": "image",
              "urls": ["extracted_profile_photo_url"],
              "caption": "@$username profile picture",
              "username": "$username",
              "is_private": true
            }
        """.trimIndent()

        val jsonRequest = JSONObject()
        val contentsArray = JSONArray()
        val partsArray = JSONArray()
        val partObject = JSONObject()
        partObject.put("text", prompt)
        partsArray.put(partObject)
        
        val contentObject = JSONObject()
        contentObject.put("parts", partsArray)
        contentsArray.put(contentObject)
        jsonRequest.put("contents", contentsArray)

        val generationConfig = JSONObject()
        val responseFormat = JSONObject()
        responseFormat.put("mimeType", "application/json")
        generationConfig.put("responseFormat", responseFormat)
        jsonRequest.put("generationConfig", generationConfig)

        val requestBody = jsonRequest.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val resBody = response.body?.string() ?: ""
                val resJson = JSONObject(resBody)
                val text = resJson.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val parsedOutput = JSONObject(text.trim())
                val urlsArray = parsedOutput.optJSONArray("urls")
                val urls = mutableListOf<String>()
                if (urlsArray != null) {
                    for (i in 0 until urlsArray.length()) {
                        urls.add(urlsArray.getString(i))
                    }
                }
                val isPrivate = parsedOutput.optBoolean("is_private", false)
                if (isPrivate) {
                    return@withContext InstagramPost(
                        mediaType = "private",
                        urls = emptyList(),
                        caption = "Private Account",
                        username = username,
                        shortcode = "profile_$username"
                    )
                }

                if (urls.isNotEmpty()) {
                    return@withContext InstagramPost(
                        mediaType = "image",
                        urls = urls,
                        caption = "@$username profile picture",
                        username = username,
                        shortcode = "profile_$username"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini profile parser exception: ", e)
        }
        return@withContext null
    }

    suspend fun parseStoriesWithGemini(htmlContent: String, username: String): InstagramPost? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API Key is not set or placeholder.")
            return@withContext null
        }

        // Keep content lightweight by focusing on the relevant story video/image resources
        val htmlSelection = extractRelevantStorySnippet(htmlContent)

        val prompt = """
            You are parsing an Instagram Stories page HTML snippet for user '$username'.
            Inspect the HTML/script tags and extract all direct static video (.mp4) and image (.jpg/webp) story download URLs you can find belonging to actual story items.
            Exclude standard profile pic or empty wrappers.
            
            Snippet:
            $htmlSelection
            
            Identify the contents and output ONLY a valid parseable JSON in this exact structure, with no markdown code blocks or wrapper markers:
            {
              "media_type": "carousel",
              "urls": ["http_story_url_1", "http_story_url_2", ...],
              "caption": "@$username active stories",
              "username": "$username"
            }
        """.trimIndent()

        val jsonRequest = JSONObject()
        val contentsArray = JSONArray()
        val partsArray = JSONArray()
        val partObject = JSONObject()
        partObject.put("text", prompt)
        partsArray.put(partObject)
        
        val contentObject = JSONObject()
        contentObject.put("parts", partsArray)
        contentsArray.put(contentObject)
        jsonRequest.put("contents", contentsArray)

        val generationConfig = JSONObject()
        val responseFormat = JSONObject()
        responseFormat.put("mimeType", "application/json")
        generationConfig.put("responseFormat", responseFormat)
        jsonRequest.put("generationConfig", generationConfig)

        val requestBody = jsonRequest.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val resBody = response.body?.string() ?: ""
                val resJson = JSONObject(resBody)
                val text = resJson.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val parsedOutput = JSONObject(text.trim())
                val urlsArray = parsedOutput.optJSONArray("urls")
                val urls = mutableListOf<String>()
                if (urlsArray != null) {
                    for (i in 0 until urlsArray.length()) {
                        val parsedUrl = urlsArray.getString(i)
                        if (!isProfilePictureUrl(parsedUrl)) {
                            urls.add(parsedUrl)
                        }
                    }
                }
                val caption = parsedOutput.optString("caption", "@$username active stories")

                if (urls.isNotEmpty()) {
                    return@withContext InstagramPost(
                        mediaType = "carousel",
                        urls = urls,
                        caption = caption,
                        username = username,
                        shortcode = "stories_$username"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini story parser exception: ", e)
        }
        return@withContext null
    }

    private suspend fun fetchHtmlWithFallbacks(
        primaryUrl: String,
        cookie: String?,
        fallbacks: List<String>
    ): String {
        val requestBuilder = Request.Builder()
            .url(primaryUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")

        if (!cookie.isNullOrEmpty()) {
            requestBuilder.header("Cookie", cookie)
        }

        try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val isLoginRequired = body.contains("login_page") || 
                                      body.contains("accounts/login") || 
                                      body.contains("<title>Login") ||
                                      body.contains("instagram.com/login")
                if (!isLoginRequired && body.isNotEmpty()) {
                    Log.d(TAG, "Successfully fetched from primary URL: $primaryUrl")
                    return body
                } else {
                    Log.d(TAG, "Primary URL returned a login gate. Trying bypass gateways...")
                }
            } else {
                Log.d(TAG, "Primary URL returned status code ${response.code}. Trying bypass gateways...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Primary URL fetch failed: ${e.message}. Trying bypass gateways...")
        }

        // Try bypass gateways sequentially with no cookie requirements
        for (fallbackUrl in fallbacks) {
            Log.d(TAG, "Attempting bypass fetch via: $fallbackUrl")
            val fallbackBuilder = Request.Builder()
                .url(fallbackUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")

            try {
                val response = okHttpClient.newCall(fallbackBuilder.build()).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val isLoginRequired = body.contains("login_page") || 
                                          body.contains("accounts/login") || 
                                          body.contains("<title>Login")
                    if (body.isNotEmpty() && !isLoginRequired) {
                        Log.d(TAG, "Successfully bypassed via gateway: $fallbackUrl")
                        return body
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bypass gateway request failed for $fallbackUrl: ${e.message}")
            }
        }

        throw Exception("Could not locate media or connection blocked. Private account or Instagram limit exceeded.")
    }

    suspend fun fetchInstagramProfilePicture(context: Context, username: String, cookie: String? = null): Result<InstagramPost> = withContext(Dispatchers.IO) {
        val cleanedUsername = username.trim().removePrefix("@").split("?")[0]
        if (cleanedUsername.isEmpty()) {
            return@withContext Result.failure(Exception("Username cannot be empty."))
        }
        val url = "https://www.instagram.com/$cleanedUsername/"
        val fallbacks = listOf(
            "https://www.ddinstagram.com/$cleanedUsername/",
            "https://www.vxinstagram.com/$cleanedUsername/"
        )

        try {
            val htmlContent = fetchHtmlWithFallbacks(url, cookie, fallbacks)

            val isPrivate = htmlContent.contains("\"is_private\":true") || 
                            htmlContent.contains("\"is_private\": true") || 
                            htmlContent.contains("This Account is Private")
            
            val isNotFound = htmlContent.contains("Page Not Found") || htmlContent.contains("page_not_found")
            if (isNotFound) {
                return@withContext Result.failure(Exception("Instagram profile not found: '@$cleanedUsername' does not exist."))
            }

            // Extract profile image via standard regex
            val ogImageMatcher = Pattern.compile("<meta[^>]*property=\"og:image\"[^>]*content=\"([^\"]+)\"").matcher(htmlContent)
            var profilePicUrl = if (ogImageMatcher.find()) unescapeString(ogImageMatcher.group(1) ?: "") else null

            if (profilePicUrl == null) {
                val hdPicMatcher = Pattern.compile("\"profile_pic_url_hd\"\\s*:\\s*\"([^\"]+)\"").matcher(htmlContent)
                if (hdPicMatcher.find()) {
                    profilePicUrl = unescapeString(hdPicMatcher.group(1) ?: "")
                }
            }

            if (profilePicUrl.isNullOrEmpty()) {
                val geminiPost = parseProfileWithGemini(htmlContent, cleanedUsername)
                if (geminiPost != null) {
                    if (isPrivate && geminiPost.mediaType == "private") {
                        return@withContext Result.failure(Exception("This profile is private or does not exist."))
                    }
                    return@withContext Result.success(geminiPost)
                }
            } else {
                if (isPrivate) {
                    return@withContext Result.failure(Exception("This Account is Private. Cannot display details for this account."))
                }
                return@withContext Result.success(
                    InstagramPost(
                        mediaType = "image",
                        urls = listOf(profilePicUrl),
                        caption = "@$cleanedUsername profile picture",
                        username = cleanedUsername,
                        shortcode = "profile_${cleanedUsername}"
                    )
                )
            }

            return@withContext Result.failure(Exception("Could not extract profile picture. Instagram block occurred or login required."))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    private fun parseStoriesDetailed(htmlContent: String): List<String> {
        val urls = mutableListOf<String>()

        // 1. Locate all occurrences of story nodes in the JSON by finding item id patterns.
        // Story items usually have "id":"3123456789_12345" or similar unique IDs.
        val idPattern = Pattern.compile("\"id\"\\s*:\\s*\"([0-9_]+)\"")
        val matcher = idPattern.matcher(htmlContent)
        val indices = mutableListOf<Int>()
        while (matcher.find()) {
            indices.add(matcher.start())
        }

        if (indices.isEmpty()) {
            return emptyList()
        }

        indices.add(htmlContent.length)

        // 2. Fragment the content into separate segments per story item
        for (i in 0 until indices.size - 1) {
            val start = indices[i]
            val end = indices[i + 1]
            val segment = htmlContent.substring(start, end)

            // Step 2a: Check if we have a direct high-quality video in this segment
            var mediaUrl: String? = null
            
            val vvPattern = Pattern.compile("\"video_versions\"\\s*:\\s*\\[[^\\}]*?\"url\"\\s*:\\s*\"([^\"]+)\"")
            val vvMatch = vvPattern.matcher(segment)
            if (vvMatch.find()) {
                val found = unescapeString(vvMatch.group(1) ?: "")
                if (found.startsWith("http") && !isProfilePictureUrl(found)) {
                    mediaUrl = found
                }
            }

            // Step 2b: If no videos, hunt for direct high-quality image_versions2
            if (mediaUrl == null) {
                val ivPattern = Pattern.compile("\"image_versions2\"\\s*:\\s*\\{\\s*\"candidates\"\\s*:\\s*\\[[^\\}]*?\"url\"\\s*:\\s*\"([^\"]+)\"")
                val ivMatch = ivPattern.matcher(segment)
                if (ivMatch.find()) {
                    val found = unescapeString(ivMatch.group(1) ?: "")
                    if (found.startsWith("http") && !isProfilePictureUrl(found)) {
                        mediaUrl = found
                    }
                }
            }

            // Step 2c: Fallback to general display_url
            if (mediaUrl == null) {
                val displayPattern = Pattern.compile("\"display_url\"\\s*:\\s*\"([^\"]+)\"")
                val displayMatch = displayPattern.matcher(segment)
                if (displayMatch.find()) {
                    val found = unescapeString(displayMatch.group(1) ?: "")
                    if (found.startsWith("http") && !isProfilePictureUrl(found)) {
                        mediaUrl = found
                    }
                }
            }

            if (mediaUrl != null && !urls.contains(mediaUrl)) {
                urls.add(mediaUrl)
            }
        }
        
        return urls
    }

    suspend fun fetchInstagramStories(context: Context, username: String, cookie: String? = null): Result<InstagramPost> = withContext(Dispatchers.IO) {
        val cleanedUsername = username.trim().removePrefix("@").split("?")[0]
        if (cleanedUsername.isEmpty()) {
            return@withContext Result.failure(Exception("Username cannot be empty."))
        }
        val url = "https://www.instagram.com/stories/$cleanedUsername/"
        val fallbacks = listOf(
            "https://www.ddinstagram.com/stories/$cleanedUsername/",
            "https://www.vxinstagram.com/stories/$cleanedUsername/"
        )

        try {
            val htmlContent = fetchHtmlWithFallbacks(url, cookie, fallbacks)

            // Strategy 1: Targeted high-quality segmented JSON parser
            var foundUrls = parseStoriesDetailed(htmlContent).toMutableList()

            // Strategy 2: If segmented parser yielded nothing, fall back to global regex sweeps
            if (foundUrls.isEmpty()) {
                Log.d(TAG, "Segmented story parser returned 0 items. Falling back to global pattern sweeps.")
                
                // Search video_versions first if logged in
                val videoVersionsPattern = Pattern.compile("\"video_versions\"\\s*:\\s*\\[[^\\}]*?\"url\"\\s*:\\s*\"([^\"]+)\"")
                val vvMatcher = videoVersionsPattern.matcher(htmlContent)
                while (vvMatcher.find()) {
                    val videoUrl = unescapeString(vvMatcher.group(1) ?: "")
                    if (videoUrl.startsWith("http") && !isProfilePictureUrl(videoUrl) && !foundUrls.contains(videoUrl)) {
                        foundUrls.add(videoUrl)
                    }
                }

                // Search video_resources second
                val videoResourcePattern = Pattern.compile("\"video_resources\"\\s*:\\s*\\[[^\\]]*\"src\"\\s*:\\s*\"([^\"]+)\"")
                val videoMatcher = videoResourcePattern.matcher(htmlContent)
                while (videoMatcher.find()) {
                    val videoUrl = unescapeString(videoMatcher.group(1) ?: "")
                    if (videoUrl.startsWith("http") && !isProfilePictureUrl(videoUrl) && !foundUrls.contains(videoUrl)) {
                        foundUrls.add(videoUrl)
                    }
                }

                // Wildcard search for raw mp4 streaming assets in stories HTML
                val anyMp4Matcher = Pattern.compile("\"(https:\\\\?/\\\\?/[^\"]+?\\.mp4[^\"]*?)\"").matcher(htmlContent)
                while (anyMp4Matcher.find()) {
                    val videoUrl = unescapeString(anyMp4Matcher.group(1) ?: "")
                    if (videoUrl.startsWith("http") && !isProfilePictureUrl(videoUrl) && !foundUrls.contains(videoUrl)) {
                        foundUrls.add(videoUrl)
                    }
                }

                // Search display_url second
                val displayPattern = Pattern.compile("\"display_url\"\\s*:\\s*\"([^\"]+)\"")
                val displayMatcher = displayPattern.matcher(htmlContent)
                while (displayMatcher.find()) {
                    val dispUrl = unescapeString(displayMatcher.group(1) ?: "")
                    if (dispUrl.startsWith("http") && !isProfilePictureUrl(dispUrl) && !foundUrls.contains(dispUrl)) {
                        foundUrls.add(dispUrl)
                    }
                }
            }

            // Strategy 3: Fallback meta tag search (this is critical when fetched from bypass gateways like ddinstagram/vxinstagram!)
            if (foundUrls.isEmpty()) {
                val metaVideoPattern = Pattern.compile("<meta[^>]*property=\"og:video(?::secure_url)?\"[^>]*content=\"([^\"]+)\"")
                val metaVideoMatcher = metaVideoPattern.matcher(htmlContent)
                while (metaVideoMatcher.find()) {
                    val urlCandidate = unescapeString(metaVideoMatcher.group(1) ?: "")
                    if (urlCandidate.startsWith("http") && !isProfilePictureUrl(urlCandidate) && !foundUrls.contains(urlCandidate)) {
                        foundUrls.add(urlCandidate)
                    }
                }
            }
            if (foundUrls.isEmpty()) {
                val metaImagePattern = Pattern.compile("<meta[^>]*property=\"og:image\"[^>]*content=\"([^\"]+)\"")
                val metaImageMatcher = metaImagePattern.matcher(htmlContent)
                while (metaImageMatcher.find()) {
                    val urlCandidate = unescapeString(metaImageMatcher.group(1) ?: "")
                    if (urlCandidate.startsWith("http") && !isProfilePictureUrl(urlCandidate) && !foundUrls.contains(urlCandidate)) {
                        foundUrls.add(urlCandidate)
                    }
                }
            }
            if (foundUrls.isEmpty()) {
                val videoTagPattern = Pattern.compile("<(?:video|source)[^>]*src=\"([^\"]+)\"")
                val videoTagMatcher = videoTagPattern.matcher(htmlContent)
                while (videoTagMatcher.find()) {
                    val urlCandidate = unescapeString(videoTagMatcher.group(1) ?: "")
                    if (urlCandidate.startsWith("http") && !isProfilePictureUrl(urlCandidate) && !foundUrls.contains(urlCandidate)) {
                        foundUrls.add(urlCandidate)
                    }
                }
            }
            if (foundUrls.isEmpty()) {
                val imgTagPattern = Pattern.compile("<img[^>]*src=\"([^\"]+)\"")
                val imgTagMatcher = imgTagPattern.matcher(htmlContent)
                while (imgTagMatcher.find()) {
                    val urlCandidate = unescapeString(imgTagMatcher.group(1) ?: "")
                    if (urlCandidate.startsWith("http") && !urlCandidate.contains("avatar") && !urlCandidate.contains("logo") && !urlCandidate.contains("icon") && !isProfilePictureUrl(urlCandidate) && !foundUrls.contains(urlCandidate)) {
                        foundUrls.add(urlCandidate)
                    }
                }
            }

            val cleanStoryUrls = foundUrls.filter { url ->
                !isProfilePictureUrl(url) && 
                !url.lowercase().contains("t51.2885-19") && 
                !url.lowercase().contains("t51.3637-19")
            }

            if (cleanStoryUrls.isEmpty()) {
                val geminiPost = parseStoriesWithGemini(htmlContent, cleanedUsername)
                if (geminiPost != null && geminiPost.urls.any { !isProfilePictureUrl(it) }) {
                    return@withContext Result.success(geminiPost)
                }
            } else {
                return@withContext Result.success(
                    InstagramPost(
                        mediaType = "carousel",
                        urls = cleanStoryUrls.take(15), // active stories limit
                        caption = "@$cleanedUsername stories feed",
                        username = cleanedUsername,
                        shortcode = "stories_${cleanedUsername}"
                    )
                )
            }

            return@withContext Result.failure(Exception("No active stories found for @$cleanedUsername. Downloading stories requires a valid Instagram Session Cookie. Go to Settings to enter your cookie."))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun fetchInstagramPost(context: Context, url: String, cookie: String? = null): Result<InstagramPost> = withContext(Dispatchers.IO) {
        val shortcode = extractShortcode(url) ?: return@withContext Result.failure(Exception("Invalid Instagram URL или shortcode missing."))
        val cleanedUrl = "https://www.instagram.com/p/$shortcode/"
        val fallbacks = listOf(
            "https://www.ddinstagram.com/p/$shortcode/",
            "https://www.vxinstagram.com/p/$shortcode/",
            "https://www.ddinstagram.com/reel/$shortcode/",
            "https://www.vxinstagram.com/reel/$shortcode/"
        )

        try {
            val htmlContent = fetchHtmlWithFallbacks(cleanedUrl, cookie, fallbacks)

            // Strategy 1: Fast Regex Matching
            var post = parseWithRegex(url, htmlContent, shortcode)
            if (post != null) {
                val isProbablyVideo = url.contains("/reel/", ignoreCase = true) || url.contains("/tv/", ignoreCase = true)
                if (isProbablyVideo && post.mediaType == "image") {
                    Log.d(TAG, "Regex extracted an image, but this is a Reel/TV URL. Bypassing match to allow Gemini video parsing.")
                } else {
                    return@withContext Result.success(post)
                }
            }

            // Strategy 2: AI Gemini Model Extraction (Fallback)
            post = parseWithGemini(htmlContent, shortcode)
            if (post != null) {
                return@withContext Result.success(post)
            }

            return@withContext Result.failure(Exception("Failed to locate media links. Try putting an Instagram Session Cookie in Settings for secure auth."))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun downloadMediaFile(
        context: Context,
        mediaUrl: String,
        filename: String,
        isVideo: Boolean,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(mediaUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to download file: Server returned code ${response.code}"))
            }

            val responseBody = response.body ?: return@withContext Result.failure(Exception("Empty download body."))
            val totalBytes = responseBody.contentLength()
            val inputStream = responseBody.byteStream()

            // Save to Public Scoped Gallery Directories using MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativeFolder = if (isVideo) "Download/SaveTube/Instagram/SaveTube Video" else "Download/SaveTube/Instagram/SaveTube Image"
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collectionUri, contentValues)
                ?: return@withContext Result.failure(Exception("Failed to register media in database via MediaStore."))

            var downloadedBytes = 0L
            resolver.openOutputStream(itemUri).use { outputStream ->
                if (outputStream == null) return@withContext Result.failure(Exception("Failed to open output stream."))
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        onProgress(downloadedBytes.toFloat() / totalBytes)
                    }
                }
                outputStream.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            // Also keep a copy or reference file in App storage to represent exact path for Room db listing
            val appStorageBase = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SaveTube/Instagram")
            val subFolder = if (isVideo) "SaveTube Video" else "SaveTube Image"
            val appStorageDir = File(appStorageBase, subFolder)
            if (!appStorageDir.exists()) appStorageDir.mkdirs()
            val localCopy = File(appStorageDir, filename)
            
            // Fast bridge local copy metadata, or write it directly
            resolver.openInputStream(itemUri).use { insideStream ->
                if (insideStream != null) {
                    FileOutputStream(localCopy).use { localOut ->
                        insideStream.copyTo(localOut)
                    }
                }
            }

            return@withContext Result.success(localCopy)
        } catch (e: Exception) {
            Log.e(TAG, "File download error: ", e)
            return@withContext Result.failure(e)
        }
    }
}
