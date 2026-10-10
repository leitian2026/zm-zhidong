package com.phoneagent.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    fun post(url: String, body: JSONObject, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url).post(body.toString().toRequestBody(json))
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    /** 读取 SSE，每个 data: 行回调一次；协程取消时会中断连接 */
    suspend fun sse(req: Request, onData: (String) -> Unit) = withContext(Dispatchers.IO) {
        val call = client.newCall(req)
        val handle = coroutineContext[Job]!!.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw HttpError("HTTP ${resp.code}：${resp.body?.string()?.take(600)}")
                }
                val src = resp.body!!.source()
                while (true) {
                    ensureActive()
                    val line = src.readUtf8Line() ?: break
                    if (line.startsWith("data:")) onData(line.removePrefix("data:").trim())
                }
            }
        } finally {
            handle.dispose()
        }
    }
}

private fun texts(m: Msg) = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }

/* ============================== 附件 ============================== */

private const val MAX_INLINE_TEXT = 200_000L

private val textExt = setOf(
    "txt", "md", "json", "csv", "tsv", "log", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg",
    "properties", "prop", "sh", "py", "kt", "java", "js", "ts", "html", "css", "c", "h", "cpp", "go",
    "rs", "sql", "gradle", "kts", "bat", "rc"
)

private fun isTextLike(f: Part.File): Boolean {
    val m = f.mime.lowercase()
    if (m.startsWith("text/")) return true
    if (m in setOf("application/json", "application/xml", "application/javascript", "application/x-sh", "application/yaml", "application/x-yaml", "application/toml")) return true
    return f.name.substringAfterLast('.', "").lowercase() in textExt
}

private fun fmtSize(n: Long): String = when {
    n >= 1L shl 20 -> "%.1f MB".format(n / 1048576.0)
    n >= 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}

/** 用户消息的文字部分：正文 + 每个附件的说明（文本类文件直接把内容带上） */
private fun userText(m: Msg): String {
    val sb = StringBuilder(texts(m))
    for (f in m.parts.filterIsInstance<Part.File>()) {
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append("[附件 ${f.name} · ${f.mime} · ${fmtSize(f.size)} · 手机上的路径 ${f.path}]")
        if (isTextLike(f)) {
            if (f.size <= MAX_INLINE_TEXT) {
                val t = runCatching { java.io.File(f.path).readText() }.getOrNull()
                if (t != null) sb.append("\n```\n").append(t).append("\n```")
            } else {
                sb.append("\n（文本较大，没有直接带上，请用工具按路径读取）")
            }
        }
    }
    return sb.toString()
}

/**
 * 挑出这次请求里要把原始内容一起发出去的附件：从最新的消息往前取，总量不超过 budget。
 * 每轮工具调用都会重发整个历史，所以必须限制总量；放不下的旧附件只保留文字说明。
 */
private fun pickInline(msgs: List<Msg>, ok: (Part.File) -> Boolean, budget: Long): Set<Part.File> {
    var left = budget
    val out = HashSet<Part.File>()
    for (m in msgs.asReversed()) {
        if (m.role != Role.USER) continue
        for (f in m.parts.filterIsInstance<Part.File>().asReversed()) {
            if (ok(f) && f.size <= left && java.io.File(f.path).exists()) { out += f; left -= f.size }
        }
    }
    return out
}

private fun b64(f: Part.File): String? =
    runCatching { Base64.encodeToString(java.io.File(f.path).readBytes(), Base64.NO_WRAP) }.getOrNull()

/** Gemini 支持的类型（保守取值：发了不支持的类型会 400，并且会让这个对话之后每次都失败） */
private fun gemMime(f: Part.File): String? {
    val m = f.mime.lowercase()
    return when (m) {
        "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif", "application/pdf" -> m
        "image/jpg" -> "image/jpeg"
        "audio/wav", "audio/x-wav" -> "audio/wav"
        "audio/mpeg", "audio/mp3" -> "audio/mp3"
        "audio/aac", "audio/ogg", "audio/flac", "audio/aiff" -> m
        "audio/x-aiff" -> "audio/aiff"
        "video/mp4", "video/mpeg", "video/webm", "video/3gpp", "video/x-flv", "video/x-ms-wmv" -> m
        "video/quicktime" -> "video/mov"
        "video/x-msvideo" -> "video/avi"
        else -> null
    }
}

