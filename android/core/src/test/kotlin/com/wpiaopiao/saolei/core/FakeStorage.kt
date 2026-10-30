package com.wpiaopiao.saolei.core

/** 内存版 Storage，供单元测试使用；带写/删计数便于断言"单次事件只写一次"。 */
class FakeStorage : Storage {
    val map = mutableMapOf<String, String>()
    var putCount = 0
        private set
    var removeCount = 0
        private set

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        putCount++
        map[key] = value
    }

    override fun remove(key: String) {
        removeCount++
        map.remove(key)
    }
}
