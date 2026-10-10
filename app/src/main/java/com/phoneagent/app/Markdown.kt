package com.phoneagent.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 轻量 Markdown：标题、列表、粗体、行内代码、代码块（够聊天用，且解析很快） */
sealed class Md {
    class Line(val text: AnnotatedString, val size: Int, val bold: Boolean) : Md()
    object Gap : Md()
    class Code(val code: String) : Md()
}

private val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x1A000000))

fun inlineMd(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val e = s.indexOf("**", i + 2)
            if (e > 0) { withStyle(boldStyle) { append(s.substring(i + 2, e)) }; i = e + 2; continue }
        }
        if (s[i] == '`') {
            val e = s.indexOf('`', i + 1)
            if (e > 0) { withStyle(codeStyle) { append(s.substring(i + 1, e)) }; i = e + 1; continue }
        }
        append(s[i]); i++
    }
}

fun parseMd(src: String): List<Md> {
    val out = mutableListOf<Md>()
    var inCode = false
    val code = StringBuilder()
    for (raw in src.split("\n")) {
        if (raw.trimStart().startsWith("```")) {
            if (inCode) { out += Md.Code(code.toString().trimEnd('\n')); code.clear(); inCode = false } else inCode = true
            continue
        }
        if (inCode) { code.append(raw).append('\n'); continue }
        val line = raw.trimEnd()
        val tr = line.trimStart()
        when {
            line.isBlank() -> if (out.lastOrNull() !is Md.Gap) out += Md.Gap
            line.startsWith("### ") -> out += Md.Line(inlineMd(line.drop(4)), 16, true)
            line.startsWith("## ") -> out += Md.Line(inlineMd(line.drop(3)), 17, true)
            line.startsWith("# ") -> out += Md.Line(inlineMd(line.drop(2)), 18, true)
            tr.startsWith("- ") || tr.startsWith("* ") -> out += Md.Line(inlineMd("• " + tr.drop(2)), 16, false)
            else -> out += Md.Line(inlineMd(line), 16, false)
        }
    }
    if (inCode) out += Md.Code(code.toString().trimEnd('\n'))
    return out
}

@Composable
fun Markdown(src: String, color: Color = QQ.text) {
    val blocks = remember(src) { parseMd(src) }
    Column {
        blocks.forEach { b ->
            when (b) {
                is Md.Line -> BasicText(
                    b.text,
                    style = TextStyle(
                        color = color, fontSize = b.size.sp,
                        fontWeight = if (b.bold) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = (b.size * 1.5f).sp
                    )
                )
                is Md.Gap -> Spacer(Modifier.height(6.dp))
                is Md.Code -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
                        .background(QQ.codeBg).horizontalScroll(rememberScrollState()).padding(10.dp)
                ) { T(b.code, QQ.text, 13, mono = true) }
            }
        }
    }
}

/**
 * 流式文字按"每帧"匀速揭示：每一帧只增加少量字符（积压多就略多一点），
 * 不管网络数据是一大块还是一小块到达，屏幕上的出字节奏都是均匀的。
 */
@Composable
fun StreamText(full: String, startFull: Boolean) {
    var shown by remember { mutableIntStateOf(if (startFull) full.length else 0) }
    val latest by rememberUpdatedState(full)
    LaunchedEffect(full) {
        while (shown < latest.length) {
            withFrameNanos { }
            val backlog = latest.length - shown
            shown += (backlog / 8).coerceIn(1, 24).coerceAtMost(backlog)
        }
    }
    var vis = if (shown >= full.length) full else full.substring(0, shown)
    if (vis.isNotEmpty() && vis.last().isHighSurrogate()) vis = vis.dropLast(1)
    Markdown(vis)
}
