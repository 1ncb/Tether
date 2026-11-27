package com.example.ps4ctl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Root(vm) }
    }
}

private val Mono = FontFamily.Monospace
private val Dim = Color(0xFF888888)

@Composable
fun T(s: String, mod: Modifier = Modifier, c: Color = Color.White, size: Int = 13) =
    BasicText(s, mod, style = TextStyle(color = c, fontFamily = Mono, fontSize = size.sp))

@Composable
fun Btn(label: String, inverted: Boolean = false, onClick: () -> Unit) =
    Box(
        Modifier.border(1.dp, Color.White)
            .background(if (inverted) Color.White else Color.Black)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { T(label, c = if (inverted) Color.Black else Color.White) }

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, mod: Modifier = Modifier, num: Boolean = false) =
    BasicTextField(
        value, onChange,
        modifier = mod.border(1.dp, Dim).padding(8.dp),
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontFamily = Mono, fontSize = 13.sp),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = if (num) KeyboardType.Number else KeyboardType.Text),
        decorationBox = { inner -> Box { if (value.isEmpty()) T(hint, c = Dim); inner() } }
    )

@Composable
fun Root(vm: AppVm) {
    var tab by remember { mutableStateOf(0) }
    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .statusBarsPadding().navigationBarsPadding().imePadding().padding(8.dp)
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("CONSOLES", "PAYLOADS", "PKGS", "APPS", "KLOG").forEachIndexed { i, n ->
                Btn(n, inverted = tab == i) { tab = i }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            when (tab) { 0 -> Consoles(vm); 1 -> Payloads(vm); 2 -> Pkgs(vm); 3 -> Apps(vm); else -> Klog(vm) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().border(1.dp, Dim).padding(6.dp)) {
            T("> ${vm.msg}", size = 12, c = Dim)
        }
    }
}

@Composable
fun Consoles(vm: AppVm) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var pp by remember { mutableStateOf("9090") }
    var kp by remember { mutableStateOf("3232") }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("SAVED CONSOLES", Modifier.weight(1f)); Btn("SCAN") { vm.scanPorts() }; Spacer(Modifier.width(4.dp)); Btn("REFRESH") { vm.refresh() }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(vm.consoles) { i, c ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selected = i }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    T(if (i == vm.selected) ">" else " ", Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        T(c.name)
                        T("${c.ip}  L:${c.payloadPort} K:${c.klogPort}", c = Dim, size = 11)
                        T(vm.status[c.ip] ?: "checking...", c = Dim, size = 11)
                    }
                    Btn("X") { vm.removeConsole(i) }
                }
                Divider()
            }
        }
        Spacer(Modifier.height(6.dp))
        T("ADD CONSOLE")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Field(name, { name = it }, "name", Modifier.weight(1f))
            Field(ip, { ip = it }, "192.168.x.x", Modifier.weight(1.3f))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(pp, { pp = it }, "loader", Modifier.weight(1f), num = true)
            Field(kp, { kp = it }, "klog", Modifier.weight(1f), num = true)
            Btn("ADD") {
                if (ip.isNotBlank()) {
                    vm.addConsole(Console(name.ifBlank { "PS4" }, ip.trim(), pp.toIntOrNull() ?: 9090, kp.toIntOrNull() ?: 3232))
                    name = ""; ip = ""
                }
            }
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))

@Composable
fun Payloads(vm: AppVm) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importPayload) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("TARGET: ${vm.current?.let { "${it.name} ${it.ip}:${it.payloadPort}" } ?: "none"}", Modifier.weight(1f), size = 12)
            Btn("IMPORT") { picker.launch(arrayOf("*/*")) }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn {
            itemsIndexed(vm.payloads) { _, p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(p, Modifier.weight(1f))
                    Btn("SEND") { vm.send(p) }
                    Spacer(Modifier.width(4.dp))
                    Btn("X") { vm.deletePayload(p) }
                }
                Divider()
            }
        }
        if (vm.payloads.isEmpty()) T("no payloads. import a .bin / .elf", c = Dim)
    }
}

