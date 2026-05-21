package com.overdrive.app.daemon.telegram;

import org.json.JSONObject;

/**
 * Context for command execution - provides messaging and utility methods.
 */
public interface CommandContext {
    
    /**
     * Send a text message to the chat.
     */
    boolean sendMessage(long chatId, String text);
    
    /**
     * Send a text message with inline keyboard buttons.
     * @param buttons Array of button rows, each row is array of [text, callbackData] pairs
     */
    boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons);
    
    /**
     * Send a video to the chat.
     */
    boolean sendVideo(long chatId, String videoPath, String caption);
    
    /**
     * Send an IPC command to a local service.
     */
    JSONObject sendIpcCommand(int port, JSONObject command);

    /**
     * Send an IPC command with a custom socket read timeout. Use when the
     * server-side handler may legitimately take longer than the default
     * (e.g. GitHub API calls in update flow). Default impl falls back to
     * the 5s-timeout method so existing impls don't break.
     */
    default JSONObject sendIpcCommand(int port, JSONObject command, int timeoutMs) {
        return sendIpcCommand(port, command);
    }
    
    /**
     * Execute a shell command and return output.
     *
     * Use ONLY for short-lived commands that terminate on their own (ps,
     * pgrep, ls, cat, pm path, echo, rm, mv, kill). Implementations drain
     * stdout to EOF and waitFor() the process — for a command that
     * backgrounds a long-lived grandchild (nohup ... &amp;), the grandchild
     * inherits the outer shell's stdio and the readLine() blocks forever.
     * For long-lived spawns, use {@link #spawnDetached} instead.
     */
    String execShell(String command);

    /**
     * Spawn a long-lived process and return immediately, without waiting
     * for it to finish or draining its stdio. Mirrors the canonical pattern
     * from {@code AppUpdater.runDetachedInstall} (AppUpdater.java:741-745):
     * wraps the command in a {@code (… &amp;)} subshell that reparents to init,
     * closes all stdio descriptors so no pipe is held open by the parent,
     * and skips {@code waitFor}/{@code readLine} entirely.
     *
     * Use for: app_process daemon launches, native binary daemons
     * (cloudflared, zrok, tailscaled, sing-box), watchdog scripts.
     *
     * The default impl exists so callers don't need a context-flavor check;
     * implementations override with the real detached pattern.
     *
     * @param command the command line to run; the implementation wraps it
     *                in {@code (… &lt;/dev/null &amp;)} so the caller should NOT
     *                add {@code nohup} / trailing {@code &amp;}
     * @return true on success, false if the spawn itself failed
     */
    default boolean spawnDetached(String command) {
        // Fallback for any context that hasn't been updated. Better to
        // execShell-with-trailing-& than to silently no-op, even though
        // the brief execShell window before the parent shell exits is
        // technically still racy.
        execShell("(" + command + " </dev/null >/dev/null 2>&1 &)");
        return true;
    }

    /**
     * Log a message.
     */
    void log(String message);
}
