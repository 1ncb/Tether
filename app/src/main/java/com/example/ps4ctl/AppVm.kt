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
            val p = async { Ps4Client.isOpen(c.ip, c.payloadPort) }
            val k = async { Ps4Client.isOpen(c.ip, c.klogPort) }
            status[c.ip] = "loader:${if (p.await()) "UP" else "--"} klog:${if (k.await()) "UP" else "--"}"
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
