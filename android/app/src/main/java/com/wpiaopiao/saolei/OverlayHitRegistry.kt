package com.wpiaopiao.saolei

/** 命中矩形（纯 JVM 数据类；Android 绘制侧由 RectF 转换而来，便于单测）。 */
data class HitRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * 覆盖层可点击区域登记表。
 *
 * 约定：绘制时登记，**后登记者优先**——子元素（如自定义雷区的 ◀ ▶ 小按钮）必须在父行矩形之后登记，
 * 这样点在子元素上时不会先命中父矩形。详见 docs/review-2026-10-27/fixes/android-app.md。
 */
class OverlayHitRegistry {

    private val rects = ArrayList<HitRect>()
    private val actions = ArrayList<(() -> Unit)?>()

    val size: Int get() = rects.size

    fun reset() {
        rects.clear()
        actions.clear()
    }

    fun add(rect: HitRect, action: (() -> Unit)?) {
        rects.add(rect)
        actions.add(action)
    }

    fun add(left: Float, top: Float, right: Float, bottom: Float, action: (() -> Unit)?) {
        add(HitRect(left, top, right, bottom), action)
    }

    /** 返回命中下标；反向遍历 = 后登记优先。未命中返回 null。 */
    fun hitAt(x: Float, y: Float): Int? {
        for (i in rects.indices.reversed()) {
            if (rects[i].contains(x, y)) return i
        }
        return null
    }

    fun actionAt(index: Int): (() -> Unit)? = actions[index]
}
