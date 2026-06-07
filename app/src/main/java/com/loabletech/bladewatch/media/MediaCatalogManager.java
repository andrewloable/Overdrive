package net.bladewatch.app.media;

import net.bladewatch.app.logging.DaemonLogger;
import net.bladewatch.app.server.RecordingsApiHandler;

import org.json.JSONObject;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the {@link RecordingsDatabase} lifecycle and the live-indexing +
 * reconcile logic for recordings / surveillance / proximity clips.
 *
 * <p>Mirrors the {@code TripAnalyticsManager} ownership model: created,
 * initialised and closed by {@code CameraDaemon}, reached from recorder/HTTP
 * code via {@code CameraDaemon.getMediaCatalogManager()}.
 *
 * <p>Live indexing: the recorder finalize paths and the sidecar writer call
 * {@link #indexRecording(File)} so a freshly recorded clip appears in the DB
 * (and thus the UI) without waiting for a sync. Manual sync and the lazy
 * auto-rebuild both call {@link #reconcile()} to make the DB exactly match the
 * files on disk.
 */
public class MediaCatalogManager {

    private static final DaemonLogger logger = DaemonLogger.getInstance("MediaCatalogManager");

    private RecordingsDatabase database;
    private final AtomicBoolean reconcileInProgress = new AtomicBoolean(false);
    // AtomicBoolean so the CAS in ensureIndexedOnce() is lock-free and safe
    // to call from any thread (HTTP workers, background threads).
    private final AtomicBoolean autoRebuildAttempted = new AtomicBoolean(false);

    // ==================== LIFECYCLE ====================

    public void init() {
        database = new RecordingsDatabase();
        database.init();
        logger.info("MediaCatalogManager initialized (available=" + isAvailable() + ")");
    }

    public void shutdown() {
        if (database != null) {
            database.close();
        }
    }

    public boolean isAvailable() {
        return database != null && database.isAvailable();
    }

    public RecordingsDatabase getDatabase() {
        return database;
    }

    // ==================== LIVE INDEXING ====================

    /**
     * Classify a recording file by its filename prefix. Matches the type
     * strings used by {@link RecordingsApiHandler} ("sentry" / "proximity" /
     * "normal").
     */
    public static String classifyType(String filename) {
        if (filename == null) return "normal";
        if (filename.startsWith("event_")) return "sentry";
        if (filename.startsWith("proximity_")) return "proximity";
        return "normal";
    }

    /**
     * Index (insert/update) a single recording. Derives the type from the
     * filename, parses it via the shared {@code RecordingsApiHandler} parser,
     * and upserts. Best-effort and synchronized so the two-phase
     * finalize-then-sidecar upserts for the same path can't interleave.
     *
     * <p>No-op when the catalog DB is unavailable, the file is missing/empty,
     * or the filename doesn't match a known recording pattern.
     */
    public synchronized void indexRecording(File mp4File) {
        if (!isAvailable() || mp4File == null) return;
        try {
            if (!mp4File.exists() || !mp4File.getName().endsWith(".mp4")
                    || mp4File.length() <= 0) {
                return;
            }
            String type = classifyType(mp4File.getName());
            JSONObject parsed = RecordingsApiHandler.parseForIndex(mp4File, type);
            if (parsed == null) return;  // not a recognised recording filename
            database.upsert(
                    mp4File.getAbsolutePath(),
                    mp4File.getName(),
                    type,
                    parsed.optLong("timestamp", 0L),
                    mp4File.length(),
                    mp4File.lastModified(),
                    sidecarMtime(mp4File),
                    parsed.toString());
        } catch (Exception e) {
            logger.warn("indexRecording failed for "
                    + (mp4File != null ? mp4File.getName() : "null") + ": " + e.getMessage());
        }
    }

    private static long sidecarMtime(File mp4File) {
        File sidecar = new File(mp4File.getParentFile(),
                mp4File.getName().replace(".mp4", ".json"));
        return sidecar.exists() ? sidecar.lastModified() : 0L;
    }

    // ==================== RECONCILE ====================

    /**
     * Schedules a one-time background reconcile so the DB mirrors the files on
     * disk. Idempotent — only the first caller ever spawns the thread; all
     * subsequent callers return immediately (AtomicBoolean CAS gate).
     *
     * <p>Runs on a daemon background thread so HTTP workers are never blocked
     * by the filesystem scan. Uses {@link #reconcile()} (not
     * {@link #reconcileInternal()} directly) so the {@code reconcileInProgress}
     * AtomicBoolean is always set <em>before</em> any snapshot is taken —
     * preventing a concurrent manual-sync POST from also entering
     * {@code reconcileInternal()} simultaneously.
     *
     * <p>Handles both the fully-empty DB case and the partially-indexed case
     * (DB has some rows but fewer than files on disk): reconcile adds missing
     * rows, updates changed ones, and removes stale ones.
     */
    public void ensureIndexedOnce() {
        if (!autoRebuildAttempted.compareAndSet(false, true)) return;
        if (!isAvailable()) return;
        logger.info("Media catalog: scheduling background auto-reconcile");
        Thread t = new Thread(this::reconcile, "media-auto-reconcile");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Reconcile the DB against the files on disk: add new, update changed
     * (size/mtime/sidecar-mtime), remove rows whose files are gone. Single-
     * flight — a concurrent caller gets a busy result rather than blocking the
     * HTTP worker for the full scan.
     *
     * @return {success, added, updated, removed, total} or {success:false,
     *         error:"sync_in_progress"}.
     */
    public JSONObject reconcile() {
        Map<String, Object> m = new java.util.HashMap<>();
        if (!isAvailable()) {
            m.put("success", false);
            m.put("error", "catalog_unavailable");
            return new JSONObject(m);
        }
        if (!reconcileInProgress.compareAndSet(false, true)) {
            m.put("success", false);
            m.put("error", "sync_in_progress");
            return new JSONObject(m);
        }
        try {
            return reconcileInternal();
        } finally {
            reconcileInProgress.set(false);
        }
    }

    private JSONObject reconcileInternal() {
        Map<String, Object> m = new java.util.HashMap<>();
        int added = 0, updated = 0, removed = 0;
        try {
            // present: absolute path -> type, for every readable, non-empty .mp4
            Map<String, String> present = RecordingsApiHandler.scanAllMp4s();
            Map<String, long[]> dbState = database.getAllPathState();

            for (Map.Entry<String, String> e : present.entrySet()) {
                String path = e.getKey();
                File f = new File(path);
                long size = f.length();
                long mtime = f.lastModified();
                long sc = sidecarMtime(f);
                long[] prev = dbState.get(path);
                if (prev == null) {
                    indexRecording(f);
                    added++;
                } else if (prev[0] != size || prev[1] != mtime || prev[2] != sc) {
                    indexRecording(f);
                    updated++;
                }
            }
            for (String path : dbState.keySet()) {
                if (!present.containsKey(path)) {
                    database.deleteByPath(path);
                    removed++;
                }
            }
            m.put("success", true);
            m.put("added", added);
            m.put("updated", updated);
            m.put("removed", removed);
            m.put("total", present.size());
            logger.info("Media reconcile: +" + added + " ~" + updated + " -" + removed
                    + " (total=" + present.size() + ")");
        } catch (Exception ex) {
            logger.error("Media reconcile failed", ex);
            m.put("success", false);
            m.put("error", ex.getMessage());
        }
        return new JSONObject(m);
    }
}
