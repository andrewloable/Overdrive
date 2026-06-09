package net.bladewatch.app.client;

import android.util.Log;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class DaemonReadinessChecker {

    private static final String SENTINEL_PATH = "/data/local/tmp/camera_daemon.ready";
    private static final String TAG = "DaemonReadiness";

    // The daemon's TCP command server. A successful connect is a UID-independent liveness
    // signal: it proves the daemon process is alive AND accepting commands.
    private static final String DAEMON_HOST = "127.0.0.1";
    private static final int DAEMON_PORT = 19876;
    private static final int PROBE_TIMEOUT_MS = 1000;

    private DaemonReadinessChecker() {}

    /**
     * Returns true if the daemon has finished startup: the ready sentinel exists and is non-empty,
     * AND the daemon's TCP command port accepts a connection.
     *
     * <p>The TCP probe is the authoritative liveness check. We deliberately do NOT stat
     * {@code /proc/<pid>}: on the head unit {@code /proc} is mounted {@code hidepid=2,gid=3009},
     * so the app UID cannot see the daemon's {@code /proc} entry (the daemon runs as the shell UID
     * and the app is not in gid 3009). A {@code /proc} check therefore always fails from the app
     * process, leaving the camera permanently "connecting". A TCP connect works regardless of UID
     * and also covers the stale-sentinel case: a daemon killed with {@code kill -9} leaves the
     * sentinel behind but is no longer listening, so the connect is refused.
     */
    public static boolean isReady() {
        File f = new File(SENTINEL_PATH);
        if (!f.exists() || f.length() == 0) return false;
        return daemonPortAccepting();
    }

    /**
     * Attempts a short-lived TCP connect to the daemon command port.
     *
     * @return true if the connection is accepted, false on refusal/timeout/any error
     */
    private static boolean daemonPortAccepting() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(DAEMON_HOST, DAEMON_PORT), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Blocks until isReady() returns true or timeoutMs elapses.
     * Polls every 500ms; logs progress at INFO level every 5 seconds.
     *
     * @return true if daemon became ready within the timeout, false otherwise
     */
    public static boolean waitUntilReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastLogMs = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            if (isReady()) {
                Log.i(TAG, "Daemon is ready");
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastLogMs >= 5000) {
                long remainingMs = deadline - now;
                Log.i(TAG, "Waiting for daemon ready sentinel... " + (remainingMs / 1000) + "s remaining");
                lastLogMs = now;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.i(TAG, "Timed out waiting for daemon ready sentinel");
        return false;
    }
}
