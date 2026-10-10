package com.phoneagent.app

import java.util.concurrent.TimeUnit

/** 以 root 执行命令（每次 su -c，带超时与输出截断） */
object RootShell {
    data class Res(val code: Int, val out: String)

    fun run(cmd: String, timeoutMs: Long = 30_000): Res {
        try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuffer()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(2048)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (sb.length < 8000) sb.append(buf, 0, n)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                reader.join(500)
                return Res(-1, trimOut(sb) + "\n[超时，已终止]")
            }
            reader.join(1500)
            return Res(p.exitValue(), trimOut(sb))
        } catch (e: Exception) {
            return Res(-1, "无法执行 su：${e.message}")
        }
    }

    private fun trimOut(sb: StringBuffer): String {
        val s = sb.toString()
        return if (s.length > 6000) s.take(6000) + "\n[输出已截断]" else s.trimEnd()
    }
}

enum class Risk { SAFE, CONFIRM, SEVERE }

data class Verdict(val risk: Risk, val reason: String = "")

/**
 * 命令分级：
 *  SAFE    只读查询、开关设置、启动应用等，自动执行
 *  CONFIRM 不在白名单内的一切命令，弹窗确认
 *  SEVERE  可能破坏系统/不可逆，弹窗确认且需长按
 * 默认是"不认识就确认"，宁可多问。
 */
object Danger {
    private val sysPath = Regex("""(^|[\s='"])/(system|vendor|product|odm|boot|data/adb|dev)(/|\b)""")
    private val destructive = Regex("""\b(rm|mv|cp|chmod|chown|tee|truncate|mount|umount|ln|install)\b|sed\s+-i|>""")
    private val severeCmd = Regex(
        """\b(dd|mkfs\S*|fastboot|wipe|flash_image|blkdiscard)\b|reboot\s+(recovery|bootloader|fastboot|edl)|/dev/block|rm\s+(-\w+\s+)*(/|/data|/sdcard|/storage\S*|\*)(\s|$)"""
    )

    fun classify(cmd: String, autoSafe: Boolean): Verdict {
        val c = cmd.trim()
        if (c.isEmpty()) return Verdict(Risk.SAFE)
        if (severeCmd.containsMatchIn(c) || (sysPath.containsMatchIn(c) && destructive.containsMatchIn(c))) {
            return Verdict(Risk.SEVERE, "可能破坏系统分区或造成不可逆的后果")
        }
        if (!autoSafe) return Verdict(Risk.CONFIRM, "当前是严格模式，所有操作都需要确认")
        if (isSafe(c)) return Verdict(Risk.SAFE)
        return Verdict(Risk.CONFIRM, "该命令不在自动放行名单内，可能修改系统或应用数据")
    }

    private fun isSafe(c: String): Boolean {
        if (c.contains('\n') || c.contains("$(") || c.contains('`')) return false
        // 去掉引号里的内容后再拆分，避免 grep -E 'a|b' 被误拆
        val flat = c.replace(Regex("'[^']*'"), "Q").replace(Regex("\"[^\"]*\""), "Q")
        if (flat.contains('>') || flat.contains('<')) return false
        return flat.split(Regex("""\|\||&&|;|\|""")).all { segSafe(it.trim()) }
    }

    private val plain = setOf(
        "ls", "cat", "head", "tail", "grep", "wc", "id", "whoami", "date", "uname", "uptime", "df",
        "ps", "getprop", "getenforce", "dumpsys", "echo", "pidof", "free", "top", "ip", "ifconfig",
        "ping", "sort", "uniq", "cut", "sleep", "true", "input", "svc"
    )

    private fun segSafe(s: String): Boolean {
        if (s.isEmpty()) return true
        val t = s.split(Regex("\\s+"))
        val cmd = t[0]
        val a = t.drop(1)
        if (cmd in plain) return true
        return when (cmd) {
            "logcat" -> a.contains("-d")
            "monkey" -> a.contains("-p")
            "am" -> a.firstOrNull() == "start"
            "pm" -> a.firstOrNull() in setOf("list", "path", "dump")
            "settings" -> a.firstOrNull() == "get"
            "content" -> a.firstOrNull() == "query"
            "find" -> !a.contains("-delete") && !a.any { it.startsWith("-exec") }
            "cmd" -> when (a.firstOrNull()) {
                "wifi" -> a.getOrNull(1) in setOf("status", "set-wifi-enabled", "list-scan-results", "list-networks")
                "bluetooth_manager" -> a.getOrNull(1) in setOf("enable", "disable")
                "statusbar" -> true
                else -> false
            }
            else -> false
        }
    }
}
