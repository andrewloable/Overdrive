package net.bladewatch.app.server;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.security.SecureRandom;

/**
 * Manages the shared secret token used to authenticate local IPC connections
 * on TcpCommandServer (port 19876) and SurveillanceIpcServer (port 19877).
 *
 * Token lifecycle:
 *   - CameraDaemon calls generate() once at startup; writes token to disk.
 *   - Servers call isValid() to verify each incoming connection/request.
 *   - Clients call getToken() to read the token before connecting.
 */
public final class IpcTokenManager {

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
        } catch (Exception ignored) {}
        return null;
    }

    /** Returns true if the supplied token matches the current token. */
    public static boolean isValid(String token) {
        if (token == null || token.isEmpty()) return false;
        String expected = getToken();
        return expected != null && expected.equals(token);
    }
}