private fun claudeMime(f: Part.File): String? {
    val m = f.mime.lowercase()
    return when {
        m == "application/pdf" -> m
        m == "image/jpg" -> if (f.size <= 5L shl 20) "image/jpeg" else null
        m in setOf("image/jpeg", "image/png", "image/gif", "image/webp") -> if (f.size <= 5L shl 20) m else null
        else -> null
    }
}

private fun openAiMime(f: Part.File): String? {
    val m = f.mime.lowercase()
    return when (m) {
        "image/jpeg", "image/png", "image/gif", "image/webp" -> m
        "image/jpg" -> "image/jpeg"
        else -> null
    }
}

/* ============================== Gemini ============================== */
class GeminiProvider(private val key: String, private val model: String) : Provider {

    private fun contents(msgs: List<Msg>): JSONArray {
        val arr = JSONArray()
        val g3 = model.startsWith("gemini-3")
        val inline = pickInline(msgs, { gemMime(it) != null }, 14L shl 20)
        for (m in msgs) {
            when (m.role) {
                Role.USER -> {
                    val parts = JSONArray()
                    for (f in m.parts.filterIsInstance<Part.File>()) {
                        if (f !in inline) continue
                        val data = b64(f) ?: continue
                        parts.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", gemMime(f)).put("data", data)
                            )
                        )
                    }
                    parts.put(JSONObject().put("text", userText(m).ifEmpty { " " }))
                    arr.put(JSONObject().put("role", "user").put("parts", parts))
                }
                Role.ASSISTANT -> {
                    val parts = JSONArray()
                    for (p in m.parts) {
                        when (p) {
                            is Part.Text -> if (p.text.isNotBlank()) parts.put(JSONObject().put("text", p.text))
                            is Part.Call -> {
                                val o = JSONObject().put(
                                    "functionCall",
                                    JSONObject().put("name", p.name)
                                        .put("args", runCatching { JSONObject(p.args) }.getOrDefault(JSONObject()))
                                )
                                val sig = p.sig ?: if (g3) "skip_thought_signature_validator" else null
                                if (sig != null) o.put("thoughtSignature", sig)
                                parts.put(o)
                            }
                            else -> {}
                        }
                    }
                    if (parts.length() > 0) arr.put(JSONObject().put("role", "model").put("parts", parts))
                }
                Role.TOOL -> {
                    val parts = JSONArray()
                    for (p in m.parts) {
                        if (p is Part.Result) parts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject().put("name", p.name)
                                    .put("response", JSONObject().put("result", p.content))
                            )
                        )
                    }
                    if (parts.length() > 0) arr.put(JSONObject().put("role", "user").put("parts", parts))
                }
            }
        }
        return arr
    }

    override suspend fun stream(system: String, msgs: List<Msg>, tools: List<ToolSpec>, emit: (Ev) -> Unit) {
        val body = JSONObject()
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        body.put("contents", contents(msgs))
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            tools.forEach { t ->
                val d = JSONObject().put("name", t.name).put("description", t.description)
                val n = t.params.optJSONObject("properties")?.length() ?: 0
                if (n > 0) d.put("parameters", t.params)
                decls.put(d)
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        if (model.startsWith("gemini-2.5") || model.startsWith("gemini-3")) {
            body.put(
                "generationConfig",
                JSONObject().put("thinkingConfig", JSONObject().put("includeThoughts", true))
            )
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"
        val req = Http.post(url, body, mapOf("x-goog-api-key" to key))
        Http.sse(req) { data ->
            val j = JSONObject(data)
            val parts = j.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return@sse
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val fc = p.optJSONObject("functionCall")
                if (fc != null) {
                    emit(
                        Ev.Call(
                            "g_" + UUID.randomUUID().toString().take(8),
                            fc.str("name"),
                            (fc.optJSONObject("args") ?: JSONObject()).toString(),
                            p.str("thoughtSignature").ifEmpty { null }
                        )
                    )
                } else {
                    val t = p.str("text")
                    if (t.isNotEmpty()) {
                        if (p.optBoolean("thought", false)) emit(Ev.Think(t)) else emit(Ev.Text(t))
                    }
                }
            }
        }
    }
}

