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

    // 重定向到 /dev/null、fd 之间的重定向（2>&1）没有副作用，先去掉再判断
    private val safeRedir = Regex("""\s*[0-9&]?>{1,2}\s*/dev/null(?![\w/])|\s*[0-9]?>&[0-9]""")
    private fun stripSafeRedirects(c: String) = c.replace(safeRedir, " ")

    private val cmdSub = Regex("""\$\(([^()]*)\)|`([^`]*)`""")
    private val keywordPrefix = Regex("^(do|then|else|elif|if)\\s+(.*)$")
    private val forHead = Regex("^for\\s+\\w+\\s+in(\\s+.*)?$")
    private val assign = Regex("^[A-Za-z_]\\w*=\\S*$")
    private val bareKeywords = setOf("do", "then", "else", "fi", "done", "esac")

    fun classify(cmd: String, autoSafe: Boolean): Verdict {
        val c = cmd.trim()
        if (c.isEmpty()) return Verdict(Risk.SAFE)
        val d = stripSafeRedirects(c)
        if (severeCmd.containsMatchIn(d) || (sysPath.containsMatchIn(d) && destructive.containsMatchIn(d))) {
            return Verdict(Risk.SEVERE, "可能破坏系统分区或造成不可逆的后果")
        }
        if (!autoSafe) return Verdict(Risk.CONFIRM, "当前是严格模式，所有操作都需要确认")
        if (isSafe(c)) return Verdict(Risk.SAFE)
        return Verdict(Risk.CONFIRM, "该命令不在自动放行名单内，可能修改系统或应用数据")
    }

    private fun isSafe(c0: String, depth: Int = 0): Boolean {
        if (depth > 3) return false
        var c = stripSafeRedirects(c0)
        // 命令替换 $(...) / `...`：里面的命令本身也必须是只读的，通过后用占位符替换
        var guard = 0
        while (true) {
            val m = cmdSub.find(c) ?: break
            if (++guard > 20) return false
            val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (!isSafe(inner, depth + 1)) return false
            c = c.replaceRange(m.range, "Q")
        }
        if (c.contains("$(") || c.contains('`')) return false
        // 去掉引号里的内容后再拆分，避免 grep -E 'a|b' 被误拆
        val flat = c.replace(Regex("'[^']*'"), "Q").replace(Regex("\"[^\"]*\""), "Q")
        if (flat.contains('>') || flat.contains('<')) return false
        return flat.split(Regex("""\|\||&&|;|\||&|\n""")).all { segSafe(it.trim()) }
    }

    private val plain = setOf(
        "ls", "cat", "head", "tail", "grep", "wc", "id", "whoami", "date", "uname", "uptime", "df",
        "ps", "getprop", "getenforce", "dumpsys", "echo", "pidof", "free", "top", "ip", "ifconfig",
        "ping", "sort", "uniq", "cut", "sleep", "true", "input", "svc",
        // 只读查询类
        "stat", "du", "readlink", "realpath", "basename", "dirname", "which", "file",
        "md5sum", "sha1sum", "sha256sum", "tr", "printf", "hostname", "nproc", "pgrep", "lsof",
        "netstat", "ss", "cmp", "diff", "strings", "xxd", "od", "hexdump", "base64", "tac", "rev",
        "lsmod", "test", "[", "[["
    )

    private fun segSafe(s0: String): Boolean {
        var s = s0.trim()
        // shell 关键字：for x in ...; do ...; done / if ...; then ...; fi
        while (true) {
            val m = keywordPrefix.find(s) ?: break
            s = m.groupValues[2].trim()
        }
        if (s.isEmpty() || s in bareKeywords) return true
        if (forHead.matches(s) || assign.matches(s)) return true
        val t = s.split(Regex("\\s+"))
        val cmd = t[0]
        val a = t.drop(1)
        if (cmd in plain) return true
        return when (cmd) {
            "logcat" -> a.contains("-d")
            "monkey" -> a.contains("-p")
            "am" -> a.firstOrNull() == "start"
            "pm" -> a.firstOrNull() in setOf("list", "path", "dump", "resolve-activity")
            "settings" -> a.firstOrNull() in setOf("get", "list")
            "content" -> a.firstOrNull() == "query"
            "magisk" -> a.firstOrNull() in setOf("-c", "-v", "-V", "--path")
            "find" -> !a.contains("-delete") && !a.any { it.startsWith("-exec") }
            "cmd" -> when (a.firstOrNull()) {
                "wifi" -> a.getOrNull(1) in setOf("status", "set-wifi-enabled", "list-scan-results", "list-networks")
                "bluetooth_manager" -> a.getOrNull(1) in setOf("enable", "disable")
                "package" -> a.getOrNull(1) in setOf("list", "path", "resolve-activity", "dump")
                "statusbar" -> true
                else -> false
            }
            else -> false
        }
    }
}
