package net.bladewatch.app.ui.fragment.liveview

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import net.bladewatch.app.auth.AuthManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

internal class LiveStreamClient {

    @Volatile private var previewSurface: Surface? = null
    @Volatile private var currentDirection = LiveViewDirection.FRONT
    @Volatile private var onStateChange: ((LiveStreamStatus) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile private var activeSocket: Socket? = null
    private var activeCodec: MediaCodec? = null

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        if (surface == null) releaseCodec()
    }

    fun connect(direction: LiveViewDirection, onStateChange: (LiveStreamStatus) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        this.currentDirection = direction
        this.onStateChange = onStateChange
        worker = Thread(::runStream, "LiveStreamDecoder").apply {
            isDaemon = true
            start()
        }
    }

    fun selectDirection(direction: LiveViewDirection) {
        currentDirection = direction
        Thread({
            val jwt = getJwt() ?: return@Thread
            httpPost("/api/stream/view/${direction.viewMode}", jwt)
        }, "LiveStreamViewSelect").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { activeSocket?.close() }
        val w = worker
        worker = null
        Thread({ w?.join(2_000L); releaseCodec() }, "LiveStop").apply { isDaemon = true; start() }
    }

    private fun releaseCodec() {
        val c = activeCodec
        activeCodec = null
        runCatching { c?.stop() }
        runCatching { c?.release() }
    }

    private fun runStream() {
        try {
            // Wait for TextureView surface to be ready before proceeding.
            while (running.get() && previewSurface == null) {
                Thread.sleep(100L)
            }
            val surface = previewSurface ?: return

            publish(LiveStreamStatus.Connecting)

            val jwt = getJwt() ?: run {
                publish(LiveStreamStatus.Unavailable("Auth unavailable"))
                return
            }

            httpPost("/api/stream/enable", jwt)
            httpPost("/api/stream/view/${currentDirection.viewMode}", jwt)

            val (width, height) = queryStreamDimensions(jwt) ?: (640 to 480)

            val wsSocket = Socket()
            activeSocket = wsSocket
            wsSocket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8080), CONNECT_TIMEOUT_MS)
            wsSocket.soTimeout = READ_TIMEOUT_MS

            val out = BufferedOutputStream(wsSocket.getOutputStream())
            val inp = BufferedInputStream(wsSocket.getInputStream())

            val wsKey = generateWebSocketKey()
            val tokenEncoded = java.net.URLEncoder.encode(jwt, "UTF-8")
            out.write(buildUpgradeRequest("/ws?token=$tokenEncoded", wsKey).toByteArray(Charsets.US_ASCII))
            out.flush()

            if (!readUpgradeResponse(inp)) {
                publish(LiveStreamStatus.Error("WebSocket handshake failed"))
                return
            }

            val decoder = MediaCodec.createDecoderByType("video/avc")
            activeCodec = decoder
            decoder.configure(MediaFormat.createVideoFormat("video/avc", width, height), surface, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var codecConfigSent = false
            var frameCounter = 0L

            while (running.get()) {
                val frame = runCatching { readWsFrame(inp) }.getOrNull() ?: break

                when (frame.opcode) {
                    WsFrame.OPCODE_CLOSE -> break
                    WsFrame.OPCODE_PING -> continue
                    WsFrame.OPCODE_BINARY -> {
                        // The server always sends SPS+PPS as the very first binary frame on
                        // each new WebSocket connection, so the first frame is always codec config.
                        val flags = if (!codecConfigSent) {
                            codecConfigSent = true
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        } else {
                            0
                        }

                        val inputIdx = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                        if (inputIdx >= 0) {
                            decoder.getInputBuffer(inputIdx)?.apply {
                                clear()
                                put(frame.payload)
                            }
                            decoder.queueInputBuffer(inputIdx, 0, frame.payload.size, frameCounter++ * PTS_STEP_US, flags)
                        }

                        drainDecoder(decoder, bufferInfo)

                        if (flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                            publish(LiveStreamStatus.Live)
                        }
                    }
                }
            }
        } catch (e: java.net.ConnectException) {
            if (running.get()) publish(LiveStreamStatus.Unavailable("Daemon not running"))
        } catch (e: java.net.SocketTimeoutException) {
            if (running.get()) publish(LiveStreamStatus.Error("Stream timed out"))
        } catch (e: Throwable) {
            Log.w(TAG, "stream_error: ${e.message}")
            if (running.get()) publish(LiveStreamStatus.Error(e.message ?: e.javaClass.simpleName))
        } finally {
            running.set(false)
            runCatching { activeSocket?.close() }
        }
    }

    private fun drainDecoder(decoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val idx = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            when {
                idx >= 0 -> {
                    val render = bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    decoder.releaseOutputBuffer(idx, render)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> return
            }
        }
    }

    private fun publish(status: LiveStreamStatus) {
        onStateChange?.invoke(status)
    }

    private fun getJwt(): String? = runCatching {
        if (AuthManager.getState() == null) AuthManager.initialize()
        AuthManager.generateJwt()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun httpPost(path: String, jwt: String): Int = runCatching {
        val conn = URL("http://127.0.0.1:8080$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.setRequestProperty("Content-Length", "0")
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        conn.responseCode
    }.getOrDefault(-1)

    private fun queryStreamDimensions(jwt: String): Pair<Int, Int>? = runCatching {
        val conn = URL("http://127.0.0.1:8080/api/stream/quality").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        if (conn.responseCode != 200) return null
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val currentId = json.optString("current")
        val options = json.optJSONArray("options") ?: return null
        for (i in 0 until options.length()) {
            val opt = options.getJSONObject(i)
            if (opt.optString("id") == currentId) {
                return opt.optInt("width", 640) to opt.optInt("height", 480)
            }
        }
        null
    }.getOrNull()

    private fun generateWebSocketKey(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun buildUpgradeRequest(path: String, key: String): String =
        "GET $path HTTP/1.1\r\n" +
            "Host: 127.0.0.1:8080\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"

    private fun readUpgradeResponse(inp: BufferedInputStream): Boolean {
        val sb = StringBuilder()
        while (sb.length <= 8192) {
            val c = inp.read().takeIf { it >= 0 }?.toChar() ?: return false
            sb.append(c)
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.startsWith("HTTP/1.1 101")
    }

    private fun readWsFrame(inp: BufferedInputStream): WsFrame? {
        val b0 = inp.read().takeIf { it >= 0 } ?: return null
        val b1 = inp.read().takeIf { it >= 0 } ?: return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var payloadLen = (b1 and 0x7F).toLong()

        when {
            payloadLen == 126L -> payloadLen = ((inp.read() and 0xFF).toLong() shl 8) or (inp.read() and 0xFF).toLong()
            payloadLen == 127L -> { payloadLen = 0L; repeat(8) { payloadLen = (payloadLen shl 8) or (inp.read() and 0xFF).toLong() } }
        }

        val mask = if (masked) ByteArray(4) { inp.read().toByte() } else null
        if (payloadLen > 4 * 1024 * 1024L) throw java.io.IOException("WS frame too large: $payloadLen bytes")
        val payload = ByteArray(payloadLen.toInt())
        var offset = 0
        while (offset < payload.size) {
            val n = inp.read(payload, offset, payload.size - offset)
            if (n < 0) return null
            offset += n
        }
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        return WsFrame(opcode, payload)
    }

    companion object {
        private const val TAG = "LiveStreamClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val PTS_STEP_US = 66_667L  // ~15fps presentation cadence
    }
}