/* ============================== OpenAI（兼容接口） ============================== */
class OpenAIProvider(private val key: String, private val model: String, private val base: String) : Provider {

    private fun messages(system: String, msgs: List<Msg>): JSONArray {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        val inline = pickInline(msgs, { openAiMime(it) != null }, 14L shl 20)
        for (m in msgs) {
            when (m.role) {
                Role.USER -> {
                    val imgs = m.parts.filterIsInstance<Part.File>().filter { it in inline }
                    if (imgs.isEmpty()) {
                        arr.put(JSONObject().put("role", "user").put("content", userText(m)))
                    } else {
                        val content = JSONArray().put(JSONObject().put("type", "text").put("text", userText(m).ifEmpty { " " }))
                        for (f in imgs) {
                            val data = b64(f) ?: continue
                            content.put(
                                JSONObject().put("type", "image_url").put(
                                    "image_url", JSONObject().put("url", "data:${openAiMime(f)};base64,$data")
                                )
                            )
                        }
                        arr.put(JSONObject().put("role", "user").put("content", content))
                    }
                }
                Role.ASSISTANT -> {
                    val calls = m.parts.filterIsInstance<Part.Call>()
                    val o = JSONObject().put("role", "assistant").put("content", texts(m))
                    if (calls.isNotEmpty()) {
                        val tc = JSONArray()
                        calls.forEach { c ->
                            tc.put(
                                JSONObject().put("id", c.id).put("type", "function")
                                    .put("function", JSONObject().put("name", c.name).put("arguments", c.args))
                            )
                        }
                        o.put("tool_calls", tc)
                    }
                    arr.put(o)
                }
                Role.TOOL -> m.parts.filterIsInstance<Part.Result>().forEach { r ->
                    arr.put(JSONObject().put("role", "tool").put("tool_call_id", r.id).put("content", r.content))
                }
            }
        }
        return arr
    }

    private class Acc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    override suspend fun stream(system: String, msgs: List<Msg>, tools: List<ToolSpec>, emit: (Ev) -> Unit) {
        val body = JSONObject().put("model", model).put("stream", true).put("messages", messages(system, msgs))
        if (tools.isNotEmpty()) {
            val ta = JSONArray()
            tools.forEach { t ->
                ta.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.params)
                    )
                )
            }
            body.put("tools", ta)
        }
        val headers = HashMap<String, String>()
        if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
        val req = Http.post(base.trimEnd('/') + "/chat/completions", body, headers)
        val accs = TreeMap<Int, Acc>()
        Http.sse(req) { data ->
            if (data == "[DONE]") return@sse
            val j = JSONObject(data)
            val d = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return@sse
            val c = d.str("content")
            if (c.isNotEmpty()) emit(Ev.Text(c))
            val r = d.str("reasoning_content")
            if (r.isNotEmpty()) emit(Ev.Think(r))
            val tcs = d.optJSONArray("tool_calls")
            if (tcs != null) {
                for (i in 0 until tcs.length()) {
                    val t = tcs.getJSONObject(i)
                    val a = accs.getOrPut(t.optInt("index", 0)) { Acc() }
                    val id = t.str("id")
                    if (id.isNotEmpty()) a.id = id
                    val f = t.optJSONObject("function")
                    if (f != null) {
                        val n = f.str("name")
                        if (n.isNotEmpty()) a.name = n
                        a.args.append(f.str("arguments"))
                    }
                }
            }
        }
        accs.forEach { (idx, a) ->
            emit(Ev.Call(a.id.ifEmpty { "call_${idx}_" + UUID.randomUUID().toString().take(6) }, a.name,
                a.args.toString().ifBlank { "{}" }, null))
        }
    }
}

