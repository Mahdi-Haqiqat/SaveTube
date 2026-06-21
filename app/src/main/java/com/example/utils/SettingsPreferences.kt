package com.example.utils

import android.content.Context
import android.content.SharedPreferences

class SettingsPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("insta_downloader_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_COOKIES = "instagram_cookies"
        private const val KEY_AUTO_CLIPBOARD = "auto_clipboard"
        private const val KEY_GEMINI_FALLBACK = "gemini_fallback"
    }

    var instagramCookies: String
        get() = prefs.getString(KEY_COOKIES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_COOKIES, value).apply()

    var isAutoClipboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLIPBOARD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLIPBOARD, value).apply()

    var isGeminiFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_GEMINI_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_GEMINI_FALLBACK, value).apply()
}
