package com.phoneagent.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val avatarColors = mapOf(
    "gemini" to Color(0xFF6C8CFF), "openai" to Color(0xFF10A37F), "claude" to Color(0xFFD97757)
)
private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/* ============================== 页面 ============================== */

@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val busy by vm.busy.collectAsState()
    val provider by vm.provider.collectAsState()
    val title by vm.title.collectAsState()
    val pending by vm.pending.collectAsState()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris)
    }
    var picker by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val rev = remember(ui) { ui.asReversed() }

    // 新增消息时回到底部（reverseLayout：index 0 就是最新一条，贴着输入栏向上生长）
    LaunchedEffect(ui.size) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TitleBar(title, Prov.names[provider] + " · " + vm.settings.model(provider), onMenu) { vm.newChat() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                itemsIndexed(rev, key = { _, m -> m.id }) { idx, m ->
                    MessageItem(m, rev.getOrNull(idx + 1)?.time, provider) { id, ok -> vm.answer(id, ok) }
                }
            }
            val atBottom by remember {
                derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            }
            val scope = rememberCoroutineScope()
            ScrollToBottomButton(!atBottom, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                scope.launch { listState.scrollToItem(0) }
            }
        }
        AnimatedVisibility(
            picker,
            enter = expandVertically(Motion.spec()) + fadeIn(Motion.spec()),
            exit = shrinkVertically(Motion.spec()) + fadeOut(Motion.spec())
        ) {
            ModelPicker(provider, vm) { picker = false }
        }
        if (pending.isNotEmpty()) PendingFiles(pending) { vm.removePending(it) }
        InputBar(
            input, { input = it }, busy, Prov.names[provider] ?: "", { picker = !picker },
            hasFiles = pending.isNotEmpty(),
            onAttach = { filePicker.launch(arrayOf("*/*")) },
            onSend = { val t = input; input = ""; vm.send(t) }, onStop = { vm.stop() }
        )
    }
}

@Composable
private fun ScrollToBottomButton(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(Motion.spec()), exit = fadeOut(Motion.spec())
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Color.White).tap { onClick() },
            contentAlignment = Alignment.Center
        ) { T("↓", QQ.gray, 18) }
    }
}

@Composable
private fun TitleBar(title: String, sub: String, onMenu: () -> Unit, onNew: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(QQ.title1, QQ.title2)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(54.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.tap(onMenu).padding(4.dp)) { IconMenu() }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            T(title, Color.White, 17, bold = true, maxLines = 1)
            T(sub, Color.White.copy(alpha = 0.8f), 11, maxLines = 1)
        }
        Box(Modifier.tap(onNew).padding(4.dp)) { IconPlus() }
    }
}

@Composable
private fun ModelPicker(current: String, vm: ChatViewModel, close: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)).background(Color.White).padding(vertical = 4.dp)
    ) {
        Prov.ids.forEach { id ->
            Row(
                Modifier.fillMaxWidth().tap { vm.switchProvider(id); close() }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(Prov.letters[id] ?: "?", avatarColors[id] ?: QQ.blue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(Prov.names[id] ?: id, QQ.text, 16, bold = true)
                    T(vm.settings.model(id), QQ.gray, 12, maxLines = 1)
                }
                if (id == current) T("✓", QQ.blue, 18, bold = true)
            }
        }
    }
}

@Composable
private fun PendingFiles(files: List<Part.File>, onRemove: (Part.File) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(QQ.bar).horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        files.forEach { f ->
            Row(
                Modifier.padding(end = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                T("📎 " + f.name.take(24), QQ.text, 13, maxLines = 1)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).tap { onRemove(f) },
                    contentAlignment = Alignment.Center
                ) { T("✕", QQ.gray, 13) }
            }
        }
    }
}

