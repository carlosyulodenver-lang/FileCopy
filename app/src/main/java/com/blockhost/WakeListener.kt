package com.blockhost

import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wake-on-join: while a server is idle-stopped, BlockHost itself listens on the server's port, answers
 * server-list pings with a "sleeping" MOTD, and when a real player tries to log in it kicks them with a
 * "waking up, retry in a minute" message and starts the server. Works only while the BlockHost foreground
 * service (and the phone's network) is alive. The first join attempt is always rejected.
 */
class WakeListener(private val port: Int, private val motd: String, private val onWake: () -> Unit) {
    @Volatile private var ss: ServerSocket? = null
    private val woke = AtomicBoolean(false)
    private val slots = Semaphore(8)

    fun start(): Boolean = try {
        val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(port)); ss = s
        Thread({ loop(s) }, "wake-listener-$port").apply { isDaemon = true }.start(); true
    } catch (e: Exception) { false }

    fun close() { try { ss?.close() } catch (_: Exception) {}; ss = null }

    private fun loop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: Exception) { return }
            if (!slots.tryAcquire()) { try { c.close() } catch (_: Exception) {}; continue }
            Thread({ try { handle(c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {}; slots.release() } }, "wake-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 4000
        val inp = BufferedInputStream(c.getInputStream()); val out = BufferedOutputStream(c.getOutputStream())
        val len = McPing.readVarInt(inp); if (len <= 0 || len > 4096) return
        val id = McPing.readVarInt(inp); if (id != 0) return
        val proto = McPing.readVarInt(inp); McPing.readString(inp, 512); DataInputStream(inp).readUnsignedShort()
        when (McPing.readVarInt(inp)) {
            1 -> {
                McPing.readVarInt(inp); McPing.readVarInt(inp) // status request
                val j = JSONObject().put("version", JSONObject().put("name", "Sleeping").put("protocol", proto))
                    .put("players", JSONObject().put("max", 0).put("online", 0))
                    .put("description", JSONObject().put("text", motd))
                val b = ByteArrayOutputStream(); McPing.writeVarInt(b, 0); McPing.writeString(b, j.toString())
                McPing.packet(out, b.toByteArray())
                try { // ping/pong
                    McPing.readVarInt(inp); McPing.readVarInt(inp)
                    val payload = ByteArray(8); DataInputStream(inp).readFully(payload)
                    val p = ByteArrayOutputStream(); McPing.writeVarInt(p, 1); p.write(payload); McPing.packet(out, p.toByteArray())
                } catch (_: Exception) {}
            }
            2 -> {
                try { val n = McPing.readVarInt(inp); inp.skip(n.toLong().coerceAtMost(1024)) } catch (_: Exception) {} // drain Login Start
                val msg = JSONObject().put("text", "Server was sleeping and is waking up now. Please try again in about a minute.")
                val b = ByteArrayOutputStream(); McPing.writeVarInt(b, 0); McPing.writeString(b, msg.toString())
                McPing.packet(out, b.toByteArray())
                if (woke.compareAndSet(false, true)) onWake()
            }
        }
    }
}
