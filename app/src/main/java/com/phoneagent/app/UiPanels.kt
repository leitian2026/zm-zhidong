package com.phoneagent.app

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
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
                        Field("每分钟请求上限（0 = 不限制，按所用模型在官网看到的数值填）", st.rpm(id).toString()) { st.setRpm(id, it) }
                        Field("每天请求上限（0 = 不限制，仅用于提醒）", st.rpd(id).toString()) { st.setRpd(id, it) }
                        if (id == "openai") Field("Base URL（兼容接口可改）", st.baseUrl()) { st.setBaseUrl(it) }
                    }
                }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("自动放行非删除命令", QQ.text, 15, bold = true)
                            T("删除类命令始终要确认；关闭后为严格模式，所有操作都要你确认", QQ.gray, 12)
                        }
                        Toggle(auto) { auto = it; st.autoSafe = it }
                    }
                }
                ProxyCard(st)
                SecretsCard()
                ExtCard()
                BackupCard()
                NotesCard(vm)
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

/* ============================== 经验笔记 ============================== */

@Composable
private fun NotesCard(vm: ChatViewModel) {
    val scope = rememberCoroutineScope()
    val dir = vm.notesDir
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var openName by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var readOnly by remember { mutableStateOf(false) }
    var delArm by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { RootShell.run("ls -1 " + shq(dir) + " 2>/dev/null") }
            files = if (r.code == 0) {
                r.out.lines().map { it.trim() }.filter { it.endsWith(".md") }
                    .sortedWith(compareBy({ it != "index.md" }, { it }))
            } else emptyList()
        }
    }
    LaunchedEffect(Unit) { reload() }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { T("经验笔记", QQ.text, 15, bold = true) }
            Box(Modifier.tap { msg = ""; reload() }.padding(8.dp)) { T("刷新", QQ.blue, 13) }
        }
        T("模型办成事后会自己记在这里，下次直接用。你可以查看、修改或删除。", QQ.gray, 12)
        if (files.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            T("还没有笔记", QQ.gray, 13)
        }
        files.forEach { f ->
            Box(
                Modifier.fillMaxWidth().tap {
                    delArm = false; msg = ""
                    if (openName == f) openName = "" else scope.launch {
                        val r = withContext(Dispatchers.IO) { RootShell.run("cat " + shq("$dir/$f")) }
                        text = r.out
                        readOnly = r.out.endsWith("[输出已截断]")
                        openName = f
                    }
                }.padding(vertical = 8.dp)
            ) { T((if (openName == f) "▾ " else "▸ ") + f, QQ.text, 14) }
        }
        if (openName.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(QQ.codeBg).padding(12.dp)) {
                BasicTextField(
                    text, { if (!readOnly) text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    textStyle = TextStyle(color = QQ.text, fontSize = 13.sp),
                    readOnly = readOnly,
                    cursorBrush = SolidColor(QQ.blue)
                )
            }
            if (readOnly) {
                Spacer(Modifier.height(4.dp))
                T("文件较长，这里只能查看和删除，不能直接修改", QQ.gray, 12)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!readOnly) Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(QQ.blue).tap {
                        val name = openName
                        val b64 = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { RootShell.run("echo $b64 | base64 -d > " + shq("$dir/$name")) }
                            msg = if (r.code == 0) "已保存" else "保存失败：" + r.out
                        }
                    }.padding(horizontal = 18.dp, vertical = 7.dp)
                ) { T("保存", Color.White, 14, bold = true) }
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (delArm) QQ.red else Color(0xFFC9CEDA)).tap {
                        if (!delArm) { delArm = true; return@tap }
                        val name = openName
                        scope.launch {
                            val idx = shq("$dir/index.md")
                            val cmd = "rm -f " + shq("$dir/$name") + "; if [ -f $idx ]; then grep -vF " + shq("→ $name") +
                                " $idx > $idx.tmp; mv $idx.tmp $idx; fi"
                            val r = withContext(Dispatchers.IO) { RootShell.run(cmd) }
                            msg = if (r.code == 0) "已删除 $name" else "删除失败：" + r.out
                            delArm = false; openName = ""
                            reload()
                        }
                    }.padding(horizontal = 18.dp, vertical = 7.dp)
                ) { T(if (delArm) "再点一次确认删除" else "删除", Color.White, 14, bold = true) }
            }
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            T(msg, QQ.gray, 12)
        }
    }
}


/* ============================== 联网代理 ============================== */

@Composable
private fun ProxyCard(st: Settings) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    Card {
        T("联网代理（给 root 命令用）", QQ.text, 15, bold = true)
        T("root 进程通常不走 VPN。填 VPN 软件的本地代理端口，如 127.0.0.1:7890，只有 SOCKS 时填 socks5h://127.0.0.1:10808；留空 = 不使用", QQ.gray, 12)
        Field("代理地址", st.proxy) { st.proxy = it }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(QQ.blue).tap {
                    result = "检测中…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            RootShell.run("curl -m 10 -sS https://api.cloudflare.com/cdn-cgi/trace 2>&1 | head -c 300", 20_000)
                        }
                        result = r.out.ifBlank { "无输出" }
                    }
                }.padding(horizontal = 18.dp, vertical = 8.dp)
            ) { T("测试连接 Cloudflare", Color.White, 14, bold = true) }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { T(result, QQ.gray, 12, mono = true) }
        }
    }
}

/* ============================== 密钥 ============================== */

private val SECRET_NAME = Regex("^[A-Za-z0-9_.-]{1,40}$")

