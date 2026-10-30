package com.wpiaopiao.saolei

import com.wpiaopiao.saolei.core.GameEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundSnapshotCodecTest {

    private fun snapshot(rows: Int = 9, cols: Int = 9): GameEngine.Snapshot {
        val size = rows * cols
        val mines = BooleanArray(size) { it % 7 == 0 }
        val revealed = BooleanArray(size) { it % 3 == 0 }
        val flagged = BooleanArray(size) { it % 11 == 0 }
        val questioned = BooleanArray(size) { it == 4 }
        val exploded = BooleanArray(size) { it == 5 }
        val wrongFlag = BooleanArray(size) { it == 6 }
        return GameEngine.Snapshot(
            rows = rows,
            cols = cols,
            totalMines = 10,
            currentLevel = "custom",
            currentCustom = GameEngine.CustomConfig(rows, cols, 10),
            state = "playing",
            minesPlaced = true,
            timer = 42,
            flagCount = 3,
            revealedCount = 20,
            bbbv = 17,
            noGuessFallback = false,
            wonByFlags = true,
            mines = mines,
            revealed = revealed,
            flagged = flagged,
            questioned = questioned,
            exploded = exploded,
            wrongFlag = wrongFlag,
        )
    }

    @Test
    fun `roundtrip keeps every field`() {
        val original = snapshot()
        val restored = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(original))!!
        assertEquals(original.rows, restored.rows)
        assertEquals(original.cols, restored.cols)
        assertEquals(original.totalMines, restored.totalMines)
        assertEquals(original.currentLevel, restored.currentLevel)
        assertEquals(original.currentCustom?.rows, restored.currentCustom?.rows)
        assertEquals(original.currentCustom?.cols, restored.currentCustom?.cols)
        assertEquals(original.currentCustom?.mines, restored.currentCustom?.mines)
        assertEquals(original.state, restored.state)
        assertEquals(original.minesPlaced, restored.minesPlaced)
        assertEquals(original.timer, restored.timer)
        assertEquals(original.flagCount, restored.flagCount)
        assertEquals(original.revealedCount, restored.revealedCount)
        assertEquals(original.bbbv, restored.bbbv)
        assertEquals(original.noGuessFallback, restored.noGuessFallback)
        assertEquals(original.wonByFlags, restored.wonByFlags)
        assertArrayEquals(original.mines, restored.mines)
        assertArrayEquals(original.revealed, restored.revealed)
        assertArrayEquals(original.flagged, restored.flagged)
        assertArrayEquals(original.questioned, restored.questioned)
        assertArrayEquals(original.exploded, restored.exploded)
        assertArrayEquals(original.wrongFlag, restored.wrongFlag)
    }

    @Test
    fun `preset level without custom config roundtrips`() {
        val original = GameEngine.Snapshot(
            rows = 9, cols = 9, totalMines = 10,
            currentLevel = "beginner", currentCustom = null,
            state = "playing", minesPlaced = true, timer = 5,
            flagCount = 0, revealedCount = 1, bbbv = 2,
            noGuessFallback = true, wonByFlags = false,
            mines = BooleanArray(81), revealed = BooleanArray(81),
            flagged = BooleanArray(81), questioned = BooleanArray(81),
            exploded = BooleanArray(81), wrongFlag = BooleanArray(81),
        )
        val restored = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(original))!!
        assertNull(restored.currentCustom)
        assertEquals("beginner", restored.currentLevel)
        assertTrue(restored.noGuessFallback)
        assertFalse(restored.wonByFlags)
    }

    @Test
    fun `corrupted or incompatible input returns null instead of throwing`() {
        assertNull(RoundSnapshotCodec.decode(null))
        assertNull(RoundSnapshotCodec.decode(""))
        assertNull(RoundSnapshotCodec.decode("garbage"))
        assertNull(RoundSnapshotCodec.decode("v1|9|9"))
        // 版本不符
        assertNull(RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(snapshot()).replaceFirst("v1", "v9")))
        // 数组长度不符（Base64 "AAAA" 只有 3 字节，而 9×9 需要 81 字节）
        assertNull(RoundSnapshotCodec.decode("v1|9|9|10|custom|-|playing|1|0|0|0|0|0|0|AAAA|AAAA|AAAA|AAAA|AAAA|AAAA"))
    }
}
