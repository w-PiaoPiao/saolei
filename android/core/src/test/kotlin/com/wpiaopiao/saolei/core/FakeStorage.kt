package com.wpiaopiao.saolei.core

/** 内存版 Storage，供单元测试使用。 */
class FakeStorage : Storage {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) {
        map[key] = value
    }
}
