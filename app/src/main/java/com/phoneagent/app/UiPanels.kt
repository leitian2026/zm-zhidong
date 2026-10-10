package com.phoneagent.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun App(vm: ChatViewModel) {
    var drawer by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = drawer && !settingsOpen) { drawer = false }

    Box(Modifier.fillMaxSize().background(QQ.bg)) {
        ChatScreen(vm) { drawer = true }
        DrawerPanel(vm, drawer, close = { drawer = false }, openSettings = { settingsOpen = true; drawer = false })
        SettingsPanel(vm, settingsOpen) { settingsOpen = false }
    }
}

/* ============================== 侧边抽屉 ============================== */

@Composable
private fun DrawerPanel(vm: ChatViewModel, visible: Boolean, close: () -> Unit, openSettings: () -> Unit) {
    val sessions by vm.sessions.collectAsState()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(Motion.spec()), exit = fadeOut(Motion.spec())) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).tap(close))
        }
        AnimatedVisibility(
            visible,
            enter = slideInHorizontally(Motion.spec()) { -it },
            exit = slideOutHorizontally(Motion.spec()) { -it }
        ) {
            Column(Modifier.fillMaxHeight().width(300.dp).background(Color.White)) {
                Box(
                    Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(QQ.title1, QQ.title2)))
                        .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 18.dp, vertical = 16.dp)
                ) { T("手机助手", Color.White, 20, bold = true) }
                Box(
                    Modifier.padding(14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(QQ.blue)
                        .tap { vm.newChat(); close() }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { T("＋ 新对话", Color.White, 15, bold = true) }
                LazyColumn(Modifier.weight(1f)) {
                    items(sessions, key = { it.id }) { s ->
                        Row(
                            Modifier.fillMaxWidth().tap { vm.open(s.id); close() }.padding(horizontal = 18.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                T(s.title, QQ.text, 15, maxLines = 1)
                                T(fmt.format(Date(s.updated)), QQ.gray, 11)
                            }
                            Box(Modifier.tap { vm.delete(s.id) }.padding(8.dp)) { T("✕", QQ.gray, 14) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(QQ.line))
                Box(
                    Modifier.fillMaxWidth().tap(openSettings)
                        .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 18.dp, vertical = 16.dp)
                ) { T("设置", QQ.text, 15) }
            }
        }
    }
}

/* ============================== 设置页 ============================== */

@Composable
private fun SettingsPanel(vm: ChatViewModel, visible: Boolean, close: () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = slideInHorizontally(Motion.spec()) { it },
        exit = slideOutHorizontally(Motion.spec()) { it }
    ) {
        val st = vm.settings
        val scope = rememberCoroutineScope()
        var rootResult by remember { mutableStateOf("") }
        var auto by remember { mutableStateOf(st.autoSafe) }

        Column(Modifier.fillMaxSize().background(QQ.bg)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(QQ.title1, QQ.title2)))
                    .windowInsetsPadding(WindowInsets.statusBars).height(54.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.tap(close).padding(end = 12.dp, top = 4.dp, bottom = 4.dp)) { T("‹", Color.White, 28) }
                T("设置", Color.White, 17, bold = true)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Prov.ids.forEach { id ->
                    Card {
                        T(Prov.names[id] ?: id, QQ.text, 16, bold = true)
                        Field("API Key", st.key(id), secret = true) { st.setKey(id, it) }
                        Field("模型名称（可改）", st.model(id)) { st.setModel(id, it) }
                        if (id == "openai") Field("Base URL（兼容接口可改）", st.baseUrl()) { st.setBaseUrl(it) }
                    }
                }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("自动放行安全命令", QQ.text, 15, bold = true)
                            T("关闭后为严格模式：所有操作都要你确认", QQ.gray, 12)
                        }
                        Toggle(auto) { auto = it; st.autoSafe = it }
                    }
                }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(20.dp)).background(QQ.blue).tap {
                                rootResult = "检测中…"
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { RootShell.run("id") }
                                    rootResult = (if (r.code == 0) "root 可用：" else "root 不可用：") + r.out
                                }
                            }.padding(horizontal = 18.dp, vertical = 8.dp)
                        ) { T("测试 root", Color.White, 14, bold = true) }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) { T(rootResult, QQ.gray, 12, mono = true) }
                    }
                }
                T("API Key 只保存在本机应用私有目录，对话内容只发送给你选择的模型服务商。", QQ.gray, 12)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) { content() }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        T(label, QQ.gray, 12)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(QQ.codeBg).padding(12.dp)) {
            BasicTextField(
                v, { v = it; onChange(it) }, Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = QQ.text, fontSize = 15.sp),
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                cursorBrush = SolidColor(QQ.blue)
            )
        }
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (on) QQ.blue else Color(0xFFC9CEDA), Motion.spec(), label = "tb")
    val x by animateDpAsState(if (on) 22.dp else 2.dp, Motion.spec(), label = "tx")
    Box(Modifier.width(48.dp).height(28.dp).clip(CircleShape).background(bg).tap { onChange(!on) }) {
        Box(Modifier.offset(x = x, y = 2.dp).size(24.dp).clip(CircleShape).background(Color.White))
    }
}
