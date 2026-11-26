package com.example.ps4ctl

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

data class Console(
    val name: String,
    val ip: String,
    val payloadPort: Int = 9090,
    val klogPort: Int = 3232,
    val ftpPort: Int = 2121
)

object Ps4Client {

    /** Streams a payload to the loader port, then closes the connection. */
    suspend fun sendPayload(c: Console, payload: InputStream): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                payload.use { input ->
                    Socket().use { s ->
                        s.connect(InetSocketAddress(c.ip, c.payloadPort), 4000)
                        var total = 0L
                        val out = s.getOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                        out.flush()
                        total
                    }
                }
            }
        }

    suspend fun isOpen(ip: String, port: Int, timeoutMs: Int = 1500): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }; true }
                .getOrDefault(false)
        }

    /** Live klog lines. Cancelling the collector closes the socket. */
    fun klogStream(c: Console): Flow<String> = channelFlow {
        val s = Socket()
        val reader = launch(Dispatchers.IO) {
            try {
                s.connect(InetSocketAddress(c.ip, c.klogPort), 4000)
                s.getInputStream().bufferedReader().forEachLine { trySend(it) }
            } catch (e: Exception) {
                if (!s.isClosed) trySend("[app] ${e.message}")
            }
            close()
        }
        awaitClose { runCatching { s.close() }; reader.cancel() }
    }.buffer(Channel.UNLIMITED)
}
