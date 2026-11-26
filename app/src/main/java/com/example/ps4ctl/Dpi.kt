package com.example.ps4ctl

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GoldHEN BinLoader route (DirectPackageInstaller payload).
 * 1. patch phone ip:port into payload.bin (6 marker bytes 0xB4), 2. send it to :9090,
 * 3. payload connects back here once per package and once more to be told to quit.
 * Per-package message (little-endian): u32 1, str url, str name, str contentId, str type, u64 size, u32 iconLen(0).
 * str = u32 length + bytes.
 */
object Dpi {
    const val META_PORT = 9899

    class Pkg(val uri: Uri, val name: String, val size: Long, val contentId: String?, val type: String)

    fun hasPayload(ctx: Context) = ctx.assets.list("")?.contains("payload.bin") == true

    /** Reads content id + type from the PS4 PKG header (magic 7F 'CNT', id at 0x40, type at 0x74). */
    fun readHeader(ctx: Context, uri: Uri): Pair<String, String>? = runCatching {
        val b = ByteArray(0x80); var n = 0
        ctx.contentResolver.openInputStream(uri)!!.use {
            while (n < b.size) { val r = it.read(b, n, b.size - n); if (r < 0) break; n += r }
        }
        if (n < 0x80 || b[0] != 0x7F.toByte() || b[1] != 'C'.code.toByte() || b[2] != 'N'.code.toByte() || b[3] != 'T'.code.toByte())
            return@runCatching null
        val id = String(b, 0x40, 0x24, Charsets.US_ASCII).trimEnd('\u0000').trim()
        if (id.length < 20) return@runCatching null
        val ct = ByteBuffer.wrap(b, 0x74, 4).int // big-endian
        id to (if (ct == 0x1B) "PS4AC" else "PS4GD")
    }.getOrNull()

    private fun patched(ctx: Context, ip: String, port: Int): ByteArray {
        val b = ctx.assets.open("payload.bin").use { it.readBytes() }
        var at = -1
        for (i in 0..b.size - 6) if ((0..5).all { b[i + it] == 0xB4.toByte() }) { at = i; break }
        if (at < 0) throw IOException("payload.bin has no connection marker")
        ip.split('.').forEachIndexed { k, o -> b[at + k] = o.toInt().toByte() }
        b[at + 4] = (port shr 8).toByte(); b[at + 5] = port.toByte() // network byte order
        return b
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    private fun message(p: Pkg, url: String): ByteArray {
        val o = ByteArrayOutputStream()
        fun str(s: String, max: Int) {
            val b = s.toByteArray(); val t = if (b.size >= max) b.copyOf(max - 1) else b
            o.write(le32(t.size)); o.write(t)
        }
        o.write(le32(1))
        str(url, 0x800); str(p.name.removeSuffix(".pkg").take(150), 0x259)
        str(p.contentId!!, 0x30); str(p.type, 0x10)
        o.write(le64(p.size)); o.write(le32(0))
        return o.toByteArray()
    }

    suspend fun run(ctx: Context, c: Console, phoneIp: String, urls: List<String>, pkgs: List<Pkg>, status: (String) -> Unit) {
        val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(META_PORT)) }
        try {
            status("sending installer payload ...")
            Ps4Client.sendPayload(c, ByteArrayInputStream(patched(ctx, phoneIp, META_PORT))).getOrThrow()
            for ((i, p) in pkgs.withIndex()) {
                status("waiting for PS4 (${i + 1}/${pkgs.size}) ${p.contentId} ${p.type}")
                ss.soTimeout = if (i == 0) 60_000 else 90_000
                val s = try { ss.accept() } catch (e: SocketTimeoutException) {
                    throw IOException("PS4 never connected back - check its notifications")
                }
                s.use { it.tcpNoDelay = true; it.getOutputStream().apply { write(message(p, urls[i])); flush() } }
            }
            ss.soTimeout = 90_000
            ss.accept().use { it.getOutputStream().apply { write(le32(0)); flush() } } // tell payload to exit
            status("queued ${pkgs.size} pkg(s). PS4 downloads from phone - keep app open")
        } finally { runCatching { ss.close() } }
    }
}
