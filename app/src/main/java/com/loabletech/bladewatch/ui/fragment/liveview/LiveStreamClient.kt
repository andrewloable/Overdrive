package net.bladewatch.app.ui.fragment.liveview

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.connectrpc.ResponseMessage
import kotlinx.coroutines.runBlocking
import net.bladewatch.app.auth.AuthManager
import net.bladewatch.app.client.ConnectClientProvider
import net.bladewatch.app.grpc.v1.EnableStreamRequest
import net.bladewatch.app.grpc.v1.GetStreamQualityRequest
import net.bladewatch.app.grpc.v1.SetViewModeRequest
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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
            val resp = runBlocking {
                ConnectClientProvider.streamService().setViewMode(
                    SetViewModeRequest.newBuilder().setViewMode(direction.viewMode).build(),
                    emptyMap()
                )
            }
            if (resp is ResponseMessage.Failure) {
                Log.w(TAG, "selectDirection failed: ${resp.cause.message}")
            }
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

    private fun enableStream() {
        val resp = runBlocking {
            ConnectClientProvider.streamService().enable(
                EnableStreamRequest.newBuilder().build(), emptyMap()
            )
        }
        if (resp is ResponseMessage.Failure) {
            throw java.io.IOException("stream enable failed: ${resp.cause.message}")
        }
    }

    private fun setViewMode(direction: LiveViewDirection) {
        val resp = runBlocking {
            ConnectClientProvider.streamService().setViewMode(
                SetViewModeRequest.newBuilder().setViewMode(direction.viewMode).build(),
                emptyMap()
            )
        }
        if (resp is ResponseMessage.Failure) {
            throw java.io.IOException("set view mode failed: ${resp.cause.message}")
        }
    }

    private fun queryStreamDimensions(): Pair<Int, Int>? = runBlocking {
        val resp = ConnectClientProvider.streamService().getQuality(
            GetStreamQualityRequest.newBuilder().build(), emptyMap()
        )
        if (resp !is ResponseMessage.Success) return@runBlocking null
        val currentId = resp.message.current
        resp.message.optionsList.firstOrNull { it.id == currentId }?.let { opt ->
            opt.width to opt.height
        }
    }

    private fun runStream() {
        try {
            // Wait for TextureView surface to be ready before proceeding.
            while (running.get() && previewSurface == null) {
                Thread.sleep(100L)
            }
            val surface = previewSurface ?: return

            publish(LiveStreamStatus.Connecting)

            // Connect with auto-retry. After a reinstall or daemon restart the
            // daemon can take tens of seconds to come up, and the app may briefly
            // hold a stale auth secret. Retry silently — refreshing the device
            // secret from the daemon each attempt (covers the RETRY button too) —
            // so the live view self-heals without a manual RETRY or app restart.
            // Only surface "unavailable" once the retry budget is exhausted.
            var connectedInp: BufferedInputStream? = null
            var width = 640
            var height = 480
            var attempt = 0
            while (running.get() && connectedInp == null) {
                attempt++
                try {
                    AuthManager.refresh()

                    enableStream()
                    setViewMode(currentDirection)

                    val dims = queryStreamDimensions() ?: (640 to 480)
                    width = dims.first
                    height = dims.second

                    val jwt = runCatching {
                        if (AuthManager.getState() == null) AuthManager.initialize()
                        AuthManager.generateJwt()?.takeIf { it.isNotBlank() }
                    }.getOrNull() ?: throw java.io.IOException("auth not ready")

                    val wsSocket = Socket()
                    activeSocket = wsSocket
                    wsSocket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8080), CONNECT_TIMEOUT_MS)
                    wsSocket.soTimeout = READ_TIMEOUT_MS

                    val out = BufferedOutputStream(wsSocket.getOutputStream())
                    val candidate = BufferedInputStream(wsSocket.getInputStream())

                    val wsKey = generateWebSocketKey()
                    val tokenEncoded = java.net.URLEncoder.encode(jwt, "UTF-8")
                    out.write(buildUpgradeRequest("/ws?token=$tokenEncoded", wsKey).toByteArray(Charsets.US_ASCII))
                    out.flush()

                    if (!readUpgradeResponse(candidate)) throw java.io.IOException("handshake failed")
                    connectedInp = candidate  // success
                } catch (e: Throwable) {
                    runCatching { activeSocket?.close() }
                    activeSocket = null
                    if (!running.get()) return
                    if (attempt >= MAX_CONNECT_ATTEMPTS) {
                        publish(LiveStreamStatus.Unavailable("Camera starting — tap retry"))
                        return
                    }
                    publish(LiveStreamStatus.Connecting)
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
            val inp = connectedInp ?: return

            val decoder = MediaCodec.createDecoderByType("video/avc")
            activeCodec = decoder
            decoder.configure(MediaFormat.createVideoFormat("video/avc", width, height), surface, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var codecConfigSent = false
            var frameCounter = 0L

            // Feeds one COMPLETE H.264 access unit to the decoder. The server
            // sends SPS+PPS as the very first message on each new connection, so
            // the first complete unit is always codec config.
            fun feedToDecoder(payload: ByteArray) {
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
                        put(payload)
                    }
                    decoder.queueInputBuffer(inputIdx, 0, payload.size, frameCounter++ * PTS_STEP_US, flags)
                }

                drainDecoder(decoder, bufferInfo)

                if (flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    publish(LiveStreamStatus.Live)
                }
            }

            // Reassembles fragmented WebSocket messages. The server splits any
            // H.264 access unit larger than 32KB into a binary frame (opcode 0x2,
            // FIN=0) followed by continuation frames (opcode 0x0). Feeding only
            // the first fragment to the decoder truncates every large unit
            // (keyframes, busy P-frames) and produces macroblock corruption, so
            // we accumulate fragments until the FIN bit is set.
            var assembly: java.io.ByteArrayOutputStream? = null

            while (running.get()) {
                val frame = runCatching { readWsFrame(inp) }.getOrNull() ?: break

                when (frame.opcode) {
                    WsFrame.OPCODE_CLOSE -> break
                    WsFrame.OPCODE_PING -> continue
                    WsFrame.OPCODE_BINARY -> {
                        if (frame.fin) {
                            feedToDecoder(frame.payload)
                        } else {
                            // First fragment of a fragmented message.
                            assembly = java.io.ByteArrayOutputStream().apply { write(frame.payload) }
                        }
                    }
                    WsFrame.OPCODE_CONTINUATION -> {
                        val acc = assembly ?: continue  // stray continuation — ignore
                        acc.write(frame.payload)
                        if (frame.fin) {
                            feedToDecoder(acc.toByteArray())
                            assembly = null
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
        val fin = (b0 and 0x80) != 0
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

        return WsFrame(opcode, payload, fin)
    }

    companion object {
        private const val TAG = "LiveStreamClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val PTS_STEP_US = 66_667L  // ~15fps presentation cadence
        // Auto-retry budget for the initial connection, covering daemon
        // cold-start after a reinstall/restart (~20 × 2s ≈ 40s of trying, plus
        // per-attempt socket/HTTP timeouts) before showing the RETRY button.
        private const val MAX_CONNECT_ATTEMPTS = 20
        private const val RETRY_DELAY_MS = 2_000L
    }
}
