package com.example.ps4ctl

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppVm(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ps4", 0)
    private val dir = File(app.filesDir, "payloads").apply { mkdirs() }

    val consoles = mutableStateListOf<Console>()
    val payloads = mutableStateListOf<String>()
    val status = mutableStateMapOf<String, String>()
    var selected by mutableStateOf(0)
    var msg by mutableStateOf("ready")

    val log = mutableStateListOf<String>()
    var filter by mutableStateOf("")
    var connected by mutableStateOf(false)
    private var job: Job? = null

    val current: Console? get() = consoles.getOrNull(selected)

    init {
        val a = JSONArray(prefs.getString("c", "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            consoles += Console(o.getString("n"), o.getString("i"), o.getInt("p"), o.getInt("k"), o.getInt("f"))
        }
        dir.list()?.sorted()?.let { payloads.addAll(it) }
        refresh()
    }

    private fun persist() {
        val a = JSONArray()
        consoles.forEach {
            a.put(JSONObject().put("n", it.name).put("i", it.ip).put("p", it.payloadPort)
                .put("k", it.klogPort).put("f", it.ftpPort))
        }
        prefs.edit().putString("c", a.toString()).apply()
    }

    fun addConsole(c: Console) { consoles += c; persist(); refresh() }
    fun removeConsole(i: Int) {
        consoles.removeAt(i); persist()
        if (selected >= consoles.size) selected = 0
    }

    fun refresh() = consoles.toList().forEach { c ->
        viewModelScope.launch {
            val k = async { Ps4Client.isOpen(c.ip, c.klogPort) }
            val r = async { Ps4Client.isOpen(c.ip, 12800) }
            status[c.ip] = "loader:not probed klog:${if (k.await()) "UP" else "--"} rpi:${if (r.await()) "UP" else "--"}"
        }
    }

    // ---- payloads ----
    fun importPayload(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val name = app.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "payload_${System.currentTimeMillis()}.bin"
            app.contentResolver.openInputStream(uri)!!.use { i -> File(dir, name).outputStream().use { i.copyTo(it) } }
            withContext(Dispatchers.Main) { if (name !in payloads) payloads += name; msg = "imported $name" }
        }.onFailure { withContext(Dispatchers.Main) { msg = "import failed: ${it.message}" } }
    }

    fun deletePayload(name: String) { File(dir, name).delete(); payloads.remove(name) }

    fun send(name: String) {
        val c = current ?: run { msg = "no console selected"; return }
        viewModelScope.launch {
            msg = "sending $name -> ${c.ip}:${c.payloadPort} ..."
            Ps4Client.sendPayload(c, File(dir, name).inputStream())
                .onSuccess { msg = "sent $name ($it bytes)" }
                .onFailure { msg = "FAILED: ${it.message}" }
            refresh()
        }
    }


    // ---- pkgs (FTP) ----
    val pkgs = mutableStateListOf<FtpEntry>()
    var pkgDir by mutableStateOf("/data/pkg")
    var upName by mutableStateOf("")
    var upProgress by mutableStateOf(-1f)

    fun listPkgs() {
        val c = current ?: run { msg = "no console selected"; return }
        val d = pkgDir.trimEnd('/').ifEmpty { "/" }
        viewModelScope.launch {
            msg = "ftp ls $d ..."
            runCatching { withContext(Dispatchers.IO) { Ftp.connectTo(c).use { it.list(d) } } }
                .onSuccess { r -> pkgs.clear(); pkgs += r.sortedBy { it.name.lowercase() }; msg = "${r.size} entries in $d" }
                .onFailure { msg = "FTP: ${it.message}" }
        }
    }

    fun uploadPkgs(uris: List<Uri>) {
        val c = current ?: run { msg = "no console selected"; return }
        val d = pkgDir.trimEnd('/')
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                var name = "upload_${System.currentTimeMillis()}.pkg"; var size = -1L
                app.contentResolver.query(uri, null, null, null, null)?.use { q ->
                    if (q.moveToFirst()) {
                        q.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = q.getString(it) }
                        q.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = q.getLong(it) }
                    }
                }
                upName = name; upProgress = 0f; msg = "uploading $name ..."
                runCatching {
                    app.contentResolver.openInputStream(uri)!!.use { input ->
                        Ftp.connectTo(c).use { f ->
                            f.upload("$d/$name", input) { sent -> if (size > 0) upProgress = sent.toFloat() / size }
                        }
                    }
                }.onSuccess { msg = "uploaded $name" }.onFailure { msg = "upload failed: ${it.message}" }
            }
            upProgress = -1f
            listPkgs()
        }
    }

    fun deletePkg(name: String) {
        val c = current ?: return
        val d = pkgDir.trimEnd('/')
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Ftp.connectTo(c).use { it.delete("$d/$name") } } }
                .onSuccess { pkgs.removeAll { e -> e.name == name }; msg = "deleted $name" }
                .onFailure { msg = "delete failed: ${it.message}" }
        }
    }

    // ---- one-tap install (phone -> PS4 via Remote Package Installer) ----
    private val host = HttpHost(app)
    var hosting by mutableStateOf(false)

    fun installFromPhone(uris: List<Uri>) {
        val c = current ?: run { msg = "no console selected"; return }
        viewModelScope.launch(Dispatchers.IO) {
            val ip = phoneIp(c.ip) ?: run { msg = "can't find phone wifi ip"; return@launch }
            val files = uris.mapNotNull { uri ->
                var name = "file.pkg"; var size = -1L
                app.contentResolver.query(uri, null, null, null, null)?.use { q ->
                    if (q.moveToFirst()) {
                        q.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = q.getString(it) }
                        q.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = q.getLong(it) }
                    }
                }
                if (size <= 0) { msg = "$name: unknown size, skipped"; null }
                else { val h = Dpi.readHeader(app, uri); Dpi.Pkg(uri, name, size, h?.first, h?.second ?: "PS4GD") }
            }
            if (files.isEmpty()) return@launch
            val urls = try { files.map { "http://$ip:${host.port}${host.add(it.uri, it.name, it.size)}" } }
                catch (e: Exception) { msg = "host failed: ${e.message}"; return@launch }
            hosting = true
            val viaPayload = Dpi.hasPayload(app) && files.all { it.contentId != null }
            if (viaPayload) {
                runCatching { Dpi.run(app, c, ip, urls, files) { msg = it } }
                    .onFailure { msg = payloadFail(it) }
            } else {
                val why = when {
                    !Dpi.hasPayload(app) -> "this build has no payload.bin"
                    files.any { it.contentId == null } -> "not a ps4 pkg"
                    else -> "unknown"
                }
                for (u in urls) {
                    msg = "$why -> trying RPI ..."
                    Ps4Client.rpiInstall(c, listOf(u))
                        .onSuccess { msg = "RPI: $it" }
                        .onFailure { msg = "$why; RPI also failed: ${it.message}" }
                }
            }
        }
    }

    /** Install a PKG that is already on the PS4 (e.g. /data/pkg) using the DPI payload with a local path. EXPERIMENTAL. */
    fun installOnPs4(e: FtpEntry) {
        val c = current ?: run { msg = "no console selected"; return }
        val path = "${pkgDir.trimEnd('/')}/${e.name}"
        viewModelScope.launch(Dispatchers.IO) {
            val ip = phoneIp(c.ip) ?: run { msg = "can't find phone wifi ip"; return@launch }
            if (!Dpi.hasPayload(app)) { msg = "this build has no payload.bin"; return@launch }
            msg = "reading ${e.name} header over FTP ..."
            val h = runCatching { Ftp.connectTo(c).use { Dpi.parseHeader(it.head(path, 0x80)) } }.getOrNull()
            if (h == null) { msg = "${e.name}: not a ps4 pkg, or FTP read failed"; return@launch }
            runCatching { Dpi.run(app, c, ip, listOf(path), listOf(Dpi.Pkg(Uri.EMPTY, e.name, e.size, h.first, h.second))) { msg = it } }
                .onFailure { msg = payloadFail(it) }
        }
    }

    fun scanPorts() {
        val c = current ?: run { msg = "no console selected"; return }
        viewModelScope.launch {
            msg = "scanning ${c.ip} ..."
            val ports = listOf(2121, 3232, 12800, 744, 1337, 8080) // loader ports are NOT probed: a bare connect can kill GoldHEN's loader
            val open = ports.map { p -> async { p to Ps4Client.isOpen(c.ip, p, 800) } }
                .awaitAll().filter { it.second }.map { it.first }
            msg = "open ports: ${open.joinToString(" ").ifEmpty { "none" }}"
        }
    }

    private fun payloadFail(e: Throwable): String =
        if (e.message.orEmpty().contains("refused", true))
            "loader refused. GoldHEN > Servers Settings: toggle PayLoader Server off then on, then retry without REFRESH/SCAN first"
        else "payload route failed: ${e.message}"

    fun stopHost() { host.stop(); hosting = false }
    override fun onCleared() { host.stop() }

    // ---- klog ----
    fun connectKlog() {
        val c = current ?: run { msg = "no console selected"; return }
        job?.cancel()
        job = viewModelScope.launch {
            connected = true
            log += "[app] connecting ${c.ip}:${c.klogPort}"
            Ps4Client.klogStream(c).catch { log += "[app] error: ${it.message}" }.collect {
                log += it
                if (log.size > 20_000) repeat(2_000) { log.removeAt(0) }
            }
            log += "[app] disconnected"
            connected = false
        }
    }

    fun disconnectKlog() { job?.cancel() }
    fun clearLog() = log.clear()

    fun saveLog(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openOutputStream(uri)!!.use { it.write(log.joinToString("\n").toByteArray()) }
            withContext(Dispatchers.Main) { msg = "log saved (${log.size} lines)" }
        }.onFailure { withContext(Dispatchers.Main) { msg = "save failed: ${it.message}" } }
    }
}