@Composable
private fun SecretsCard() {
    val scope = rememberCoroutineScope()
    val dir = RootShell.SECRETS_DIR
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var nm by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var nonce by remember { mutableStateOf(0) }
    var delArm by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { RootShell.run("ls -1 " + shq(dir) + " 2>/dev/null") }
            names = if (r.code == 0) r.out.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        }
    }
    LaunchedEffect(Unit) { reload() }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { T("密钥", QQ.text, 15, bold = true) }
            Box(Modifier.tap { msg = ""; reload() }.padding(8.dp)) { T("刷新", QQ.blue, 13) }
        }
        T("存在手机的 $dir（仅 root 可读）。模型只知道名称，用时从文件读取；在这里填写，值不会出现在聊天里。", QQ.gray, 12)
        key(nonce) {
            Field("名称（如 cf_token）", "") { nm = it.trim() }
            Field("值", "", secret = true) { value = it.trim() }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.clip(RoundedCornerShape(20.dp)).background(QQ.blue).tap {
                if (!SECRET_NAME.matches(nm)) { msg = "名称只能用字母、数字、下划线、点、减号"; return@tap }
                if (value.isEmpty()) { msg = "值不能为空"; return@tap }
                val b64 = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
                val path = shq("$dir/$nm")
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        RootShell.run("mkdir -p " + shq(dir) + " && chmod 700 " + shq(dir) +
                            " && echo " + b64 + " | base64 -d > " + path + " && chmod 600 " + path)
                    }
                    if (r.code == 0) {
                        msg = "已保存 $nm"
                        nm = ""; value = ""; nonce++
                        reload()
                    } else msg = "保存失败：" + r.out
                }
            }.padding(horizontal = 18.dp, vertical = 8.dp)
        ) { T("保存", Color.White, 14, bold = true) }
        names.forEach { n ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { T(n, QQ.text, 14, mono = true) }
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (delArm == n) QQ.red else Color(0xFFC9CEDA)).tap {
                        if (delArm != n) { delArm = n; return@tap }
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { RootShell.run("rm -f " + shq("$dir/$n")) }
                            msg = if (r.code == 0) "已删除 $n" else "删除失败：" + r.out
                            delArm = ""
                            reload()
                        }
                    }.padding(horizontal = 14.dp, vertical = 5.dp)
                ) { T(if (delArm == n) "再点一次确认" else "删除", Color.White, 13) }
            }
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            T(msg, QQ.gray, 12)
        }
    }
}


/* ============================== 扩展环境 ============================== */

@Composable
private fun ExtCard() {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    Card {
        T("扩展环境", QQ.text, 15, bold = true)
        T("模型自己装的工具、环境脚本 env.sh 和清单 README.md 都在 ${RootShell.EXT_DIR}，卸载重装 App 后仍保留。若环境脚本写坏导致命令异常，先点下面停用它（只是改名为 env.sh.bak）。", QQ.gray, 12)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(QQ.blue).tap {
                    val f = shq(RootShell.EXT_DIR + "/env.sh")
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            RootShell.run("if [ -f $f ]; then mv -f $f $f.bak && echo 已停用 env.sh; else echo 没有环境脚本; fi", withEnv = false)
                        }
                        msg = r.out.ifBlank { "完成" }
                    }
                }.padding(horizontal = 18.dp, vertical = 8.dp)
            ) { T("停用环境脚本", Color.White, 14, bold = true) }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { T(msg, QQ.gray, 12) }
        }
    }
}


/* ============================== 备份 / 还原 ============================== */

@Composable
private fun BackupCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var withSecrets by remember { mutableStateOf(false) }
    var restoreArm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null) {
            busy = true; msg = "导出中…"
            scope.launch {
                msg = withContext(Dispatchers.IO) { Backup.export(ctx, uri, withSecrets) }
                busy = false
            }
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true; msg = "还原中…"
            scope.launch {
                msg = withContext(Dispatchers.IO) { Backup.restore(ctx, uri) }
                busy = false
            }
        }
    }

    Card {
        T("备份 / 还原", QQ.text, 15, bold = true)
        T("把模型的记忆库和功能扩展库（工具、环境脚本、清单、笔记）打成一个文件，存到任何位置；也可以从任何位置选回来，还原到它原本使用的位置。", QQ.gray, 12)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("导出时同时包含密钥", QQ.text, 14)
                T("密钥在文件里是明文，默认不带", QQ.gray, 12)
            }
            Toggle(withSecrets) { withSecrets = it }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(QQ.blue).tap {
                    if (busy) return@tap
                    restoreArm = false
                    saver.launch("phoneagent-ext-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".tar.gz")
                }.padding(horizontal = 18.dp, vertical = 8.dp)
            ) { T("导出到…", Color.White, 14, bold = true) }
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(if (restoreArm) QQ.red else Color(0xFFC9CEDA)).tap {
                    if (busy) return@tap
                    if (!restoreArm) { restoreArm = true; return@tap }
                    restoreArm = false
                    opener.launch(arrayOf("*/*"))
                }.padding(horizontal = 18.dp, vertical = 8.dp)
            ) { T(if (restoreArm) "再点一次，选择备份文件" else "从文件还原…", Color.White, 14, bold = true) }
        }
        if (restoreArm) {
            Spacer(Modifier.height(6.dp))
            T("还原会覆盖同名文件。只还原你自己导出的备份。", QQ.gray, 12)
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            T(msg, QQ.gray, 12)
        }
    }
}
