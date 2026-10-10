package com.blockhost

import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

/** Minecraft Java "Server List Ping" — a real protocol-level check that a server is answering. */
object McPing {
    data class Status(val online: Int, val max: Int, val motd: String, val version: String)

    fun writeVarInt(o: OutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) { o.write(v); return }
            o.write((v and 0x7F) or 0x80); v = v ushr 7
        }
    }

    fun readVarInt(i: InputStream): Int {
        var r = 0; var shift = 0
        while (true) {
            val b = i.read(); if (b < 0) throw EOFException()
            r = r or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return r
            shift += 7; if (shift > 35) throw IOException("VarInt too long")
        }
    }

    fun writeString(o: OutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8); writeVarInt(o, b.size); o.write(b)
    }

    fun readString(i: InputStream, maxLen: Int = 65536): String {
        val n = readVarInt(i); if (n < 0 || n > maxLen * 4) throw IOException("bad string length")
        val b = ByteArray(n); DataInputStream(i).readFully(b); return String(b, Charsets.UTF_8)
    }

    fun packet(o: OutputStream, body: ByteArray) { writeVarInt(o, body.size); o.write(body); o.flush() }

    fun ping(host: String, port: Int, timeoutMs: Int = 2500): Status? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
            val out = BufferedOutputStream(s.getOutputStream()); val inp = BufferedInputStream(s.getInputStream())
            val hs = ByteArrayOutputStream()
            writeVarInt(hs, 0); writeVarInt(hs, -1); writeString(hs, host)
            DataOutputStream(hs).writeShort(port); writeVarInt(hs, 1)
            packet(out, hs.toByteArray()); packet(out, byteArrayOf(0))
            readVarInt(inp); val id = readVarInt(inp)
            if (id != 0) null else {
                val j = JSONObject(readString(inp))
                val pl = j.optJSONObject("players")
                val d = j.opt("description")
                val motd = when (d) { is JSONObject -> d.optString("text"); is String -> d; else -> "" }
                Status(pl?.optInt("online", 0) ?: 0, pl?.optInt("max", 0) ?: 0, motd, j.optJSONObject("version")?.optString("name") ?: "")
            }
        }
    } catch (e: Exception) { null }
}
