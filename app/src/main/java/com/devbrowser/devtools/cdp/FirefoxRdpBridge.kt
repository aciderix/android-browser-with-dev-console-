package com.devbrowser.devtools.cdp

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Stub bridge from Firefox Remote Debugging Protocol (RDP, TCP, length-prefixed
 * JSON) onto Chrome DevTools Protocol — so the same UI can talk to GeckoView.
 *
 * Status: incomplete. The TCP transport and frame parsing are wired up, but
 * the actor model translation onto CDP domains is a large piece of work that
 * is not finished. With remoteDebuggingEnabled = true GeckoView listens on
 * about:config "devtools.debugger.remote-port" (default 6000). This class
 * connects, reads the first hello packet, and exposes raw RDP traffic so a
 * future translator layer can map RDP actors onto CDP domains:
 *
 *   RDP "console" actor       → Runtime / Console / Log domains
 *   RDP "inspector" actor     → DOM / CSS domains
 *   RDP "thread" actor        → Debugger domain
 *   RDP "netMonitor" actor    → Network domain
 *   RDP "performance" actor   → Performance / Tracing domains
 *   RDP "memory" actor        → HeapProfiler domain
 *
 * Until that translator exists, GeckoView tabs will report "Unsupported" in
 * the DevTools status indicator. WebView (Chromium) is the supported engine.
 */
class FirefoxRdpBridge(private val host: String = "127.0.0.1", private val port: Int = 6000) {

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val rawPackets: SharedFlow<String> = _events.asSharedFlow()

    fun connect(): Boolean = try {
        val socket = Socket(host, port)
        Thread { readLoop(socket.getInputStream()) }.start()
        true
    } catch (e: Exception) {
        Log.w("FirefoxRdpBridge", "connect failed", e)
        false
    }

    private fun readLoop(input: InputStream) {
        try {
            while (true) {
                // RDP frames are: <decimal length>:<json>
                val lenBuf = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1) return
                    if (b == ':'.code) break
                    lenBuf.append(b.toChar())
                }
                val len = lenBuf.toString().toIntOrNull() ?: return
                val payload = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(payload, read, len - read)
                    if (n == -1) return
                    read += n
                }
                _events.tryEmit(String(payload, StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w("FirefoxRdpBridge", "read loop ended", e)
        }
    }

    @Suppress("unused")
    private fun writeFrame(output: OutputStream, json: String) {
        val payload = json.toByteArray(StandardCharsets.UTF_8)
        output.write("${payload.size}:".toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.flush()
    }
}
