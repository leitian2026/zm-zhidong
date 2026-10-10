package com.phoneagent.app

import org.json.JSONObject

/** 与具体模型无关的内部消息格式，发请求时由各 Provider 转成各家格式 */
enum class Role { USER, ASSISTANT, TOOL }

sealed class Part {
    data class Text(val text: String) : Part()
    data class Call(val id: String, val name: String, val args: String, val sig: String? = null) : Part()
    data class Result(val id: String, val name: String, val content: String) : Part()
    /** 用户附件：文件已复制到应用私有目录，path 是绝对路径 */
    data class File(val name: String, val mime: String, val path: String, val size: Long) : Part()
}

data class Msg(val role: Role, val parts: List<Part>, val time: Long = System.currentTimeMillis())

data class ToolSpec(val name: String, val description: String, val params: JSONObject)

/** Provider 输出的统一事件 */
sealed class Ev {
    data class Text(val d: String) : Ev()
    data class Think(val d: String) : Ev()
    data class Call(val id: String, val name: String, val args: String, val sig: String?) : Ev()
}

interface Provider {
    suspend fun stream(system: String, msgs: List<Msg>, tools: List<ToolSpec>, emit: (Ev) -> Unit)
}

class HttpError(message: String) : java.io.IOException(message)

/** ---------- UI 状态 ---------- */
enum class Status { RUNNING, WAITING, DONE, FAILED, DENIED }

data class Confirm(val command: String, val reason: String, val severe: Boolean)

sealed class Block {
    abstract val key: String
    data class Text(override val key: String, val text: String) : Block()
    data class Think(override val key: String, val text: String, val running: Boolean, val secs: Int) : Block()
    data class Tool(
        override val key: String,
        val name: String,
        val args: String,
        val status: Status,
        val output: String,
        val confirm: Confirm?
    ) : Block()
}

data class UiMsg(val id: String, val user: Boolean, val blocks: List<Block>, val time: Long, val streaming: Boolean)

data class SessionMeta(val id: String, val title: String, val updated: Long)

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)
