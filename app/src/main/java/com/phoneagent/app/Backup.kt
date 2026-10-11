package com.phoneagent.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream

/**
 * 备份/还原“记忆库和功能扩展库”：整个 /data/local/tmp/ext（工具、env.sh、README.md、笔记）。
 * 可选一并带上 /data/local/tmp/secrets（明文密钥）。
 * 压缩包里的路径始终是 ext/... 和 secrets/...，还原时解到 /data/local/tmp，也就是它们原来能正常使用的位置。
 */
object Backup {
    private const val BASE = "/data/local/tmp"

    private class Counting(private val o: OutputStream) : OutputStream() {
        var n = 0L
        override fun write(b: Int) { o.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); n += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    private fun mb(n: Long) = String.format("%.1f MB", n / 1048576.0)

    /** 导出到用户选的任意位置（系统文件选择器给的 Uri） */
    fun export(ctx: Context, uri: Uri, withSecrets: Boolean): String {
        val has = RootShell.run("[ -d " + shq(RootShell.EXT_DIR) + " ] && echo y", withEnv = false)
        if (!has.out.contains("y")) return "还没有扩展目录，没有可导出的内容"
        // ext 下有挂载（chroot 的 /proc、/dev 等）时直接打包会把整个系统文件卷进去，先拒绝
        val mounts = RootShell.run("grep -E ' " + RootShell.EXT_DIR + "(/| )' /proc/mounts | cut -d' ' -f2", withEnv = false)
        if (mounts.out.isNotBlank()) {
            return "已取消：扩展目录下还有挂载点，请先让模型 umount 掉（chroot 用完要卸载）：\n" + mounts.out
        }
        val items = if (withSecrets) "ext \$([ -d secrets ] && echo secrets)" else "ext"
        val cmd = "cd $BASE && tar -czf - $items"
        var counted = 0L
        val r = try {
            val os = ctx.contentResolver.openOutputStream(uri, "w") ?: return "无法写入所选位置"
            val c = Counting(java.io.BufferedOutputStream(os, 65536))
            c.use { out -> RootShell.runToStream(cmd, out) }.also { counted = c.n }
        } catch (e: Exception) {
            return "导出失败：" + e.message
        }
        if (r.code != 0) {
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
            return "导出失败：" + r.out
        }
        return "已导出 " + mb(counted) + (if (withSecrets) "（含密钥，文件里是明文，请妥善保管）" else "")
    }

    /** 从用户选的任意位置还原：解到 /data/local/tmp，同名文件覆盖，其余保留 */
    fun restore(ctx: Context, uri: Uri): String {
        val tmp = File(ctx.cacheDir, "restore.tgz")
        try {
            val ins = ctx.contentResolver.openInputStream(uri) ?: return "无法读取所选文件"
            ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val f = shq(tmp.absolutePath)
            // 只接受 ext/ 和 secrets/ 开头、不含 .. 的条目，防止还原到别的位置
            val check = RootShell.run(
                "tar -tzf $f >/dev/null 2>&1 || echo BAD_ARCHIVE; " +
                    "tar -tzf $f 2>/dev/null | grep -vE '^(ext|secrets)(/|\$)' | head -3; " +
                    "tar -tzf $f 2>/dev/null | grep -E '(^|/)\\.\\.(/|\$)' | head -3",
                120_000, withEnv = false
            )
            if (check.out.isNotBlank()) {
                return if (check.out.contains("BAD_ARCHIVE")) "不是有效的备份文件（需要本 App 导出的 .tar.gz）"
                else "已取消：压缩包里有不属于 ext/ 或 secrets/ 的路径，不是本 App 导出的备份：\n" + check.out
            }
            val r = RootShell.run(
                "cd $BASE && tar -xzf $f && " +
                    "{ [ -d secrets ] && chmod 700 secrets; [ -d ext/bin ] && chmod -R u+x ext/bin; true; }",
                600_000, withEnv = false
            )
            return if (r.code == 0) "已还原到 $BASE（同名文件已覆盖，其余保留）。笔记卡片点一下刷新即可看到" else "还原失败：" + r.out
        } catch (e: Exception) {
            return "还原失败：" + e.message
        } finally {
            tmp.delete()
        }
    }
}
