package com.example.ps4ctl

import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

data class FtpEntry(val name: String, val isDir: Boolean, val size: Long)

/** Minimal passive-mode FTP client (enough for GoldHEN's FTP server on 2121). */
class Ftp private constructor(private val host: String) : Closeable {
    private lateinit var sock: Socket
    private lateinit var rd: BufferedReader
    private lateinit var wr: OutputStream

    companion object {
        fun connectTo(c: Console): Ftp = Ftp(c.ip).also { it.start(c.ftpPort) }
    }

    private fun start(port: Int) {
        sock = Socket().apply { connect(InetSocketAddress(host, port), 4000); soTimeout = 20_000 }
        rd = sock.getInputStream().bufferedReader()
        wr = sock.getOutputStream()
        read() // 220 banner
        val u = cmd("USER anonymous")
        if (u.first == 331) {
            val p = cmd("PASS guest@")
            if (p.first !in 200..299) throw IOException("login failed: ${p.second}")
        }
    }

    private fun read(): Pair<Int, String> {
        val first = rd.readLine() ?: throw IOException("connection closed")
        val code = first.take(3).toIntOrNull() ?: throw IOException("bad reply: $first")
        var last = first
        if (first.length > 3 && first[3] == '-') {
            while (true) { last = rd.readLine() ?: break; if (last.startsWith("$code ")) break }
        }
        return code to last
    }

    private fun cmd(c: String): Pair<Int, String> {
        wr.write("$c\r\n".toByteArray()); wr.flush(); return read()
    }

    private fun pasv(): Socket {
        val (code, text) = cmd("PASV")
        if (code != 227) throw IOException("PASV failed: $text")
        val n = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(text)
            ?.groupValues?.drop(1)?.map { it.toInt() } ?: throw IOException("bad PASV: $text")
        return Socket().apply { connect(InetSocketAddress(host, n[4] * 256 + n[5]), 4000); soTimeout = 20_000 }
    }

    fun list(path: String): List<FtpEntry> {
        cmd("TYPE A")
        val d = pasv()
        val (code, t) = cmd("LIST $path")
        if (code !in 100..199) { d.close(); throw IOException(t) }
        val lines = d.getInputStream().bufferedReader().readLines()
        d.close(); read()
        val parsed = lines.mapNotNull { parse(it) }
        return if (parsed.isEmpty() && lines.isNotEmpty()) lines.map { FtpEntry(it.trim(), false, 0) } else parsed
    }

    private fun parse(l: String): FtpEntry? {
        val p = l.trim().split(Regex("\\s+"), 9)
        if (p.size < 9 || p[8] == "." || p[8] == "..") return null
        return FtpEntry(p[8], l.startsWith("d"), p[4].toLongOrNull() ?: 0)
    }

    fun upload(path: String, input: InputStream, onProgress: (Long) -> Unit) {
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) cmd("MKD $dir") // ignore result: usually "already exists"
        cmd("TYPE I")
        val d = pasv()
        val (code, t) = cmd("STOR $path")
        if (code !in 100..199) { d.close(); throw IOException(t) }
        val out = d.getOutputStream()
        val buf = ByteArray(256 * 1024)
        var sent = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n); sent += n; onProgress(sent)
        }
        out.flush(); d.close()
        sock.soTimeout = 120_000
        val (c2, t2) = read()
        if (c2 !in 200..299) throw IOException(t2)
    }

    fun delete(path: String) {
        val (c, t) = cmd("DELE $path")
        if (c !in 200..299) throw IOException(t)
    }

    override fun close() {
        if (::sock.isInitialized) { runCatching { cmd("QUIT") }; runCatching { sock.close() } }
    }
}
