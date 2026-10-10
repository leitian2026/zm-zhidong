package com.phoneagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 会话以 JSON 文件保存在应用私有目录 */
class Store(ctx: Context) {
    private val dir = File(ctx.filesDir, "sessions").apply { mkdirs() }
    private val attachments = File(ctx.filesDir, "attachments").apply { mkdirs() }

    /** 某个会话的附件目录 */
    fun attachDir(id: String): File = File(attachments, id).apply { mkdirs() }

    fun list(): List<SessionMeta> =
        (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val j = JSONObject(f.readText())
                SessionMeta(j.getString("id"), j.getString("title"), j.getLong("updated"))
            }.getOrNull()
        }.sortedByDescending { it.updated }

    fun save(id: String, title: String, msgs: List<Msg>) {
        val arr = JSONArray()
        for (m in msgs) {
            val parts = JSONArray()
            for (p in m.parts) {
                when (p) {
                    is Part.Text -> parts.put(JSONObject().put("t", "text").put("text", p.text))
                    is Part.Call -> parts.put(
                        JSONObject().put("t", "call").put("id", p.id).put("name", p.name)
                            .put("args", p.args).put("sig", p.sig ?: "")
                    )
                    is Part.Result -> parts.put(
                        JSONObject().put("t", "result").put("id", p.id).put("name", p.name)
                            .put("content", p.content)
                    )
                    is Part.File -> parts.put(
                        JSONObject().put("t", "file").put("name", p.name).put("mime", p.mime)
                            .put("path", p.path).put("size", p.size)
                    )
                }
            }
            arr.put(JSONObject().put("role", m.role.name).put("time", m.time).put("parts", parts))
        }
        val j = JSONObject().put("id", id).put("title", title)
            .put("updated", System.currentTimeMillis()).put("msgs", arr)
        File(dir, "$id.json").writeText(j.toString())
    }

    fun load(id: String): Pair<String, List<Msg>>? = runCatching {
        val j = JSONObject(File(dir, "$id.json").readText())
        val arr = j.getJSONArray("msgs")
        val out = ArrayList<Msg>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val pa = m.getJSONArray("parts")
            val parts = ArrayList<Part>()
            for (k in 0 until pa.length()) {
                val p = pa.getJSONObject(k)
                when (p.getString("t")) {
                    "text" -> parts.add(Part.Text(p.str("text")))
                    "call" -> parts.add(Part.Call(p.str("id"), p.str("name"), p.str("args"), p.str("sig").ifEmpty { null }))
                    "result" -> parts.add(Part.Result(p.str("id"), p.str("name"), p.str("content")))
                    "file" -> parts.add(Part.File(p.str("name"), p.str("mime"), p.str("path"), p.optLong("size", 0L)))
                }
            }
            out.add(Msg(Role.valueOf(m.getString("role")), parts, m.optLong("time", 0L)))
        }
        j.getString("title") to out
    }.getOrNull()

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(attachments, id).deleteRecursively()
    }
}
