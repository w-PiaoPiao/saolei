package com.wpiaopiao.saolei.core

/**
 * 键值存储抽象：core 保持纯 JVM 可测，app 侧用 SharedPreferences 实现。
 */
interface Storage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}
