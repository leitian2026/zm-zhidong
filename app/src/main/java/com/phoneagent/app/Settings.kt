package com.phoneagent.app

import android.content.Context

object Prov {
    val ids = listOf("gemini", "openai", "claude")
    val names = mapOf("gemini" to "Gemini", "openai" to "GPT", "claude" to "Claude")
    val letters = mapOf("gemini" to "G", "openai" to "O", "claude" to "C")
    val defModel = mapOf(
        "gemini" to "gemini-3.8-flash",
        "openai" to "gpt-4o",
        "claude" to "claude-sonnet-5-5"
    )
    const val DEF_BASE = "https://api.openai.com/v1"
}

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var provider: String
        get() = p.getString("provider", "gemini") ?: "gemini"
        set(v) { p.edit().putString("provider", v).apply() }

    /** true：安全命令自动放行；false：严格模式，所有操作都要确认 */
    var autoSafe: Boolean
        get() = p.getBoolean("autoSafe", true)
        set(v) { p.edit().putBoolean("autoSafe", v).apply() }

    fun key(pv: String): String = p.getString("key_$pv", "") ?: ""
    fun setKey(pv: String, v: String) { p.edit().putString("key_$pv", v.trim()).apply() }

    fun model(pv: String): String =
        p.getString("model_$pv", "")?.takeIf { it.isNotBlank() } ?: Prov.defModel[pv]!!
    fun setModel(pv: String, v: String) { p.edit().putString("model_$pv", v.trim()).apply() }

    /** 每分钟 / 每天请求上限，0 = 不限制。Gemini 免费层默认 15 / 500，数值请按 AI Studio「速率限制」页面里自己模型那一行修改 */
    fun rpm(pv: String): Int = p.getInt("rpm_$pv", if (pv == "gemini") 15 else 0)
    fun setRpm(pv: String, v: String) { p.edit().putInt("rpm_$pv", v.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0).apply() }
    fun rpd(pv: String): Int = p.getInt("rpd_$pv", if (pv == "gemini") 500 else 0)
    fun setRpd(pv: String, v: String) { p.edit().putInt("rpd_$pv", v.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0).apply() }

    private fun ptDay() = java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")).toString()

    /** 今天（按太平洋时间）本应用已发的请求数，只统计本应用，同一项目里别处的用量看不到 */
    fun bumpDay(key: String): Int {
        val s = p.getString("day_$key", "") ?: ""
        val parts = s.split(":")
        val n = (if (parts.getOrNull(0) == ptDay()) parts.getOrNull(1)?.toIntOrNull() ?: 0 else 0) + 1
        p.edit().putString("day_$key", "${ptDay()}:$n").apply()
        return n
    }

    fun baseUrl(): String =
        p.getString("base_openai", "")?.takeIf { it.isNotBlank() } ?: Prov.DEF_BASE
    fun setBaseUrl(v: String) { p.edit().putString("base_openai", v.trim()).apply() }
}
