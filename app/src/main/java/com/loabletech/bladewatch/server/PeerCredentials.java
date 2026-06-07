package net.bladewatch.app.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Socket;

/**
 * Resolves and authorizes the peer UID of an accepted loopback IPC connection.
 *
 * <p>The loopback IPC servers — {@link TcpCommandServer} (19876) and
 * {@link SurveillanceIpcServer} (19877) — authenticate callers with a shared
 * bearer token. That token lives in {@code /data/local/tmp/bladewatch_ipc_token}
 * and is intentionally world-readable (mode 644) so the app UID can bootstrap
 * its IPC calls (see {@link IpcTokenManager}). World-readable means the token
 * alone is NOT a trust boundary: any local process that can read it could drive
 * privileged commands (e.g. {@code shell}, {@code secret_*}, {@code UPDATE_GPS}).
 *
 * <p>This class adds a second, defence-in-depth gate: the daemon resolves the
 * connecting socket's owning UID and rejects any peer that is not an allow-listed
 * trusted identity (root, system, shell/daemon, or the BladeWatch app itself).
 *
 * <p><b>Transport note:</b> a Java TCP {@link Socket} cannot read
 * {@code SO_PEERCRED} directly. Because both endpoints are on loopback, both
 * appear in {@code /proc/net/tcp} / {@code /proc/net/tcp6}. We locate the
 * client's socket row by its {@code (localPort, remotePort)} pair — unique for an
 * established loopback connection — and read the owning UID from that row. The
 * daemon runs as shell (UID 2000), which retains read access to these procfs
 * entries on Android 10+.
 */
public final class PeerCredentials {

    private PeerCredentials() {}

    // Resolved lazily from the app's package context and cached. -1 = unresolved.
    private static volatile int cachedAppUid = -1;

    private static final int ROOT_UID = 0;
    private static final int SYSTEM_UID = 1000;
    private static final int SHELL_UID = 2000;

    /**
     * Resolve the owning UID of the remote end of an accepted loopback socket.
     *
     * @return the peer UID, or -1 if it could not be determined.
     */
    public static int resolvePeerUid(Socket client) {
        if (client == null) return -1;
        int clientPort = client.getPort();      // remote (client ephemeral) port
        int serverPort = client.getLocalPort(); // our listening port
        if (clientPort <= 0 || serverPort <= 0) return -1;
        // The procfs row for an established connection is normally present the
        // instant accept() returns, but retry a few times to absorb any race.
        for (int attempt = 0; attempt < 5; attempt++) {
            int uid = lookupUid(clientPort, serverPort);
            if (uid >= 0) return uid;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return -1;
    }

    /**
     * Returns true if the given UID is an allow-listed trusted identity:
     * root (0), system (1000), shell/daemon (2000), or the BladeWatch app UID.
     * An unresolved UID (-1) is never trusted.
     */
    public static boolean isTrusted(int uid) {
        if (uid == ROOT_UID || uid == SYSTEM_UID || uid == SHELL_UID) return true;
        if (uid < 0) return false;
        int appUid = resolveAppUid();
        // Compare on the per-user base app-id so a non-zero Android user still
        // matches (uid = userId * 100000 + appId).
        return appUid > 0 && (uid == appUid || (uid % 100000) == (appUid % 100000));
    }

    /** Convenience: resolve the peer UID of {@code client} and check the allow-list. */
    public static boolean isTrustedPeer(Socket client) {
        return isTrusted(resolvePeerUid(client));
    }

    // ==================== internals ====================

    private static int resolveAppUid() {
        int cached = cachedAppUid;
        if (cached > 0) return cached;
        try {
            android.content.Context ctx = net.bladewatch.app.daemon.CameraDaemon.getAppContext();
            if (ctx != null) {
                android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
                if (ai != null && ai.uid > 0) {
                    cachedAppUid = ai.uid;
                    return ai.uid;
                }
                int u = ctx.getPackageManager().getPackageUid(ctx.getPackageName(), 0);
                if (u > 0) {
                    cachedAppUid = u;
                    return u;
                }
            }
        } catch (Exception ignored) {
            // App context not ready yet — caller treats unresolved app UID as
            // "no app match"; shell/system/root peers still pass.
        }
        return -1;
    }

    /** Scan both procfs tables for the client socket row and return its UID. */
    private static int lookupUid(int clientPort, int serverPort) {
        int uid = scan("/proc/net/tcp", clientPort, serverPort);
        if (uid < 0) uid = scan("/proc/net/tcp6", clientPort, serverPort);
        return uid;
    }

    /**
     * Parse a /proc/net/tcp{,6} table. Each data row is:
     *   sl  local_address rem_address st tx:rx tr:tm retr uid timeout inode ...
     * We match the row whose local port == the client's ephemeral port AND whose
     * remote port == our server port, then return field[7] (uid).
     */
    private static int scan(String path, int clientPort, int serverPort) {
        File f = new File(path);
        if (!f.exists()) return -1;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine(); // skip header
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 8) continue;
                int localPort = parseHexPort(t[1]);
                int remPort = parseHexPort(t[2]);
                if (localPort == clientPort && remPort == serverPort) {
                    try {
                        return Integer.parseInt(t[7]);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        } catch (Exception ignored) {
            // procfs unreadable / unexpected format — treat as unresolved.
        }
        return -1;
    }

    /** Extract the hex port from an "ADDRESS:PORT" procfs field. */
    private static int parseHexPort(String addrPort) {
        int colon = addrPort.lastIndexOf(':');
        if (colon < 0 || colon + 1 >= addrPort.length()) return -1;
        try {
            return Integer.parseInt(addrPort.substring(colon + 1), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
