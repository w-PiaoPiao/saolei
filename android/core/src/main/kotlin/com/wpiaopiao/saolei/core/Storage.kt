package com.wpiaopiao.saolei.core

/**
 * 键值存储抽象：core 保持纯 JVM 可测，app 侧用 SharedPreferences 实现。
 */
interface Storage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)

    /** 删除键（真正回到「无值」状态，替代用空串当哨兵的做法） */
    fun remove(key: String)
}
