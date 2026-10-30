package com.wpiaopiao.saolei

/**
 * 和弦失败抖动（失败时周围未翻开格左右轻晃）的纯函数实现。
 *
 * 参数与桌面版 `src/style.css` 的 `@keyframes shake` 对齐：总时长 300ms、
 * 水平偏移 0 → -3px → +3px → -2px → +2px → 0，关键帧之间线性插值。
 * 抽成纯对象便于单元测试，也避免绘制热路径里重复写插值逻辑。
 */
object ShakeAnimation {

    /** 与桌面 `.cell.shake { animation: shake 0.3s ease; }` 对齐 */
    const val DURATION_MS = 300L

    /** 桌面关键帧百分比（0%,20%,40%,60%,80%,100%） */
    private val KEYFRAME_PROGRESS = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)

    /** 对应的水平位移（px），与桌面 `translateX(-3px/3px/-2px/2px)` 一致 */
    private val KEYFRAME_OFFSET = floatArrayOf(0f, -3f, 3f, -2f, 2f, 0f)

    /**
     * @param progress 归一化进度（0..1，越界按 0 处理）
     * @return 水平偏移像素（正值向右）
     */
    fun horizontalOffset(progress: Float): Float {
        if (!progress.isFinite() || progress <= 0f || progress >= 1f) return 0f
        for (i in 1 until KEYFRAME_PROGRESS.size) {
            val end = KEYFRAME_PROGRESS[i]
            if (progress <= end) {
                val start = KEYFRAME_PROGRESS[i - 1]
                val span = end - start
                val fraction = if (span <= 0f) 0f else (progress - start) / span
                return KEYFRAME_OFFSET[i - 1] +
                    (KEYFRAME_OFFSET[i] - KEYFRAME_OFFSET[i - 1]) * fraction
            }
        }
        return 0f
    }
}
