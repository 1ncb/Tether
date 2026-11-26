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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
    Text(s, mod, color = c, fontFamily = Mono, fontSize = size.sp)

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("CONSOLES", "PAYLOADS", "KLOG").forEachIndexed { i, n ->
                Btn(n, inverted = tab == i) { tab = i }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            when (tab) { 0 -> Consoles(vm); 1 -> Payloads(vm); else -> Klog(vm) }
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
            T("SAVED CONSOLES", Modifier.weight(1f)); Btn("REFRESH") { vm.refresh() }
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
