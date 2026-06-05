package com.overdrive.app.notifications.push;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent push-subscription store. JSON file at the configured path.
 *
 * <p>Concurrency model: all mutations are serialized via a single monitor on
 * the instance. Reads return defensive copies so iteration in {@code PushSink}
 * doesn't race with subscribe/unsubscribe.
 */
public final class SubscriptionStore {

    private final File file;
    private final Map<String, PushSubscription> byId = new LinkedHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public SubscriptionStore(File file) {
        this.file = file;
    }

    public synchronized void load() {
        if (loaded.get()) return;
        loaded.set(true);

        if (!file.exists() || file.length() == 0) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = readAll(fis);
            JSONArray arr = new JSONArray(new String(bytes, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                try {
                    PushSubscription sub = PushSubscription.fromJson(arr.getJSONObject(i));
                    byId.put(sub.id, sub);
                } catch (Exception e) {
                    // skip corrupt entry, continue loading the rest
                }
            }
        } catch (Exception ignored) {}
    }

    public synchronized List<PushSubscription> all() {
        if (!loaded.get()) load();
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public synchronized PushSubscription get(String id) {
        if (!loaded.get()) load();
        return byId.get(id);
    }

    public synchronized void put(PushSubscription sub) {
        if (!loaded.get()) load();
        byId.put(sub.id, sub);
        persist();
    }

    public synchronized boolean remove(String id) {
        if (!loaded.get()) load();
        boolean removed = byId.remove(id) != null;
        if (removed) persist();
        return removed;
    }

    public synchronized int size() {
        if (!loaded.get()) load();
        return byId.size();
    }

    // ==================== INTERNAL ====================

    private void persist() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        JSONArray arr = new JSONArray();
        for (PushSubscription sub : byId.values()) arr.put(sub.toJson());

        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.getFD().sync();
        } catch (Exception e) {
            // Couldn't even write the tmp; leave the existing file intact.
            return;
        }
        // Atomic-rename happy path. On filesystems where rename-overwrite
        // isn't supported, fall through to the swap dance below.
        if (tmp.renameTo(file)) return;

        // Swap dance: keep a backup so a second-rename failure doesn't
        // leave us with zero subscriptions. Previously this path did
        //     file.delete(); tmp.renameTo(file);
        // and a second-rename failure (volume unmount, permission flip)
        // wiped every subscription on next boot.
        File backup = new File(file.getAbsolutePath() + ".bak");
        backup.delete();
        boolean haveBackup = file.renameTo(backup);
        if (!tmp.renameTo(file)) {
            // tmp couldn't move into place. Restore from backup so we
            // don't end up with no subscriptions on disk.
            if (haveBackup) backup.renameTo(file);
            // Leave tmp on disk; load() ignores it.
            return;
        }
        // New file is in place; remove the backup. Best-effort.
        if (haveBackup) backup.delete();
    }

    private static byte[] readAll(FileInputStream fis) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * Derive a stable subscription id from the endpoint URL. The endpoint
     * itself is large and contains opaque tokens — we hash it so the id
     * stays compact and consistent across re-subscribe attempts.
     */
    public static String idForEndpoint(String endpoint) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(endpoint.getBytes("UTF-8"));
            return android.util.Base64.encodeToString(
                    java.util.Arrays.copyOf(digest, 12),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return Integer.toHexString(endpoint.hashCode());
        }
    }
}
