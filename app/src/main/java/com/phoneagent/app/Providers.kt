package com.phoneagent.app

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

/* ============================== Gemini ============================== */
class GeminiProvider(private val key: String, private val model: String) : Provider {

    private fun contents(msgs: List<Msg>): JSONArray {
        val arr = JSONArray()
        val g3 = model.startsWith("gemini-3")
        for (m in msgs) {
            when (m.role) {
                Role.USER -> arr.put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", texts(m).ifEmpty { " " })))
                )
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
        for (m in msgs) {
            when (m.role) {
                Role.USER -> arr.put(JSONObject().put("role", "user").put("content", texts(m)))
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
        for (m in msgs) {
            when (m.role) {
                Role.USER -> arr.put(
                    JSONObject().put("role", "user").put(
                        "content", JSONArray().put(JSONObject().put("type", "text").put("text", texts(m).ifEmpty { " " }))
                    )
                )
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
