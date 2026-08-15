package com.wpiaopiao.saolei.core

/**
 * 键位绑定：动作 → 物理键码，可自定义并持久化。
 * 默认（KPA 掌机）：方向键移动，B=翻开/和弦，A=标雷，Start=新游戏，Select=菜单。
 * 由桌面版 src/keybindings.js 移植（localStorage 换成 Storage 抽象）。
 */
class KeyBindings(private val storage: Storage) {

    data class Binding(val keyCode: Int, val label: String)

    companion object {
        const val STORAGE_KEY = "bindings"

        val ACTIONS = listOf(
            "moveUp", "moveDown", "moveLeft", "moveRight",
            "activate", "flag", "newGame", "menu"
        )

        val DEFAULTS: Map<String, Binding> = mapOf(
            "moveUp" to Binding(KeyCodes.DPAD_UP, "上移"),
            "moveDown" to Binding(KeyCodes.DPAD_DOWN, "下移"),
            "moveLeft" to Binding(KeyCodes.DPAD_LEFT, "左移"),
            "moveRight" to Binding(KeyCodes.DPAD_RIGHT, "右移"),
            "activate" to Binding(KeyCodes.BUTTON_B, "翻开/和弦"),
            "flag" to Binding(KeyCodes.BUTTON_A, "标雷"),
            "newGame" to Binding(KeyCodes.BUTTON_START, "新游戏"),
            "menu" to Binding(KeyCodes.BUTTON_SELECT, "菜单")
        )
    }

    private val bindings = mutableMapOf<String, Binding>()

    init {
        reset()
        load()
    }

    fun reset() {
        bindings.clear()
        bindings.putAll(DEFAULTS.mapValues { it.value.copy() })
    }

    fun load() {
        val raw = storage.getString(STORAGE_KEY) ?: return
        for (part in raw.split(",")) {
            val pair = part.split(":")
            if (pair.size != 2) continue
            val action = pair[0]
            val code = pair[1].toIntOrNull() ?: continue
            if (bindings.containsKey(action) && code >= 0) {
                bindings[action] = bindings[action]!!.copy(keyCode = code)
            }
        }
    }

    fun save() {
        val encoded = bindings.entries.joinToString(",") { (a, b) -> "$a:${b.keyCode}" }
        storage.putString(STORAGE_KEY, encoded)
    }

    fun getKeyCode(action: String): Int? = bindings[action]?.keyCode

    fun getDisplayKey(action: String): String {
        val code = getKeyCode(action) ?: return "-"
        return KeyNames.nameOf(code)
    }

    fun setKey(action: String, newCode: Int): Boolean {
        if (!bindings.containsKey(action)) return false
        bindings[action] = bindings[action]!!.copy(keyCode = newCode)
        save()
        return true
    }

    /** 返回与新键冲突的既有动作名；无冲突返回 null。 */
    fun findConflict(action: String, newCode: Int): String? {
        for ((a, b) in bindings) {
            if (a != action && b.keyCode == newCode) return a
        }
        return null
    }

    fun getActionForKey(code: Int): String? {
        for ((action, b) in bindings) {
            if (b.keyCode == code) return action
        }
        return null
    }

    fun getBindings(): Map<String, Binding> = bindings.toMap()
}
