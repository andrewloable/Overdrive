package net.bladewatch.app.server;

import net.bladewatch.app.daemon.CameraDaemon;
import net.bladewatch.app.storage.StorageManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Format Storage API Handler — lets the user reformat a removable external drive.
 *
 * Only public: volumes (FAT/exFAT removable media such as SD cards and USB sticks)
 * can be formatted. Internal emulated storage is explicitly rejected.
 * Formatting is blocked while recording is active.
 *
 * Endpoints:
 *   GET  /api/storage/format         — list mountable removable public volumes
 *   POST /api/storage/format         — format a volume by ID
 *                                      Body: { "volumeId": "public:8,97" }
 */
public class FormatStorageApiHandler {

    private static final int MOUNT_POLL_COUNT = 20;
    private static final long MOUNT_POLL_DELAY_MS = 500L;

    public static boolean handle(String path, String method, String body, OutputStream out) throws Exception {
        if (path.equals("/api/storage/format") && "GET".equals(method)) {
            listVolumes(out);
            return true;
        }
        if (path.equals("/api/storage/format") && "POST".equals(method)) {
            formatVolume(body, out);
            return true;
        }
        return false;
    }

    // ─── GET /api/storage/format ──────────────────────────────────────────────

    private static void listVolumes(OutputStream out) throws Exception {
        List<SmPublicVolume> volumes = enumPublicVolumes();
        JSONArray arr = new JSONArray();
        for (SmPublicVolume v : volumes) {
            JSONObject obj = new JSONObject();
            obj.put("volumeId", v.id);
            obj.put("uuid", v.uuid != null ? v.uuid : JSONObject.NULL);
            obj.put("mounted", v.mounted);
            obj.put("mountPath", v.uuid != null ? "/storage/" + v.uuid : JSONObject.NULL);
            arr.put(obj);
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("volumes", arr);
        sendJson(out, 200, response);
    }

    // ─── POST /api/storage/format ─────────────────────────────────────────────

    private static void formatVolume(String body, OutputStream out) throws Exception {
        if (body == null || body.trim().isEmpty()) {
            error(out, 400, "Missing request body");
            return;
        }

        JSONObject req = new JSONObject(body);
        String volumeId = req.optString("volumeId", "");

        if (!isValidVolumeId(volumeId)) {
            error(out, 400, "Invalid volumeId — must be a removable public volume (e.g. public:8,97)");
            return;
        }

        // Block if recording or surveillance is active
        StorageManager storage = StorageManager.getInstance();
        if (storage != null && storage.isRecordingActive()) {
            error(out, 409, "Cannot format drive while recording is active — stop recording first");
            return;
        }

        CameraDaemon.log("FormatStorage: starting format of " + volumeId);

        // Unmount (safe even if already unmounted)
        String[] unmountResult = shell("sm unmount " + shellQuote(volumeId));
        CameraDaemon.log("FormatStorage: unmount exit=" + unmountResult[0]);

        // Format — destructive: erases all data on the volume
        String[] formatResult = shell("sm format " + shellQuote(volumeId));
        CameraDaemon.log("FormatStorage: format exit=" + formatResult[0] + " out=" + formatResult[1].trim());

        if (!"0".equals(formatResult[0])) {
            String msg = formatResult[1].trim();
            error(out, 500, "Format failed: " + (msg.isEmpty() ? "sm format exited " + formatResult[0] : msg));
            return;
        }

        // Mount after format
        shell("sm mount " + shellQuote(volumeId));

        // Poll for remount (up to 10 seconds)
        String mountPath = null;
        for (int i = 0; i < MOUNT_POLL_COUNT; i++) {
            Thread.sleep(MOUNT_POLL_DELAY_MS);
            for (SmPublicVolume v : enumPublicVolumes()) {
                if (v.id.equals(volumeId) && v.mounted && v.uuid != null) {
                    mountPath = "/storage/" + v.uuid;
                    break;
                }
            }
            if (mountPath != null) break;
        }

        // Refresh StorageManager so it picks up the newly formatted drive
        if (storage != null) {
            storage.discoverSdCard();
        }

        JSONObject response = new JSONObject();
        if (mountPath != null) {
            CameraDaemon.log("FormatStorage: success, mounted at " + mountPath);
            response.put("success", true);
            response.put("message", "Drive formatted and remounted at " + mountPath);
            response.put("mountPath", mountPath);
        } else {
            // Format succeeded but drive didn't auto-remount — partial success
            CameraDaemon.log("FormatStorage: format OK but drive did not remount");
            response.put("success", false);
            response.put("message", "Drive formatted but did not remount — safely re-insert the drive");
            response.put("mountPath", JSONObject.NULL);
        }
        sendJson(out, 200, response);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Run sm list-volumes all and return all public: entries. */
    private static List<SmPublicVolume> enumPublicVolumes() {
        List<SmPublicVolume> result = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("public:")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                String id = parts[0];
                String state = parts[1];
                String uuid = (parts.length >= 3 && !"null".equals(parts[2])) ? parts[2] : null;
                result.add(new SmPublicVolume(id, state, uuid));
            }
            r.close();
            p.waitFor();
        } catch (Exception e) {
            CameraDaemon.log("FormatStorage: enumPublicVolumes error: " + e.getMessage());
        }
        return result;
    }

    /** Execute a shell command via sh -c, returning [exitCode, combined stdout+stderr]. */
    private static String[] shell(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader stdout = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader stderr = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = stdout.readLine()) != null) sb.append(line).append('\n');
            while ((line = stderr.readLine()) != null) sb.append(line).append('\n');
            stdout.close();
            stderr.close();
            int exit = p.waitFor();
            return new String[]{String.valueOf(exit), sb.toString()};
        } catch (Exception e) {
            CameraDaemon.log("FormatStorage: shell command failed: " + e.getMessage());
            return new String[]{"-1", e.getMessage() != null ? e.getMessage() : "exec error"};
        }
    }

    /** Accept only "public:" followed by alphanumeric and commas — no shell injection. */
    private static boolean isValidVolumeId(String id) {
        return id != null && id.matches("public:[a-zA-Z0-9,]+");
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static void error(OutputStream out, int status, String message) throws Exception {
        JSONObject err = new JSONObject();
        err.put("success", false);
        err.put("error", message);
        sendJson(out, status, err);
    }

    private static void sendJson(OutputStream out, int status, JSONObject json) throws Exception {
        String body = json.toString();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : (status == 400 ? "Bad Request" :
            (status == 409 ? "Conflict" : "Internal Server Error"));
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + bodyBytes.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private static class SmPublicVolume {
        final String id, state;
        final String uuid;
        final boolean mounted;

        SmPublicVolume(String id, String state, String uuid) {
            this.id = id;
            this.state = state;
            this.uuid = uuid;
            this.mounted = "mounted".equals(state);
        }
    }
}
