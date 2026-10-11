package com.phoneagent.app

import java.util.concurrent.TimeUnit

/** 以 root 执行命令（每次 su -c，带超时与输出截断） */
object RootShell {
    data class Res(val code: Int, val out: String)

    /** 模型自己的扩展都放这里（工具、env.sh、README.md、笔记）。不在 App 私有目录，卸载重装后仍保留 */
    const val EXT_DIR = "/data/local/tmp/ext"
    /** 下载的工具放这里，每条命令都会把它加进 PATH */
    const val BIN_DIR = "$EXT_DIR/bin"
    /** 密钥目录：一个文件一个密钥，文件名即名称（权限 600） */
    const val SECRETS_DIR = "/data/local/tmp/secrets"

    /** 联网代理（设置页填写，如 127.0.0.1:7890 或 socks5h://127.0.0.1:10808），空 = 不用 */
    @Volatile var proxy: String = ""

    /** root 进程不走 VPN，所以每条命令前统一补上 PATH 和代理环境变量 */
    private fun envPrefix(): String {
        val sb = StringBuilder("export PATH=\"\$PATH:$BIN_DIR\"; ")
        val px = proxy.trim()
        if (px.isNotEmpty()) {
            val q = shq(if (px.contains("://")) px else "http://$px")
            for (n in listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "all_proxy")) {
                sb.append("export $n=$q; ")
            }
            sb.append("export no_proxy=localhost,127.0.0.1; ")
        }
        // 模型自己维护的环境脚本：存在就 source（放在最后，可以覆盖上面的 PATH/代理）
        sb.append("[ -f $EXT_DIR/env.sh ] && . $EXT_DIR/env.sh 2>/dev/null; ")
        return sb.toString()
    }

    @Volatile private var cur: Process? = null

    /** 用户点“终止”时，立刻结束正在执行的命令（不用等它跑完或超时） */
    fun cancelCurrent() {
        cur?.destroyForcibly()
    }

    /** withEnv=false：不加载 PATH/代理/env.sh，App 内部读取和“停用环境脚本”用，避免被写坏的脚本拖住 */
    fun run(cmd: String, timeoutMs: Long = 30_000, withEnv: Boolean = true): Res {
        try {
            val p = ProcessBuilder("su", "-c", (if (withEnv) envPrefix() else "") + cmd).redirectErrorStream(true).start()
            cur = p
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
        } finally {
            cur = null
        }
    }

    /** 以 root 执行命令，stdout（二进制）直接写进 out，stderr 单独收集。用于导出备份 */
    fun runToStream(cmd: String, out: java.io.OutputStream, timeoutMs: Long = 600_000): Res {
        try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            cur = p
            val err = StringBuffer()
            val et = Thread {
                try {
                    p.errorStream.bufferedReader().use { r ->
                        val buf = CharArray(1024)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (err.length < 2000) err.append(buf, 0, n)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            var copyErr: Exception? = null
            val copier = Thread {
                try { p.inputStream.copyTo(out) } catch (e: Exception) { copyErr = e }
            }
            et.start()
            copier.start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                return Res(-1, "超时，已终止")
            }
            copier.join()
            et.join(500)
            if (copyErr != null) return Res(-1, "写入目标文件失败：" + copyErr!!.message)
            return Res(p.exitValue(), err.toString().trim())
        } catch (e: Exception) {
            return Res(-1, "无法执行 su：${e.message}")
        } finally {
            cur = null
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

/** 给 shell 参数加单引号 */
fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
