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

/** SAFE 直接执行，CONFIRM 弹确认（SEVERE 目前未使用） */
enum class Risk { SAFE, CONFIRM, SEVERE }

data class Verdict(val risk: Risk, val reason: String = "")