@Composable
private fun InputBar(
    input: String, onInput: (String) -> Unit, busy: Boolean, providerName: String,
    onPicker: () -> Unit, hasFiles: Boolean, onAttach: () -> Unit, onSend: () -> Unit, onStop: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(QQ.bar)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).tap(onPicker)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) { T("$providerName ▾", QQ.gray, 13) }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.White).tap(onAttach),
            contentAlignment = Alignment.Center
        ) { T("＋", QQ.gray, 22) }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (input.isEmpty()) T("输入消息…", QQ.gray, 16)
            BasicTextField(
                input, onInput, Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = QQ.text, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(QQ.blue), maxLines = 5
            )
        }
        Spacer(Modifier.width(8.dp))
        val canSend = input.isNotBlank() || hasFiles
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (busy || canSend) QQ.blue else Color(0xFFC9CEDA))
                .tap { if (busy) onStop() else if (canSend) onSend() },
            contentAlignment = Alignment.Center
        ) { if (busy) IconStop() else IconSend() }
    }
}

/* ============================== 消息 ============================== */

@Composable
private fun MessageItem(m: UiMsg, olderTime: Long?, provider: String, onAnswer: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        if (olderTime == null || m.time - olderTime > 5 * 60_000L) {
            Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                T(timeFmt.format(Date(m.time)), QQ.gray, 12)
            }
        }
        if (m.user) UserRow(m) else AiRow(m, provider, onAnswer)
    }
}

@Composable
private fun UserRow(m: UiMsg) {
    val text = (m.blocks.firstOrNull() as? Block.Text)?.text ?: ""
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
        Spacer(Modifier.width(36.dp))
        Box(
            Modifier.weight(1f, fill = false).background(QQ.blue, BubbleShape(true))
                .padding(start = 14.dp, end = 21.dp, top = 9.dp, bottom = 9.dp)
        ) {
            SelectionContainer { T(text, Color.White, 16) }
        }
        Spacer(Modifier.width(6.dp))
        Avatar("我", Color(0xFF5B6B8C))
    }
}

private sealed class G {
    class Txt(val b: Block.Text) : G()
    class Fold(val items: List<Block>) : G()
}

private fun groupBlocks(bs: List<Block>): List<G> {
    val out = mutableListOf<G>()
    var cur = mutableListOf<Block>()
    for (b in bs) {
        if (b is Block.Text) {
            if (cur.isNotEmpty()) { out += G.Fold(cur); cur = mutableListOf() }
            out += G.Txt(b)
        } else cur.add(b)
    }
    if (cur.isNotEmpty()) out += G.Fold(cur)
    return out
}

@Composable
private fun AiRow(m: UiMsg, provider: String, onAnswer: (String, Boolean) -> Unit) {
    val groups = groupBlocks(m.blocks)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Avatar(Prov.letters[provider] ?: "?", avatarColors[provider] ?: QQ.blue)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.weight(1f, fill = false).background(Color.White, BubbleShape(false))
                .padding(start = 21.dp, end = 14.dp, top = 9.dp, bottom = 9.dp)
        ) {
            Column {
                if (groups.isEmpty()) {
                    if (m.streaming) Spinner(18.dp) else T("（无内容）", QQ.gray, 14)
                }
                groups.forEachIndexed { i, g ->
                    when (g) {
                        is G.Txt -> key(g.b.key) {
                            if (i > 0) Spacer(Modifier.height(4.dp))
                            SelectionContainer { StreamText(g.b.text, startFull = !m.streaming) }
                        }
                        is G.Fold -> key(g.items.first().key) {
                            if (i > 0) Spacer(Modifier.height(4.dp))
                            FoldRow(g.items, onAnswer)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(36.dp))
    }
}

/* ============================== 折叠条 ============================== */

@Composable
private fun FoldRow(items: List<Block>, onAnswer: (String, Boolean) -> Unit) {
    var userOpen by remember { mutableStateOf(false) }
    val tools = items.filterIsInstance<Block.Tool>()
    val thinks = items.filterIsInstance<Block.Think>()
    val active = tools.lastOrNull { it.status == Status.RUNNING || it.status == Status.WAITING }
    val thinking = thinks.any { it.running }
    val needConfirm = tools.any { it.status == Status.WAITING }
    val open = userOpen || needConfirm

    val label = when {
        active != null -> Tools.title(active.name, active.args, active.status)
        thinking -> "思考中…"
        tools.isEmpty() -> "思考了 ${thinks.sumOf { it.secs }} 秒"
        items.size == 1 -> Tools.title(tools[0].name, tools[0].args, tools[0].status)
        else -> "执行了 ${tools.size} 个操作"
    }
    val rot by animateFloatAsState(if (open) 90f else 0f, Motion.spec(), label = "chev")

    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.tap { userOpen = !userOpen }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active != null || thinking) { Spinner(); Spacer(Modifier.width(8.dp)) }
            T(label, QQ.gray, 14)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.rotate(rot)) { IconChevron() }
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(Motion.spec()) + fadeIn(Motion.spec()),
            exit = shrinkVertically(Motion.spec()) + fadeOut(Motion.spec())
        ) {
            Column(Modifier.padding(top = 4.dp)) {
                items.forEach { b -> key(b.key) { ItemDetail(b, onAnswer) } }
            }
        }
    }
}

