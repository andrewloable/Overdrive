package net.bladewatch.app.ui.fragment.liveview

// View mode integers must match the daemon's StreamingApiHandler view mode mapping.
enum class LiveViewDirection(val viewMode: Int, val label: String) {
    MOSAIC(0, "All"),
    FRONT(1, "Front"),
    RIGHT(2, "Right"),
    REAR(3, "Rear"),
    LEFT(4, "Left"),
}

sealed interface LiveStreamStatus {
    data object Idle : LiveStreamStatus
    data object Connecting : LiveStreamStatus
    data object Live : LiveStreamStatus
    data class Error(val reason: String) : LiveStreamStatus
    data class Unavailable(val reason: String) : LiveStreamStatus
}

data class LiveViewState(
    val direction: LiveViewDirection = LiveViewDirection.FRONT,
    val status: LiveStreamStatus = LiveStreamStatus.Idle,
)

internal data class WsFrame(val opcode: Int, val payload: ByteArray) {
    companion object {
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
    }
}
