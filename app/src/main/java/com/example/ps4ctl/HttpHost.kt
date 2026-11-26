package com.example.ps4ctl

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Tiny HTTP file server (with Range support) so the PS4 can pull a phone file over Wi-Fi. */
class HttpHost(private val ctx: Context) {
    private class Item(val uri: Uri, val size: Long)
    private val items = ConcurrentHashMap<String, Item>()
    @Volatile private var server: ServerSocket? = null
    val port = 9898

    fun add(uri: Uri, name: String, size: Long): String {
        val id = Integer.toHexString(uri.toString().hashCode())
        items[id] = Item(uri, size)
        if (server?.isClosed != false) {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
            server = ss
            thread(isDaemon = true) {
                while (!ss.isClosed) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { handle(s) }
                }
            }
        }
        return "/$id/${Uri.encode(name)}"
    }

    fun stop() { runCatching { server?.close() }; server = null; items.clear() }

    private fun handle(s: Socket) = s.use {
        runCatching {
            val rd = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val req = rd.readLine() ?: return@runCatching
            val p = req.split(" ")
            var range: String? = null
            while (true) {
                val h = rd.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Range:", true)) range = h.substringAfter(":").trim()
            }
            val out = BufferedOutputStream(s.getOutputStream())
            val item = items[p.getOrElse(1) { "" }.trimStart('/').substringBefore('/')]
            if (item == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); return@runCatching
            }
            var start = 0L; var end = item.size - 1
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            if (m != null) {
                val a = m.groupValues[1]; val b = m.groupValues[2]
                if (a.isEmpty() && b.isNotEmpty()) start = (item.size - b.toLong()).coerceAtLeast(0)
                else { start = a.toLongOrNull() ?: 0; end = b.toLongOrNull() ?: end }
            }
            end = minOf(end, item.size - 1)
            val len = end - start + 1
            val head = (if (m != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${item.size}\r\n" else "HTTP/1.1 200 OK\r\n") +
                "Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray())
            if (p[0] == "GET") {
                ctx.contentResolver.openFileDescriptor(item.uri, "r")!!.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        fis.channel.position(start)
                        val buf = ByteArray(128 * 1024); var left = len
                        while (left > 0) {
                            val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n); left -= n
                        }
                    }
                }
            }
            out.flush()
        }
    }
}

/** The phone's Wi-Fi IPv4, preferring the same /24 as the console. */
fun phoneIp(consoleIp: String): String? {
    val all = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.mapNotNull { it.hostAddress }
    return all.firstOrNull { it.substringBeforeLast('.') == consoleIp.substringBeforeLast('.') } ?: all.firstOrNull()
}
