package com.phoneagent.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(val ok: Boolean, val text: String)

/** 工具只在这里声明一次，三家模型的格式由 Provider 自动导出 */
object Tools {
    private fun schema(required: List<String>, vararg props: Triple<String, String, String>): JSONObject {
        val pr = JSONObject()
        props.forEach { (n, type, desc) -> pr.put(n, JSONObject().put("type", type).put("description", desc)) }
        return JSONObject().put("type", "object").put("properties", pr).put("required", JSONArray(required))
    }

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "run_shell",
            "以 root 权限在这台 Android 手机上执行 shell 命令，返回输出和退出码。没有合适的专用工具时使用。",
            schema(
                listOf("command"),
                Triple("command", "string", "要执行的 shell 命令"),
                Triple("reason", "string", "一句话说明为什么要执行")
            )
        ),
        ToolSpec(
            "read_file", "读取手机上的文本文件（最多 8000 字节）。",
            schema(listOf("path"), Triple("path", "string", "文件绝对路径"))
        ),
        ToolSpec(
            "write_file", "把文本写入手机上的文件（覆盖）。会弹窗让用户确认。",
            schema(
                listOf("path", "content"),
                Triple("path", "string", "文件绝对路径"),
                Triple("content", "string", "要写入的文本")
            )
        ),
        ToolSpec(
            "set_wifi", "开启或关闭 Wi-Fi。",
            schema(listOf("enabled"), Triple("enabled", "boolean", "true 开启，false 关闭"))
        ),
        ToolSpec(
            "set_bluetooth", "开启或关闭蓝牙。",
            schema(listOf("enabled"), Triple("enabled", "boolean", "true 开启，false 关闭"))
        ),
        ToolSpec(
            "launch_app", "按包名启动应用。不知道包名时先用 run_shell 执行 pm list packages | grep 关键字。",
            schema(listOf("package"), Triple("package", "string", "应用包名，例如 com.tencent.mobileqq"))
        ),
        ToolSpec(
            "get_device_info", "查看设备型号、系统版本、电量、Wi-Fi 与飞行模式状态。",
            schema(emptyList())
        )
    )


    fun title(name: String, argsJson: String, st: Status): String {
        val a = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val on = a.optBoolean("enabled", true)
        val t: Triple<String, String, String> = when (name) {
            "set_wifi" -> Triple(
                if (on) "正在开启 Wi-Fi" else "正在关闭 Wi-Fi",
                if (on) "已开启 Wi-Fi" else "已关闭 Wi-Fi",
                "Wi-Fi 操作失败"
            )
            "set_bluetooth" -> Triple(
                if (on) "正在开启蓝牙" else "正在关闭蓝牙",
                if (on) "已开启蓝牙" else "已关闭蓝牙",
                "蓝牙操作失败"
            )
            "launch_app" -> Triple("正在打开应用", "已打开应用 ${a.str("package")}", "打开应用失败")
            "run_shell" -> Triple("正在执行命令", "已执行命令", "命令执行失败")
            "read_file" -> Triple("正在读取文件", "已读取文件", "读取文件失败")
            "write_file" -> Triple("正在写入文件", "已写入文件", "写入文件失败")
            "get_device_info" -> Triple("正在查看设备状态", "已查看设备状态", "查看设备状态失败")
            else -> Triple("正在调用 $name", "已调用 $name", "$name 调用失败")
        }
        return when (st) {
            Status.RUNNING -> t.first + "…"
            Status.WAITING -> "等待确认：" + t.first.removePrefix("正在")
            Status.DONE -> t.second
            Status.FAILED -> t.third
            Status.DENIED -> "已拒绝：" + t.first.removePrefix("正在")
        }
    }

    fun describe(name: String, a: JSONObject): String = when (name) {
        "run_shell" -> a.str("command")
        "write_file" -> "写入 ${a.str("path")}（${a.str("content").length} 字）"
        else -> name + " " + a.toString()
    }

    fun risk(name: String, a: JSONObject, autoSafe: Boolean): Verdict {
        val v = when (name) {
            "run_shell" -> return Danger.classify(a.str("command"), autoSafe)
            "write_file" -> {
                val p = a.str("path")
                if (Regex("^/(system|vendor|product|odm|boot|dev|data/adb)(/|$)").containsMatchIn(p))
                    return Verdict(Risk.SEVERE, "目标在系统分区或设备目录，可能造成不可逆的后果")
                Verdict(Risk.CONFIRM, "将覆盖写入文件")
            }
            else -> Verdict(Risk.SAFE)
        }
        return if (!autoSafe && v.risk == Risk.SAFE) Verdict(Risk.CONFIRM, "当前是严格模式，所有操作都需要确认") else v
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun sh(cmd: String): ToolResult {
        val r = RootShell.run(cmd)
        val body = if (r.out.isBlank()) "（无输出）" else r.out
        return if (r.code == 0) ToolResult(true, body) else ToolResult(false, "错误: $body\n[退出码 ${r.code}]")
    }

    fun execute(name: String, a: JSONObject): ToolResult = when (name) {
        "run_shell" -> sh(a.str("command"))
        "read_file" -> sh("head -c 8000 ${q(a.str("path"))}")
        "write_file" -> {
            val b64 = Base64.encodeToString(a.str("content").toByteArray(), Base64.NO_WRAP)
            val r = sh("echo $b64 | base64 -d > ${q(a.str("path"))}")
            if (r.ok) ToolResult(true, "已写入") else r
        }
        "set_wifi" -> {
            val en = a.optBoolean("enabled", true)
            val r = sh("svc wifi ${if (en) "enable" else "disable"}; sleep 1; settings get global wifi_on")
            if (r.ok) ToolResult(true, "已执行，wifi_on=${r.text.trim()}") else r
        }
        "set_bluetooth" -> {
            val en = a.optBoolean("enabled", true)
            val r = sh("svc bluetooth ${if (en) "enable" else "disable"}")
            if (r.ok) ToolResult(true, "已执行") else r
        }
        "launch_app" -> {
            val pkg = a.str("package")
            if (!Regex("^[A-Za-z0-9._]+$").matches(pkg)) ToolResult(false, "错误: 包名格式不对")
            else {
                val r = sh("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                if (r.ok && r.text.contains("No activities found")) ToolResult(false, "错误: 找不到该应用的启动入口")
                else r
            }
        }
        "get_device_info" -> sh(
            "getprop ro.product.model; getprop ro.build.version.release; " +
                "dumpsys battery | grep -E 'level|status|powered'; " +
                "echo wifi_on=\$(settings get global wifi_on); " +
                "echo airplane=\$(settings get global airplane_mode_on); date"
        )
        else -> ToolResult(false, "错误: 未知工具 $name")
    }
}
