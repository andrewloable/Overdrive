package net.bladewatch.app.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import net.bladewatch.app.logging.DaemonLogger;

/**
 * Single source of truth for the shared secret token that authenticates local
 * IPC connections on TcpCommandServer (port 19876) and SurveillanceIpcServer
 * (port 19877). All token creation and reading flows through this class — no
 * other code touches {@link #TOKEN_FILE} directly.
 *
 * Token lifecycle:
 *   - generate() — daemon: ensure a token exists on disk. IDEMPOTENT — reuses an
 *                  existing well-formed token instead of rotating it. Called once
 *                  per daemon startup (CameraDaemon).
 *   - getToken()     — client/server: read the current token (process-cached).
 *   - refreshToken() — client: drop the cache and re-read from disk. Called by
 *                      CameraDaemonClient before every connect so it never sends
 *                      a token cached before the daemon (re)wrote one on its last
 *                      startup, which the daemon would reject as Unauthorized.
 *   - isValid()      — server: verify an incoming token.
 *
 * Why generate() is idempotent: it runs on every daemon startup. Minting a fresh
 * random token each boot meant that any process still holding the previous token
 * — the Android app, or a stale daemon that survived an app reinstall (shell-
 * launched daemons are detached app_process instances, NOT bound to the package
 * manager) — would keep presenting a token the new daemon rejected, producing
 * "Unauthorized" on every app→daemon secret fetch. Symptom: "Camera unavailable"
 * and the dashboard trip stats stuck on "Loading…". The token lives in
 * /data/local/tmp (NOT the app data dir), so it survives uninstall; reusing it
 * keeps every process in agreement across daemon restarts and reinstalls. A
 * stable loopback bootstrap token is acceptable — it only gates 127.0.0.1 IPC
 * and is intentionally world-readable (see below).
 *
 * IMPORTANT — file permissions: the daemon runs as the shell UID (2000) but the
 * Android app runs as a distinct app UID (e.g. 10085). The app is a *client*: it
 * must read this token to authenticate its IPC calls (e.g. fetching the device
 * secret for live-view JWTs). FileWriter creates files mode 600 (shell-only) by
 * default, which the app cannot read — that silently breaks every app→daemon IPC
 * call. We therefore make the token world-readable (644) after writing. This is
 * acceptable: the token only gates loopback (127.0.0.1) IPC, and the unified
 * config already lives world-readable in the same directory. Do NOT revert this
 * to a bare FileWriter without restoring an equivalent chmod.
 */
public final class IpcTokenManager {

    private static final String TAG = "IpcTokenManager";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final String TOKEN_FILE = "/data/local/tmp/bladewatch_ipc_token";

    private static final int TOKEN_LENGTH = 32;
    private static final String TOKEN_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    // ponytail: package-private for test injection; null forces a disk read
    static volatile String cachedToken = null;

    private IpcTokenManager() {}

    /**
     * Ensure a token exists on disk and return it. IDEMPOTENT: an existing,
     * well-formed token is reused (loaded + cached, permissions repaired); a
     * fresh one is minted only when the file is missing, empty, or malformed.
     */
    public static synchronized String generate() {
        String existing = readFromDisk();
        if (existing != null) {
            // Reuse — but repair perms in case a prior writer left the file 600,
            // which would silently break the app UID's ability to read it.
            ensureWorldReadable();
            cachedToken = existing;
            return existing;
        }
        return mintAndPersist();
    }

    /** Mint a new random token, persist it world-readable, and cache it. */
    private static String mintAndPersist() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_CHARS.charAt(rng.nextInt(TOKEN_CHARS.length())));
        }
        String token = sb.toString();
        try (FileWriter fw = new FileWriter(TOKEN_FILE)) {
            fw.write(token);
        } catch (Exception e) {
            // Non-fatal: servers will reject all connections until a valid token is written.
            logger.error("Failed to write IPC token to disk: " + e.getMessage(), e);
        }
        ensureWorldReadable();
        // Read back from disk: if a racing daemon's write won, every process still
        // converges on the same on-disk value instead of caching a token only this
        // process knows about.
        String onDisk = readFromDisk();
        String effective = onDisk != null ? onDisk : token;
        cachedToken = effective;
        return effective;
    }

    /** chmod the token file to 644 so the app UID (client) can read it. */
    private static void ensureWorldReadable() {
        try {
            File f = new File(TOKEN_FILE);
            // setReadable(true, false) grants read to group+other → rw-r--r-- (644).
            if (!f.setReadable(true, false)) {
                logger.warn("Failed to set world-readable permissions on " + TOKEN_FILE
                        + " — app UID will not be able to read the IPC token.");
            }
        } catch (Exception e) {
            logger.warn("Exception setting world-readable permissions on " + TOKEN_FILE
                    + ": " + e.getMessage() + " — app IPC auth will fail");
        }
    }

    /**
     * Read and validate the token directly from disk. Returns null if the file
     * is missing, empty, unreadable, or malformed. Does NOT touch the cache —
     * this is the single low-level disk read every public accessor builds on.
     */
    private static String readFromDisk() {
        try (BufferedReader r = new BufferedReader(new FileReader(TOKEN_FILE))) {
            String line = r.readLine();
            if (line != null) {
                String trimmed = line.trim();
                if (isWellFormed(trimmed)) return trimmed;
            }
        } catch (Exception e) {
            // A missing file is the normal first-run case; only surface real read errors.
            File f = new File(TOKEN_FILE);
            if (f.exists()) {
                logger.error("Failed to read IPC token from disk: " + e.getMessage(), e);
            }
        }
        return null;
    }

    /** A valid token is exactly {@link #TOKEN_LENGTH} chars drawn from {@link #TOKEN_CHARS}. */
    private static boolean isWellFormed(String token) {
        if (token == null || token.length() != TOKEN_LENGTH) return false;
        for (int i = 0; i < token.length(); i++) {
            if (TOKEN_CHARS.indexOf(token.charAt(i)) < 0) return false;
        }
        return true;
    }

    /**
     * Read the token (process-cached after first successful read).
     * Returns null if the token file is missing or unreadable.
     */
    public static String getToken() {
        String cur = cachedToken;
        if (cur != null) return cur;
        String fromDisk = readFromDisk();
        if (fromDisk != null) cachedToken = fromDisk;
        return fromDisk;
    }

    /**
     * Drop the process cache and re-read the token from disk. Clients call this
     * before each new IPC connection so they pick up a token the daemon wrote
     * after this process last cached one — the self-healing path for a token
     * that was rotated across a daemon restart / app reinstall. Returns the
     * fresh token, or null if the file is missing/unreadable.
     */
    public static String refreshToken() {
        cachedToken = null;
        return getToken();
    }

    /** Returns true if the supplied token matches the current token. Constant-time compare (uy93.3). */
    public static boolean isValid(String token) {
        if (token == null || token.isEmpty()) return false;
        String expected = getToken();
        if (expected == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
