package com.wpiaopiao.saolei

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.text.DateFormat as TimeDateFormat
import java.util.Date

/**
 * 底栏状态区（当前时间 + 电量）的 Android 侧数据源。
 *
 * 随 Activity 生命周期动态注册一个 [BroadcastReceiver]（不写进 Manifest）：
 * - [Intent.ACTION_BATTERY_CHANGED]：sticky 广播，注册瞬间系统会把当前电量交回；
 * - [Intent.ACTION_TIME_TICK]：分钟变化（系统每分钟发一次），时钟翻分；
 * - [Intent.ACTION_TIME_CHANGED] / [Intent.ACTION_TIMEZONE_CHANGED] / [Intent.ACTION_DATE_CHANGED]：
 *   手动改时间 / 改时区 / 跨天，可能不伴随 TIME_TICK，需要主动刷新。
 *
 * 时钟文案由 [DateFormat.getTimeFormat] 生成，自动尊重系统 12/24 小时制设置；电量广播
 * 的 level/scale/status/plugged 解析全部委托 [GameStatus]（纯逻辑，可 JVM 单测）。
 *
 * 回调在主线程触发（动态注册的接收器运行于注册方线程，本类只在主线程创建/调用），
 * 结果直接交给 [GameView.setStatusInfo] 驱动底栏重绘。
 *
 * 幂等约定：[start] 重复调用不会重复注册；[stop] 可重复调用；[refresh] 可随时调用，
 * 未在监听时只更新时钟格式、不触发回调（下次 [start] 会重新读取并推送）。
 */
class StatusWatcher(
    private val context: Context,
    private val onChanged: (clock: String, batteryPercent: Int?, charging: Boolean) -> Unit,
) {

    /** 当前区域 / 12-24 小时制对应的时钟格式；[refresh] 与每次 [start] 时重建。 */
    private var timeFormat: TimeDateFormat = DateFormat.getTimeFormat(context)

    /** 最近一次电池广播解析出的 0..100 电量；广播缺失或字段非法时为 null（底栏显示 "--"）。 */
    private var percent: Int? = null

    /** 最近一次电池广播解析出的「已接电源」状态。 */
    private var charging = false

    /** 当前注册中的接收器；null 表示未监听。 */
    private var receiver: BroadcastReceiver? = null

    /** 开始监听，并立即推一次「当前时钟 + sticky 电量」；重复调用不重复注册。 */
    fun start() {
        if (receiver != null) return
        // 后台期间语言 / 12-24 小时制可能被改过，注册前重新读取时钟格式
        timeFormat = DateFormat.getTimeFormat(context)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    applyBattery(intent)
                }
                emit()
            }
        }
        // RECEIVER_NOT_EXPORTED：不接收其他应用显式发来的广播，系统广播仍会送达。
        // 先注册成功再记录到字段，注册抛异常时 stop 不会误 unregister。
        val sticky = ContextCompat.registerReceiver(
            context,
            newReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = newReceiver
        // ACTION_BATTERY_CHANGED 是 sticky 广播：多数系统注册时不回调 onReceive，
        // 而是把当前值作为 registerReceiver 的返回值交回，这里补一次解析。
        if (sticky != null) applyBattery(sticky)
        emit()
    }

    /** 停止监听并注销接收器；可重复调用。 */
    fun stop() {
        val current = receiver ?: return
        receiver = null
        try {
            context.unregisterReceiver(current)
        } catch (ignored: IllegalArgumentException) {
            // 已被系统或外部注销（极端竞态）：忽略即可，保证 stop 幂等
        }
    }

    /**
     * 重新生成时钟格式并重推一次状态；用于 Locale / 12-24 小时制等配置变化。
     * 未在监听时只更新格式，不触发回调。
     */
    fun refresh() {
        timeFormat = DateFormat.getTimeFormat(context)
        if (receiver != null) emit()
    }

    /** 解析电池广播的 level / scale / status / plugged 并缓存。 */
    private fun applyBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        percent = GameStatus.batteryPercent(level, scale)
        charging = GameStatus.isCharging(status, plugged)
    }

    /** 把「当前时钟 + 最近一次电量」推给绘制端。 */
    private fun emit() {
        onChanged(timeFormat.format(Date()), percent, charging)
    }
}
