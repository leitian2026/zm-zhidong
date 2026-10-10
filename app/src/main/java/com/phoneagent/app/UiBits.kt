package com.phoneagent.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** QQ 配色 */
object QQ {
    val bg = Color(0xFFEAEEF7)
    val blue = Color(0xFF12B7F5)
    val title1 = Color(0xFF00CAFC)
    val title2 = Color(0xFF00A2FF)
    val text = Color(0xFF1F2329)
    val gray = Color(0xFF8A8F99)
    val line = Color(0xFFDDE1EA)
    val red = Color(0xFFF5483B)
    val green = Color(0xFF2FB36B)
    val codeBg = Color(0xFFF3F5F9)
    val bar = Color(0xFFF7F8FB)
}

/**
 * 全局统一的动效参数：所有过渡都用同一个时长和同一条曲线，
 * 这样每个操作的"帧跨度"一致，不会有的快有的慢。
 */
object Motion {
    const val MS = 350                                   // 300–450ms 区间内取中间值
    val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)       // 起步柔和、收尾平缓，不突兀
    fun <T> spec(): TweenSpec<T> = tween(MS, 0, ease)
}

fun Modifier.tap(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

@Composable
fun T(
    text: String,
    color: Color = QQ.text,
    size: Int = 15,
    bold: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            lineHeight = (size * 1.45f).sp
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** QQ 气泡：圆角矩形 + 一侧的小尖角（尖角占 tail 宽度，内容需自行留出） */
class BubbleShape(private val self: Boolean, private val tail: Dp = 7.dp, private val radius: Dp = 16.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = with(density) { tail.toPx() }
        val r = with(density) { radius.toPx() }
        val left = if (self) 0f else t
        val right = if (self) size.width - t else size.width
        val p = Path()
        p.addRoundRect(RoundRect(left, 0f, right, size.height, CornerRadius(r, r)))
        val y0 = r * 0.5f
        val y1 = r * 1.3f
        if (self) {
            p.moveTo(right - 2f, y0); p.lineTo(size.width, y0 - 2f); p.lineTo(right - 2f, y1)
        } else {
            p.moveTo(left + 2f, y0); p.lineTo(0f, y0 - 2f); p.lineTo(left + 2f, y1)
        }
        p.close()
        return Outline.Generic(p)
    }
}

@Composable
fun Avatar(letter: String, color: Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        T(letter, Color.White, 16, bold = true)
    }
}

@Composable
fun Spinner(size: Dp = 14.dp, color: Color = QQ.blue) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size)) {
        drawArc(color, a, 270f, false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun IconMenu(c: Color = Color.White) {
    Canvas(Modifier.size(22.dp)) {
        val s = 2.dp.toPx()
        listOf(0.28f, 0.5f, 0.72f).forEach {
            drawLine(c, Offset(size.width * 0.1f, size.height * it), Offset(size.width * 0.9f, size.height * it), s, StrokeCap.Round)
        }
    }
}

@Composable
fun IconPlus(c: Color = Color.White) {
    Canvas(Modifier.size(22.dp)) {
        val s = 2.dp.toPx()
        drawLine(c, Offset(size.width * 0.1f, size.height / 2), Offset(size.width * 0.9f, size.height / 2), s, StrokeCap.Round)
        drawLine(c, Offset(size.width / 2, size.height * 0.1f), Offset(size.width / 2, size.height * 0.9f), s, StrokeCap.Round)
    }
}

@Composable
fun IconChevron(c: Color = QQ.gray, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val s = 1.6.dp.toPx()
        drawLine(c, Offset(this.size.width * 0.3f, this.size.height * 0.15f), Offset(this.size.width * 0.7f, this.size.height * 0.5f), s, StrokeCap.Round)
        drawLine(c, Offset(this.size.width * 0.7f, this.size.height * 0.5f), Offset(this.size.width * 0.3f, this.size.height * 0.85f), s, StrokeCap.Round)
    }
}

@Composable
fun IconSend(c: Color = Color.White) {
    Canvas(Modifier.size(20.dp)) {
        val s = 2.2.dp.toPx()
        drawLine(c, Offset(size.width / 2, size.height * 0.88f), Offset(size.width / 2, size.height * 0.15f), s, StrokeCap.Round)
        drawLine(c, Offset(size.width * 0.2f, size.height * 0.42f), Offset(size.width / 2, size.height * 0.12f), s, StrokeCap.Round)
        drawLine(c, Offset(size.width * 0.8f, size.height * 0.42f), Offset(size.width / 2, size.height * 0.12f), s, StrokeCap.Round)
    }
}

@Composable
fun IconStop(c: Color = Color.White) {
    Canvas(Modifier.size(16.dp)) {
        drawRoundRect(c, size = size, cornerRadius = CornerRadius(3.dp.toPx()))
    }
}
