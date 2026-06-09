package net.bladewatch.app.client;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class DaemonReadinessChecker {

    private static final String SENTINEL_PATH = "/data/local/tmp/camera_daemon.ready";
    private static final String TAG = "DaemonReadiness";

    private DaemonReadinessChecker() {}

    /**
     * Returns true if the daemon has finished startup: sentinel exists, is non-empty, and the
     * PID it contains belongs to a live process (/proc/<pid> exists). This prevents a
     * false-ready signal when the sentinel was left behind by a kill -9 (no shutdown hook ran
     * to delete it), which would otherwise cause callers to get ECONNREFUSED.
     */
    public static boolean isReady() {
        File f = new File(SENTINEL_PATH);
        if (!f.exists() || f.length() == 0) return false;
        int pid = readDaemonPid();
        if (pid <= 0) return false;
        return new File("/proc/" + pid).exists();
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

    /**
     * Reads the daemon PID from the sentinel file.
     *
     * @return the PID integer, or -1 on any error
     */
    public static int readDaemonPid() {
        try (BufferedReader br = new BufferedReader(new FileReader(SENTINEL_PATH))) {
            String line = br.readLine();
            if (line == null || line.trim().isEmpty()) return -1;
            return Integer.parseInt(line.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