@Composable
fun Klog(vm: AppVm) {
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(vm::saveLog) }
    val state = rememberLazyListState()
    val vis = if (vm.filter.isBlank()) vm.log else vm.log.filter { it.contains(vm.filter, true) }
    LaunchedEffect(vis.size) { if (vis.isNotEmpty()) state.scrollToItem(vis.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.connected) Btn("DISCONNECT", true) { vm.disconnectKlog() } else Btn("CONNECT") { vm.connectKlog() }
            Btn("CLEAR") { vm.clearLog() }
            Btn("SAVE") { saver.launch("klog_${System.currentTimeMillis()}.txt") }
        }
        Spacer(Modifier.height(4.dp))
        Field(vm.filter, { vm.filter = it }, "filter...", Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().border(1.dp, Color(0xFF333333)).padding(4.dp), state = state) {
            itemsIndexed(vis) { _, l -> T(l, size = 11) }
        }
        T("${vis.size}/${vm.log.size} lines  ${if (vm.connected) "[LIVE]" else "[OFF]"}", c = Dim, size = 11)
    }
}

private fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun Pkgs(vm: AppVm) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.uploadPkgs(it)
    }
    val installPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.installFromPhone(it)
    }
    val view = LocalView.current
    LaunchedEffect(vm.hosting) { view.keepScreenOn = vm.hosting }
    LaunchedEffect(vm.selected) { if (vm.pkgs.isEmpty()) vm.listPkgs() }
    Column(Modifier.fillMaxSize()) {
        T("FTP: ${vm.current?.let { "${it.name} ${it.ip}:${it.ftpPort}" } ?: "none"}", size = 12)
        Spacer(Modifier.height(4.dp))
        Field(vm.pkgDir, { vm.pkgDir = it }, "/data/pkg", Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Btn("REFRESH") { vm.listPkgs() }
            Btn("UPLOAD") { picker.launch(arrayOf("*/*")) }
            Btn("PUSH", inverted = true) { installPicker.launch(arrayOf("*/*")) }
            if (vm.hosting) Btn("STOP") { vm.stopHost() }
        }
        Spacer(Modifier.height(4.dp))
        Btn(if (vm.keepPkg) "KEEP PKG: ON (copy first, needs 2x space)" else "KEEP PKG: OFF (installer moves the file)") { vm.toggleKeep() }
        if (vm.upProgress >= 0f) {
            val n = (vm.upProgress * 20).toInt().coerceIn(0, 20)
            Spacer(Modifier.height(4.dp))
            T("[${"#".repeat(n)}${"-".repeat(20 - n)}] ${(vm.upProgress * 100).toInt()}%", size = 12)
            T(vm.upName, c = Dim, size = 11)
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(vm.pkgs) { _, e ->
                var armed by remember(e.name) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(if (e.isDir) e.name + "/" else e.name, size = 12)
                        if (!e.isDir) T(fmt(e.size), c = Dim, size = 11)
                    }
                    if (!e.isDir) {
                        Btn("INST", inverted = true) { vm.installOnPs4(e) }
                        Spacer(Modifier.width(4.dp))
                        Btn(if (armed) "SURE?" else "DEL", inverted = armed) {
                            if (armed) vm.deletePkg(e.name) else armed = true
                        }
                    }
                }
                Divider()
            }
        }
        T("INST = install file already on PS4 (experimental). PUSH = send phone file, keep app open", c = Dim, size = 11)
    }
}

@Composable
fun Apps(vm: AppVm) {
    LaunchedEffect(vm.selected) { if (vm.apps.isEmpty()) vm.loadApps() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("INSTALLED APPS (${vm.apps.size})", Modifier.weight(1f), size = 12)
            Btn("REFRESH") { vm.loadApps() }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(vm.apps) { _, a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T(a.title, size = 12); T(a.id, c = Dim, size = 11) }
                    Btn("LAUNCH", inverted = true) { vm.launchApp(a) }
                }
                Divider()
            }
        }
    }
}
