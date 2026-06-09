package net.bladewatch.app.media;

import net.bladewatch.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * H2 embedded database that indexes recordings / surveillance / proximity
 * clips so the web UI can list them without re-scanning the filesystem and
 * re-parsing every JSON sidecar on each poll.
 *
 * <p>Mirrors {@link net.bladewatch.app.trips.TripDatabase}'s connection
 * handling (FILE_LOCK=SOCKET, DB_CLOSE_ON_EXIT=FALSE, retry/backoff,
 * ensureConnection). One difference matters: this DB is written from several
 * threads (the event recorder's drain thread via StorageManager.onFileSaved,
 * the sidecar writer's executor, the normal-recording promote path, and the
 * HTTP worker running a manual sync) whereas the trip DB is daemon-thread
 * confined. Every method is therefore {@code synchronized} on this instance —
 * the single H2 {@link Connection} is not safe for concurrent use.
 *
 * <p>Each row stores the full parsed recording JSON (exactly the object
 * {@code RecordingsApiHandler} returns per clip) plus a few indexed columns
 * for cheap filtering/sorting. This is effectively a persisted, on-disk form
 * of the in-memory RECORDING_CACHE.
 */
public class RecordingsDatabase {

    private static final String TAG = "RecordingsDatabase";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Persistent home under the user-visible BladeWatch tree (same rationale as
    // TripDatabase): survives app uninstall/reinstall, written by the shell
    // daemon UID. New DB — no legacy migration needed.
    private static final String DB_PATH = "/storage/emulated/0/BladeWatch/data/bladewatch_media_h2";
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
            ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";

    private Connection connection;
    private volatile boolean isInitialized = false;

    // ==================== LIFECYCLE ====================

    public synchronized void init() {
        if (isInitialized) return;

        logger.info("Initializing H2 media database at: " + DB_PATH);
        ensureDbDir();

        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("H2 Driver not found! Check gradle dependencies.", e);
            return;
        }

        int maxRetries = 3;
        int retryDelayMs = 1000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("SET CACHE_SIZE 8192");
                }
                createTables();
                isInitialized = true;
                logger.info("Media Database initialized via H2 (Pure Java): " + DB_PATH);
                return;
            } catch (Exception e) {
                String msg = e.getMessage();
                boolean isLockError = msg != null && (msg.contains("Locked by another process") ||
                        msg.contains("lock.db") || msg.contains("already in use"));
                if (isLockError && attempt < maxRetries) {
                    logger.warn("Media DB locked (attempt " + attempt + "/" + maxRetries + "), cleaning up stale locks...");
                    cleanupStaleLocks();
                    try {
                        Thread.sleep((long) retryDelayMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    logger.error("Failed to initialize media database: " + e.getClass().getName() + " - " + msg, e);
                    break;
                }
            }
        }
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("Media database connection closed");
            } catch (Exception e) {
                logger.error("Failed to close media database connection", e);
            }
            connection = null;
        }
        isInitialized = false;
    }

    public synchronized boolean isAvailable() {
        return isInitialized && connection != null;
    }

    private synchronized void reconnect() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                isInitialized = true;
                logger.info("H2 media database connection re-established");
            }
        } catch (Exception e) {
            logger.error("Failed to reconnect to H2 media database", e);
        }
    }

    private boolean ensureConnection() {
        if (!isInitialized && connection == null) return false;
        try {
            if (connection == null || connection.isClosed()) {
                logger.info("Media DB connection closed, reconnecting...");
                reconnect();
                return connection != null && !connection.isClosed();
            }
            return true;
        } catch (Exception e) {
            logger.error("Media DB connection check failed", e);
            reconnect();
            try {
                return connection != null && !connection.isClosed();
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private void ensureDbDir() {
        try {
            java.io.File parent = new java.io.File(DB_PATH).getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warn("Failed to create media DB directory: " + parent.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("ensureDbDir failed: " + e.getMessage());
        }
    }

    private void cleanupStaleLocks() {
        try {
            java.io.File lockFile = new java.io.File(DB_PATH + ".lock.db");
            if (lockFile.exists()) {
                long ageMs = System.currentTimeMillis() - lockFile.lastModified();
                if (ageMs > 5 * 60 * 1000) {
                    if (lockFile.delete()) {
                        logger.info("Deleted stale media DB lock file (age: " + (ageMs / 1000) + "s)");
                    }
                }
            }
            java.io.File traceFile = new java.io.File(DB_PATH + ".trace.db");
            if (traceFile.exists()) {
                traceFile.delete();
            }
        } catch (Exception e) {
            logger.debug("Media DB lock cleanup failed: " + e.getMessage());
        }
    }

    private void createTables() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS recordings (" +
                "path VARCHAR(700) PRIMARY KEY," +     // absolute mp4 path (natural key)
                "filename VARCHAR(255) NOT NULL," +
                "type VARCHAR(16) NOT NULL," +          // normal | sentry | proximity
                "timestamp_ms BIGINT NOT NULL," +       // parsed from filename
                "size_bytes BIGINT DEFAULT 0," +        // change detection
                "mtime BIGINT DEFAULT 0," +             // change detection
                "sidecar_mtime BIGINT DEFAULT 0," +     // change detection (enrichment)
                "json CLOB" +                            // full parsed recording object
                ")"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_ts ON recordings(timestamp_ms)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rec_type ON recordings(type)");
        }
    }

    // ==================== WRITE ====================

    /**
     * Insert or update a recording row keyed by absolute path.
     */
    public synchronized void upsert(String path, String filename, String type,
                                    long timestampMs, long sizeBytes, long mtime,
                                    long sidecarMtime, String json) {
        if (!ensureConnection()) return;
        String sql = "MERGE INTO recordings (path, filename, type, timestamp_ms, " +
                "size_bytes, mtime, sidecar_mtime, json) KEY(path) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, path);
            pstmt.setString(2, filename);
            pstmt.setString(3, type);
            pstmt.setLong(4, timestampMs);
            pstmt.setLong(5, sizeBytes);
            pstmt.setLong(6, mtime);
            pstmt.setLong(7, sidecarMtime);
            pstmt.setString(8, json);
            pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to upsert recording: " + path, e);
            reconnect();
        }
    }

    public synchronized boolean deleteByPath(String absPath) {
        if (absPath == null || !ensureConnection()) return false;
        try (PreparedStatement pstmt = connection.prepareStatement(
                "DELETE FROM recordings WHERE path=?")) {
            pstmt.setString(1, absPath);
            return pstmt.executeUpdate() > 0;
        } catch (Exception e) {
            logger.error("Failed to delete recording: " + absPath, e);
            reconnect();
        }
        return false;
    }

    public synchronized long resetAll() {
        if (!ensureConnection()) return -1;
        try (Statement stmt = connection.createStatement()) {
            int n = stmt.executeUpdate("DELETE FROM recordings");
            logger.info("resetAll: cleared " + n + " rows from recordings");
            return n;
        } catch (Exception e) {
            logger.error("media resetAll failed", e);
            return -1;
        }
    }

    // ==================== READ ====================

    public synchronized int getCount() {
        if (!ensureConnection()) return 0;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM recordings")) {
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) {
            logger.error("Failed to get recording count", e);
            reconnect();
        }
        return 0;
    }

    /**
     * Full recording objects within the time window, optionally narrowed by
     * type, ordered newest-first. Returns the same JSON shape the filesystem
     * scan produces (stored verbatim), so the API post-processing (dedup,
     * filtering, pagination) is identical regardless of source.
     */
    public synchronized List<JSONObject> getRecordings(String type, long startMs, long endMs) {
        List<JSONObject> out = new ArrayList<>();
        if (!ensureConnection()) return out;
        StringBuilder sql = new StringBuilder(
                "SELECT json FROM recordings WHERE timestamp_ms >= ? AND timestamp_ms < ?");
        boolean hasType = type != null && !type.isEmpty();
        if (hasType) sql.append(" AND type = ?");
        sql.append(" ORDER BY timestamp_ms DESC");
        try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
            pstmt.setLong(1, startMs);
            pstmt.setLong(2, endMs);
            if (hasType) pstmt.setString(3, type);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString(1);
                    if (json == null) continue;
                    try {
                        out.add(new JSONObject(json));
                    } catch (Exception ignored) {
                        logger.warn("Skipping corrupt JSON row in recordings query: " + ignored.getMessage());
                        // skip a corrupt row; reconcile/sync will rebuild it
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to query recordings", e);
            reconnect();
        }
        return out;
    }

    /**
     * Returns one {@code long[2]} per row: {@code [timestamp_ms, isSentry]}
     * (isSentry=1 when type='sentry', else 0) for calendar/date aggregation.
     * Avoids allocating JSONObjects; no json CLOB read.
     */
    public synchronized List<long[]> getDateRows() {
        List<long[]> out = new ArrayList<>();
        if (!ensureConnection()) return out;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT timestamp_ms, CASE WHEN type='sentry' THEN 1 ELSE 0 END" +
                     " FROM recordings ORDER BY timestamp_ms DESC")) {
            while (rs.next()) {
                out.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        } catch (Exception e) {
            logger.error("Failed to query date rows", e);
            reconnect();
        }
        return out;
    }

    /**
     * Aggregates per-type counts, live sizes, and today-window counts into
     * the caller's {@code agg} array without materialising JSON.
     *
     * <p>Uses live {@code File.length()} per row for size accuracy (stale
     * {@code size_bytes} values from SD-card write errors are corrected);
     * falls back to the stored value when the file is temporarily unavailable.
     * Deduplicates by filename per type to match the filesystem scan behaviour
     * (a clip accessible at two paths is counted once).
     *
     * <p>{@code agg} layout: [0]=normalSize, [1]=normalCount, [2]=sentrySize,
     * [3]=sentryCount, [4]=proximitySize, [5]=proximityCount, [6]=normalToday,
     * [7]=sentryToday, [8]=proximityToday.
     */
    public synchronized void aggregateForStats(long[] agg, long todayStartMs, long todayEndMs) {
        if (!ensureConnection()) return;
        java.util.Set<String> seenNormal = new java.util.HashSet<>();
        java.util.Set<String> seenSentry = new java.util.HashSet<>();
        java.util.Set<String> seenProx = new java.util.HashSet<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT path, filename, type, size_bytes, timestamp_ms FROM recordings")) {
            while (rs.next()) {
                String path = rs.getString(1);
                String fn = rs.getString(2);
                String type = rs.getString(3);
                long storedSize = rs.getLong(4);
                long ts = rs.getLong(5);
                // Live file.length() for accuracy; fall back to stored value if unavailable.
                long size = storedSize;
                if (path != null && !path.isEmpty()) {
                    long live = new java.io.File(path).length();
                    if (live > 0) size = live;
                }
                boolean today = ts >= todayStartMs && ts < todayEndMs;
                if ("sentry".equals(type)) {
                    if (!seenSentry.add(fn)) continue;
                    agg[2] += size; agg[3]++; if (today) agg[7]++;
                } else if ("proximity".equals(type)) {
                    if (!seenProx.add(fn)) continue;
                    agg[4] += size; agg[5]++; if (today) agg[8]++;
                } else {
                    if (!seenNormal.add(fn)) continue;
                    agg[0] += size; agg[1]++; if (today) agg[6]++;
                }
            }
        } catch (Exception e) {
            logger.error("aggregateForStats failed", e);
            reconnect();
        }
    }

    /**
     * Map of absolute path -&gt; [size, mtime, sidecarMtime] for reconcile diffing.
     */
    public synchronized Map<String, long[]> getAllPathState() {
        Map<String, long[]> out = new HashMap<>();
        if (!ensureConnection()) return out;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT path, size_bytes, mtime, sidecar_mtime FROM recordings")) {
            while (rs.next()) {
                out.put(rs.getString(1),
                        new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4)});
            }
        } catch (Exception e) {
            logger.error("Failed to read recording path state", e);
            reconnect();
        }
        return out;
    }
}
