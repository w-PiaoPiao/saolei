package com.wpiaopiao.saolei

import android.content.SharedPreferences
import com.wpiaopiao.saolei.core.Storage

/** Storage 的 SharedPreferences 实现（core 保持纯 JVM 可测）。 */
class PrefsStorage(private val prefs: SharedPreferences) : Storage {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
