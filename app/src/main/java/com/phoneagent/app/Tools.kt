package com.phoneagent.app

import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(val ok: Boolean, val text: String)

/**
 * 只有一个工具：run_shell。
 * App 不理解任务内容，唯一的兜底判断：命令里含删除操作时弹窗让用户确认，其余一律直接执行。
 */
object Tools {
    private fun schema(required: List<String>, vararg props: Triple<String, String, String>): JSONObject {
        val pr = JSONObject()
        props.forEach { (n, type, desc) -> pr.put(n, JSONObject().put("type", type).put("description", desc)) }
        return JSONObject().put("type", "object").put("properties", pr).put("required", JSONArray(required))
    }

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "run_shell",
            "以 root 权限在这台 Android 手机上执行 shell 命令，返回输出和退出码。手机上的一切操作（开关 Wi-Fi/蓝牙、启动应用、读写文件、查询状态、点击屏幕等）都用它完成。",
            schema(
                listOf("command", "reason"),
                Triple("command", "string", "要执行的 shell 命令"),
                Triple("reason", "string", "一句话说明在做什么，直接显示给用户，例如“开启 Wi-Fi”")
            )
        )
    )

    fun title(name: String, argsJson: String, st: Status): String {
        val a = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val what = a.str("reason").ifBlank { a.str("command").take(40) }.ifBlank { name }
        return when (st) {
            Status.RUNNING -> "$what…"
            Status.WAITING -> "等待确认：$what"
            Status.DONE -> what
            Status.FAILED -> "$what（失败）"
            Status.DENIED -> "已拒绝：$what"
        }
    }

    fun describe(name: String, a: JSONObject): String =
        if (name == "run_shell") a.str("command") else name + " " + a.toString()

    /** 唯一的兜底：命令里有删除操作就需要确认；其余一律放行 */
    private val deleteCmd = Regex(
        """(^|[\s;&|(`'"])(rm|rmdir|unlink|shred)(\s|$)|-delete\b|\b(pm|cmd\s+package)\s+(uninstall|clear)\b"""
    )

    fun risk(name: String, a: JSONObject, autoSafe: Boolean): Verdict {
        val why = a.str("reason")
        if (!autoSafe) return Verdict(Risk.CONFIRM, "当前是严格模式，所有操作都需要确认")
        if (name == "run_shell" && deleteCmd.containsMatchIn(a.str("command"))) {
            return Verdict(Risk.CONFIRM, "这条命令会删除内容" + if (why.isNotBlank()) "：$why" else "")
        }
        return Verdict(Risk.SAFE)
    }

    fun execute(name: String, a: JSONObject): ToolResult {
        if (name != "run_shell") return ToolResult(false, "错误: 未知工具 $name，只有 run_shell")
        val r = RootShell.run(a.str("command"))
        val body = if (r.out.isBlank()) "（无输出）" else r.out
        return if (r.code == 0) ToolResult(true, body) else ToolResult(false, "错误: $body\n[退出码 ${r.code}]")
    }
}
