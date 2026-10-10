package com.phoneagent.app

import android.os.SystemClock
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * 请求额度保护（和任务内容无关）：
 *  1. 本地滑动窗口：每分钟快到上限时先等一等再发，不用撞到 429 才发现
 *  2. 用到 80% 时给出提醒
 *  3. 真遇到 429 时区分“每分钟”和“每天”：前者按服务端给的时间自动重试，后者直接告诉用户
 * 上限数值在设置里填（不同模型、不同层级都不一样），0 表示不限制。
 */
object RateLimiter {
    /** 留一点余量：服务端的计时窗口和本地不完全对齐 */
    private const val MARGIN = 2
    private val stamps = HashMap<String, ArrayDeque<Long>>()

    /** 记一次请求；窗口满了就等到有空位，等待期间每秒回调一次剩余秒数。返回这一分钟内（含本次）已发次数 */
    suspend fun acquire(key: String, rpm: Int, onWait: (Int) -> Unit): Int {
        if (rpm <= 0) return 0
        val cap = maxOf(1, rpm - MARGIN)
        while (true) {
            var wait = 0L
            var n = 0
            synchronized(stamps) {
                val q = stamps.getOrPut(key) { ArrayDeque() }
                val now = SystemClock.elapsedRealtime()
                while (q.isNotEmpty() && now - q.first() >= 60_000) q.removeFirst()
                if (q.size < cap) {
                    q.addLast(now)
                    n = q.size
                } else {
                    wait = q.first() + 60_000 - now
                }
            }
            if (wait <= 0) return n
            onWait(((wait + 999) / 1000).toInt())
            delay(minOf(wait, 1000L))
        }
    }

    /** 额度提醒文字，没到提醒线返回空串 */
    fun warn(minuteUsed: Int, rpm: Int, dayUsed: Int, rpd: Int): String {
        val out = mutableListOf<String>()
        if (rpm > 0 && minuteUsed * 5 >= rpm * 4) out += "本分钟已发 $minuteUsed 次（上限 $rpm）"
        if (rpd > 0 && dayUsed * 5 >= rpd * 4) out += "今日已发 $dayUsed 次（上限 $rpd）"
        return out.joinToString(" · ")
    }

    data class Limit429(val daily: Boolean, val waitSec: Int)

    fun parse429(e: HttpError): Limit429 {
        val b = e.body
        val daily = Regex("PerDay|per day", RegexOption.IGNORE_CASE).containsMatchIn(b)
        val sec = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(b)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("retry in (\\d+(?:\\.\\d+)?)s", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: e.retryAfter.takeIf { it > 0 }?.toDouble()
        val wait = (sec?.let { ceil(it).toInt() + 1 } ?: 20).coerceIn(2, 90)
        return Limit429(daily, wait)
    }

    /** Gemini 的每日额度在太平洋时间午夜重置，换算成手机当前时区 */
    fun dailyResetText(): String {
        val pt = ZoneId.of("America/Los_Angeles")
        val reset = LocalDate.now(pt).plusDays(1).atStartOfDay(pt).withZoneSameInstant(ZoneId.systemDefault())
        return DateTimeFormatter.ofPattern("M月d日 HH:mm").format(reset)
    }

    fun dailyMessage(providerName: String): String =
        "$providerName 今天的请求额度已用完。每日额度大约在 ${dailyResetText()} 重置（太平洋时间午夜）。" +
            "现在可以先切换到别的模型或服务商，或者在对应平台开通计费提高额度。"
}
