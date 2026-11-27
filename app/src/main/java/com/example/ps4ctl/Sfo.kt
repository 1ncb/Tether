package com.example.ps4ctl

/** Minimal PARAM.SFO reader: returns the TITLE string. */
object Sfo {
    fun title(b: ByteArray): String? = runCatching {
        fun u8(o: Int) = b[o].toInt() and 0xFF
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (b.size < 20 || u8(0) != 0 || u8(1) != 'P'.code || u8(2) != 'S'.code || u8(3) != 'F'.code) return@runCatching null
        val keyStart = u32(8); val dataStart = u32(12); val count = u32(16)
        for (i in 0 until count) {
            val e = 20 + i * 16
            val ko = keyStart + u16(e)
            var ke = ko; while (b[ke].toInt() != 0) ke++
            if (String(b, ko, ke - ko) == "TITLE") {
                val off = dataStart + u32(e + 12); val len = u32(e + 4)
                var end = off; while (end < off + len && b[end].toInt() != 0) end++
                return@runCatching String(b, off, end - off, Charsets.UTF_8)
            }
        }
        null
    }.getOrNull()
}