/* ============================== Claude ============================== */
class ClaudeProvider(private val key: String, private val model: String) : Provider {

    private fun messages(msgs: List<Msg>): JSONArray {
        val arr = JSONArray()
        val inline = pickInline(msgs, { claudeMime(it) != null }, 20L shl 20)
        for (m in msgs) {
            when (m.role) {
                Role.USER -> {
                    val blocks = JSONArray()
                    for (f in m.parts.filterIsInstance<Part.File>()) {
                        if (f !in inline) continue
                        val data = b64(f) ?: continue
                        val mt = claudeMime(f)!!
                        blocks.put(
                            JSONObject().put("type", if (mt == "application/pdf") "document" else "image").put(
                                "source",
                                JSONObject().put("type", "base64").put("media_type", mt).put("data", data)
                            )
                        )
                    }
                    blocks.put(JSONObject().put("type", "text").put("text", userText(m).ifEmpty { " " }))
                    arr.put(JSONObject().put("role", "user").put("content", blocks))
                }
                Role.ASSISTANT -> {
                    val blocks = JSONArray()
                    for (p in m.parts) {
                        when (p) {
                            is Part.Text -> if (p.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", p.text))
                            is Part.Call -> blocks.put(
                                JSONObject().put("type", "tool_use").put("id", p.id).put("name", p.name)
                                    .put("input", runCatching { JSONObject(p.args) }.getOrDefault(JSONObject()))
                            )
                            else -> {}
                        }
                    }
                    if (blocks.length() > 0) arr.put(JSONObject().put("role", "assistant").put("content", blocks))
                }
                Role.TOOL -> {
                    val blocks = JSONArray()
                    m.parts.filterIsInstance<Part.Result>().forEach { r ->
                        blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", r.id).put("content", r.content))
                    }
                    if (blocks.length() > 0) arr.put(JSONObject().put("role", "user").put("content", blocks))
                }
            }
        }
        return arr
    }

    private class CAcc(val id: String, val name: String, val args: StringBuilder = StringBuilder())

    override suspend fun stream(system: String, msgs: List<Msg>, tools: List<ToolSpec>, emit: (Ev) -> Unit) {
        val body = JSONObject().put("model", model).put("max_tokens", 8192).put("stream", true)
            .put("system", system).put("messages", messages(msgs))
        if (tools.isNotEmpty()) {
            val ta = JSONArray()
            tools.forEach { t ->
                ta.put(JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.params))
            }
            body.put("tools", ta)
        }
        val req = Http.post(
            "https://api.anthropic.com/v1/messages", body,
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        )
        val blocks = HashMap<Int, CAcc>()
        Http.sse(req) { data ->
            val j = JSONObject(data)
            when (j.optString("type")) {
                "content_block_start" -> {
                    val cb = j.optJSONObject("content_block")
                    if (cb != null && cb.optString("type") == "tool_use") {
                        blocks[j.optInt("index")] = CAcc(cb.str("id"), cb.str("name"))
                    }
                }
                "content_block_delta" -> {
                    val d = j.optJSONObject("delta") ?: return@sse
                    when (d.optString("type")) {
                        "text_delta" -> emit(Ev.Text(d.str("text")))
                        "thinking_delta" -> emit(Ev.Think(d.str("thinking")))
                        "input_json_delta" -> blocks[j.optInt("index")]?.args?.append(d.str("partial_json"))
                        else -> {}
                    }
                }
                "content_block_stop" -> {
                    val a = blocks.remove(j.optInt("index"))
                    if (a != null) emit(Ev.Call(a.id, a.name, a.args.toString().ifBlank { "{}" }, null))
                }
                "error" -> throw HttpError(j.optJSONObject("error")?.str("message") ?: data)
                else -> {}
            }
        }
    }
}
