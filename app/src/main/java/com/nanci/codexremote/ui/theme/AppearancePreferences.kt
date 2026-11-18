package com.nanci.codexremote.ui.theme

import android.content.Context
import android.content.SharedPreferences

enum class AppColorMode(val storedValue: String, val label: String) {
    Light("light", "浅色"),
    Dark("dark", "深色"),
}

/** Device-local appearance. Deliberately independent of server and system night mode. */
class AppearancePreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    )

    fun read(): AppColorMode = when (runCatching { preferences.getString("color_mode", null) }.getOrNull()) {
        AppColorMode.Dark.storedValue -> AppColorMode.Dark
        else -> AppColorMode.Light
    }

    fun save(mode: AppColorMode) {
        preferences.edit().putString("color_mode", mode.storedValue).apply()
    }
}