@Composable
private fun ItemDetail(b: Block, onAnswer: (String, Boolean) -> Unit) {
    val rail = Modifier.padding(start = 4.dp, bottom = 6.dp).drawBehind {
        drawLine(QQ.line, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx())
    }.padding(start = 12.dp)
    when (b) {
        is Block.Think -> Column(rail) { T(b.text.trim().ifEmpty { "…" }, QQ.gray, 13) }
        is Block.Tool -> Column(rail) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (b.status) {
                    Status.RUNNING -> Spinner(12.dp)
                    Status.WAITING -> T("•", Color(0xFFF59E0B), 16, bold = true)
                    Status.DONE -> T("✓", QQ.green, 14, bold = true)
                    else -> T("✕", QQ.red, 14, bold = true)
                }
                Spacer(Modifier.width(8.dp))
                T(Tools.title(b.name, b.args, b.status), QQ.text, 14)
            }
            val a = runCatching { JSONObject(b.args) }.getOrDefault(JSONObject())
            val detail = if (b.name == "run_shell") a.str("command") else if (a.length() > 0) a.toString() else ""
            if (detail.isNotEmpty() && b.confirm == null) {
                Spacer(Modifier.height(4.dp))
                T(detail, QQ.gray, 12, mono = true, maxLines = 6)
            }
            if (b.output.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                T(b.output, QQ.gray, 12, mono = true, maxLines = 12)
            }
            val c = b.confirm
            if (c != null) {
                Spacer(Modifier.height(6.dp))
                ConfirmCard(c) { ok -> onAnswer(b.key, ok) }
            }
        }
        else -> {}
    }
}

@Composable
private fun ConfirmCard(c: Confirm, onAnswer: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(QQ.codeBg).padding(12.dp)) {
        T("需要你的确认", if (c.severe) QQ.red else QQ.text, 14, bold = true)
        Spacer(Modifier.height(6.dp))
        T(c.command, QQ.text, 12, mono = true)
        Spacer(Modifier.height(4.dp))
        T(c.reason, QQ.gray, 12)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFE6E9F0)).tap { onAnswer(false) }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) { T("拒绝", QQ.text, 14) }
            Spacer(Modifier.width(10.dp))
            if (c.severe) HoldButton("长按 2 秒允许") { onAnswer(true) }
            else Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(QQ.blue).tap { onAnswer(true) }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) { T("允许", Color.White, 14, bold = true) }
        }
    }
}

/** 不可逆操作：长按满 2 秒才生效，防手滑 */
@Composable
private fun HoldButton(label: String, onDone: () -> Unit) {
    val prog = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFFFE3E0))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val j = scope.launch {
                        prog.animateTo(1f, tween(2000, easing = LinearEasing))
                        done()
                    }
                    tryAwaitRelease()
                    j.cancel()
                    scope.launch { prog.snapTo(0f) }
                })
            }
            .drawBehind { drawRect(QQ.red.copy(alpha = 0.35f), size = Size(size.width * prog.value, size.height)) }
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) { T(label, QQ.red, 14, bold = true) }
}
