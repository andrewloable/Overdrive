package net.bladewatch.app.ui.model

/**
 * Status of a daemon process.
 */
enum class DaemonStatus {
    RUNNING,
    STOPPED,
    ERROR,
    STARTING,
    STOPPING
}
