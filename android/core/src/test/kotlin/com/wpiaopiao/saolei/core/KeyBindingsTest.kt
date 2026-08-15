package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键位系统测试：默认映射、冲突检测、持久化、恢复默认。 */
class KeyBindingsTest {

    @Test
    fun `默认映射符合 KPA 要求`() {
        val kb = KeyBindings(FakeStorage())
        assertEquals(KeyCodes.DPAD_UP, kb.getKeyCode("moveUp"))
        assertEquals(KeyCodes.DPAD_DOWN, kb.getKeyCode("moveDown"))
        assertEquals(KeyCodes.DPAD_LEFT, kb.getKeyCode("moveLeft"))
        assertEquals(KeyCodes.DPAD_RIGHT, kb.getKeyCode("moveRight"))
        assertEquals(KeyCodes.BUTTON_B, kb.getKeyCode("activate"))
        assertEquals(KeyCodes.BUTTON_A, kb.getKeyCode("flag"))
        assertEquals(KeyCodes.BUTTON_START, kb.getKeyCode("newGame"))
        assertEquals(KeyCodes.BUTTON_SELECT, kb.getKeyCode("menu"))
    }

    @Test
    fun `显示名`() {
        val kb = KeyBindings(FakeStorage())
        assertEquals("B", kb.getDisplayKey("activate"))
        assertEquals("A", kb.getDisplayKey("flag"))
        assertEquals("Start", kb.getDisplayKey("newGame"))
        assertEquals("上", kb.getDisplayKey("moveUp"))
    }

    @Test
    fun `动作查键与键查动作互逆`() {
        val kb = KeyBindings(FakeStorage())
        for (action in KeyBindings.ACTIONS) {
            val code = kb.getKeyCode(action)
            assertNotNull(code)
            assertEquals(action, kb.getActionForKey(code!!))
        }
    }

    @Test
    fun `冲突检测：同键被两个动作占用时报出另一个动作`() {
        val kb = KeyBindings(FakeStorage())
        // 把 flag 绑到 B（activate 已占用）→ 冲突
        val conflict = kb.findConflict("flag", KeyCodes.BUTTON_B)
        assertEquals("activate", conflict)
        // 换成 X → 无冲突
        assertNull(kb.findConflict("flag", KeyCodes.BUTTON_X))
    }

    @Test
    fun `设置键位并持久化，重建后仍生效`() {
        val storage = FakeStorage()
        val kb = KeyBindings(storage)
        assertTrue(kb.setKey("activate", KeyCodes.BUTTON_X))
        assertEquals(KeyCodes.BUTTON_X, kb.getKeyCode("activate"))

        val kb2 = KeyBindings(storage)
        assertEquals(KeyCodes.BUTTON_X, kb2.getKeyCode("activate"))
        // 未改动的键保持默认
        assertEquals(KeyCodes.BUTTON_A, kb2.getKeyCode("flag"))
    }

    @Test
    fun `恢复默认`() {
        val storage = FakeStorage()
        val kb = KeyBindings(storage)
        kb.setKey("flag", KeyCodes.BUTTON_Y)
        kb.reset()
        kb.save()
        val kb2 = KeyBindings(storage)
        assertEquals(KeyCodes.BUTTON_A, kb2.getKeyCode("flag"))
    }

    @Test
    fun `损坏的持久化数据被安全忽略`() {
        val storage = FakeStorage()
        storage.putString(KeyBindings.STORAGE_KEY, "moveUp:abc,unknown:19,flag:")
        val kb = KeyBindings(storage)
        // 非法值忽略，保持默认
        assertEquals(KeyCodes.DPAD_UP, kb.getKeyCode("moveUp"))
        assertEquals(KeyCodes.BUTTON_A, kb.getKeyCode("flag"))
    }

    @Test
    fun `未知动作设置键位失败`() {
        val kb = KeyBindings(FakeStorage())
        assertFalse(kb.setKey("noSuchAction", KeyCodes.BUTTON_X))
    }
}
