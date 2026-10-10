package com.phoneagent.app

import android.content.Context

object Prov {
    val ids = listOf("gemini", "openai", "claude")
    val names = mapOf("gemini" to "Gemini", "openai" to "GPT", "claude" to "Claude")
    val letters = mapOf("gemini" to "G", "openai" to "O", "claude" to "C")
    val defModel = mapOf(
        "gemini" to "gemini-2.5-flash",
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

    fun baseUrl(): String =
        p.getString("base_openai", "")?.takeIf { it.isNotBlank() } ?: Prov.DEF_BASE
    fun setBaseUrl(v: String) { p.edit().putString("base_openai", v.trim()).apply() }
}
