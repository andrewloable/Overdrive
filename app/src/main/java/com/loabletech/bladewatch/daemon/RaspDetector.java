package net.bladewatch.app.daemon;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Report-only Frida/hook detection for uy93.9.
 *
 * SCOPE: Frida agent library presence in /proc/self/maps ONLY.
 * NOT CHECKED: root, ADB, ptrace, debugger presence — all fire on normal
 * BYD DiLink v3 operation (ADB-connected, dev-unlocked, shell UID daemons).
 *
 * ENFORCEMENT: NEVER. Detection triggers a log entry only. The daemon
 * continues to run regardless of what is found.
 *
 * KILL-SWITCH: create /data/local/tmp/bladewatch_rasp_disabled to skip checks.
 *   adb shell touch /data/local/tmp/bladewatch_rasp_disabled
 *
 * REPORT: appended to /data/local/tmp/bladewatch_security.log
 * This log survives R8/ProGuard stripping (writes directly via FileWriter,
 * not via DaemonLogger which is stripped in release builds).
 */
public class RaspDetector {

    private static final String SECURITY_LOG = "/data/local/tmp/bladewatch_security.log";
    private static final String KILL_SWITCH  = "/data/local/tmp/bladewatch_rasp_disabled";

    // Frida patterns: agent lib names and gadget names as they appear in /proc/self/maps
    private static final String[] FRIDA_MARKERS = {
        "frida-agent",
        "frida-gadget",
        "re.frida.agent",
    };

    /** Called once at CameraDaemon startup. Never throws. */
    public static void checkAndReport() {
        try {
            if (new java.io.File(KILL_SWITCH).exists()) return;
            scanProcMaps();
        } catch (Exception ignored) {
            // Never propagate — RASP must not affect daemon availability
        }
    }

    private static void scanProcMaps() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                for (String marker : FRIDA_MARKERS) {
                    if (line.contains(marker)) {
                        reportEvent("FRIDA_DETECTED", line.trim());
                        return; // one report per scan is enough
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void reportEvent(String type, String detail) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String entry = ts + " [RASP:" + type + "] " + detail + "\n";
            // ponytail: append-only, no rotation; this file is low-volume (one entry per detect)
            try (FileWriter fw = new FileWriter(SECURITY_LOG, true)) {
                fw.write(entry);
            }
        } catch (Exception ignored) {}
    }
}
