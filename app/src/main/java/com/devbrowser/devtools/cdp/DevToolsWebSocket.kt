package com.devbrowser.devtools.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.experimental.xor

/**
 * Minimal RFC6455 WebSocket client over an Android LocalSocket. Implemented
 * by hand because we need to speak WS over the abstract UNIX socket exposed
 * by WebView devtools — OkHttp can only do TCP.
 *
 * Frame support: text frames + ping/pong + close. Binary is not used by CDP.
 * Fragmentation: incoming fragments are reassembled. Outgoing frames are
 * always single-frame text. All client→server frames are masked per spec.
 */
class DevToolsWebSocket(
    private val abstractSocketName: String,
    private val path: String,
) : AutoCloseable {

    private val socket = LocalSocket()
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    fun connect() {
        socket.connect(
            LocalSocketAddress(abstractSocketName, LocalSocketAddress.Namespace.ABSTRACT)
        )
        // Use unbuffered streams: we cannot afford the BufferedReader for HTTP
        // headers to over-read into the WebSocket frame stream.
        input = DataInputStream(socket.inputStream)
        output = DataOutputStream(socket.outputStream)

        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(nonce)
        val handshake = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        output.write(handshake.toByteArray(StandardCharsets.UTF_8))
        output.flush()

        val response = readHttpResponseHeaders(socket.inputStream)
        val statusLine = response.firstOrNull() ?: error("WebSocket handshake: empty response")
        if (!statusLine.contains("101")) error("WebSocket handshake failed: $statusLine")
    }

    private fun readHttpResponseHeaders(stream: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var prev = -1
        while (true) {
            val b = stream.read()
            if (b == -1) break
            if (prev == '\r'.code && b == '\n'.code) {
                current.setLength(current.length - 1) // strip \r
                if (current.isEmpty()) break
                lines.add(current.toString())
                current.setLength(0)
            } else {
                current.append(b.toChar())
            }
            prev = b
        }
        return lines
    }

    fun sendText(text: String) {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        writeFrame(opcode = 0x1, payload = payload, mask = true)
    }

    /** Blocks until a text message is received. Returns null on close. */
    fun receiveText(): String? {
        var assembled = ByteBuffer.allocate(0)
        var finalReceived = false
        var firstOpcode = -1
        while (!finalReceived) {
            val b0 = input.readUnsignedByte()
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0f
            val b1 = input.readUnsignedByte()
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7f).toLong()
            len = when (len) {
                126L -> input.readUnsignedShort().toLong()
                127L -> input.readLong()
                else -> len
            }
            val maskKey = if (masked) ByteArray(4).also { input.readFully(it) } else null
            val payload = ByteArray(len.toInt())
            input.readFully(payload)
            if (maskKey != null) {
                for (i in payload.indices) payload[i] = payload[i] xor maskKey[i % 4]
            }
            when (opcode) {
                0x1, 0x0 -> {
                    if (opcode == 0x1) firstOpcode = 0x1
                    val grown = ByteBuffer.allocate(assembled.position() + payload.size)
                    assembled.flip()
                    grown.put(assembled)
                    grown.put(payload)
                    assembled = grown
                    finalReceived = fin
                }
                0x8 -> {
                    writeFrame(0x8, ByteArray(0), mask = true)
                    return null
                }
                0x9 -> writeFrame(0xA, payload, mask = true)
                0xA -> { /* pong, ignore */ }
                else -> { /* unknown, ignore */ }
            }
        }
        if (firstOpcode != 0x1) return null
        return String(assembled.array(), 0, assembled.position(), StandardCharsets.UTF_8)
    }

    private fun writeFrame(opcode: Int, payload: ByteArray, mask: Boolean) {
        synchronized(output) {
            output.writeByte(0x80 or opcode)
            val len = payload.size
            val maskBit = if (mask) 0x80 else 0
            when {
                len < 126 -> output.writeByte(maskBit or len)
                len <= 0xffff -> {
                    output.writeByte(maskBit or 126)
                    output.writeShort(len)
                }
                else -> {
                    output.writeByte(maskBit or 127)
                    output.writeLong(len.toLong())
                }
            }
            if (mask) {
                val key = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
                output.write(key)
                val masked = ByteArray(len)
                for (i in 0 until len) masked[i] = payload[i] xor key[i % 4]
                output.write(masked)
            } else {
                output.write(payload)
            }
            output.flush()
        }
    }

    override fun close() {
        runCatching { writeFrame(0x8, ByteArray(0), mask = true) }
        runCatching { socket.close() }
    }
}
