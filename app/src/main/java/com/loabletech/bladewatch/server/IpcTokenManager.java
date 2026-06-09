package net.bladewatch.app.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.security.SecureRandom;
import net.bladewatch.app.logging.DaemonLogger;

/**
 * Manages the shared secret token used to authenticate local IPC connections
 * on TcpCommandServer (port 19876) and SurveillanceIpcServer (port 19877).
 *
 * Token lifecycle:
 *   - CameraDaemon calls generate() once at startup; writes token to disk.
 *   - Servers call isValid() to verify each incoming connection/request.
 *   - Clients call getToken() to read the token before connecting.
 *
 * IMPORTANT — file permissions: the daemon runs as the shell UID (2000) but the
 * Android app runs as a distinct app UID (e.g. 10085). The app is a *client*: it
 * must read this token to authenticate its IPC calls (e.g. fetching the device
 * secret for live-view JWTs). FileWriter creates files mode 600 (shell-only) by
 * default, which the app cannot read — that silently breaks every app→daemon IPC
 * call (symptom: "Camera unavailable" / "Auth unavailable" in the UI). We
 * therefore make the token world-readable after writing. This is acceptable:
 * the token only gates loopback (127.0.0.1) IPC, and the unified config already
 * lives world-readable in the same directory. Do NOT revert this to a bare
 * FileWriter without restoring an equivalent chmod.
 */
public final class IpcTokenManager {

    private static final String TAG = "IpcTokenManager";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final String TOKEN_FILE = "/data/local/tmp/bladewatch_ipc_token";

    private static volatile String cachedToken = null;

    private IpcTokenManager() {}

    /** Generate a new 32-char alphanumeric token and write it to disk. */
    public static String generate() {
        SecureRandom rng = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        String token = sb.toString();
        try (FileWriter fw = new FileWriter(TOKEN_FILE)) {
            fw.write(token);
        } catch (Exception e) {
            // Non-fatal: servers will reject all connections until a valid token is written.
            logger.error("Failed to write IPC token to disk: " + e.getMessage(), e);
        }
        // Make the token readable by the app UID (client). Without this the file
        // is mode 600 (shell-only) and the app cannot authenticate its IPC calls.
        // setReadable(true, false) grants read to group+other → rw-r--r-- (644).
        try {
            File f = new File(TOKEN_FILE);
            if (!f.setReadable(true, false)) {
                logger.warn("Failed to set world-readable permissions on " + TOKEN_FILE
                        + " — returning false. App UID will not be able to read the IPC token.");
            }
        } catch (Exception e) {
            logger.warn("Exception setting world-readable permissions on " + TOKEN_FILE
                    + ": " + e.getMessage() + " — app IPC auth will fail");
        }
        cachedToken = token;
        return token;
    }

    /**
     * Read the token from disk (cached after first successful read).
     * Returns null if the token file is missing or unreadable.
     */
    public static String getToken() {
        String cur = cachedToken;
        if (cur != null) return cur;
        try (BufferedReader r = new BufferedReader(new FileReader(TOKEN_FILE))) {
            String line = r.readLine();
            if (line != null && !line.isEmpty()) {
                cachedToken = line;
                return line;
            }
        } catch (Exception ignored) {
            logger.error("Failed to read IPC token from disk: " + ignored.getMessage(), ignored);
        }
        return null;
    }

    /** Returns true if the supplied token matches the current token. */
    public static boolean isValid(String token) {
        if (token == null || token.isEmpty()) return false;
        String expected = getToken();
        return expected != null && expected.equals(token);
    }
}
