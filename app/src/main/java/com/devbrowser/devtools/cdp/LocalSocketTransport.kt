package com.devbrowser.devtools.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Dials the WebView devtools abstract UNIX socket and speaks raw HTTP for the
 * discovery endpoints (/json, /json/version) and HTTP/1.1 Upgrade for the
 * per-target WebSocket. The WebSocket framing itself is handled in
 * [DevToolsWebSocket] on top of the raw streams returned here.
 *
 * Why not OkHttp? OkHttp can't dial an abstract UNIX socket — only TCP. The
 * abstract namespace is Linux-specific and the Android LocalSocket class is
 * the only Java API that exposes it.
 */
class LocalSocketTransport(private val abstractSocketName: String) {

    fun openHttp(): LocalSocket {
        val socket = LocalSocket()
        socket.connect(
            LocalSocketAddress(abstractSocketName, LocalSocketAddress.Namespace.ABSTRACT)
        )
        return socket
    }

    fun httpGet(path: String): String {
        openHttp().use { socket ->
            val out: OutputStream = socket.outputStream
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(request.toByteArray(StandardCharsets.UTF_8))
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
            // Skip status + headers
            var line: String?
            do { line = reader.readLine() } while (line != null && line.isNotEmpty())
            return reader.readText()
        }
    }
}
