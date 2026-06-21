package net.bladewatch.app.daemon;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import net.bladewatch.app.BuildConfig;

import java.io.FileWriter;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * APK signing-certificate self-check (uy93.10).
 *
 * Protects against repackaged/tampered BladeWatch APKs distributed under the
 * same package name that could gain IPC access to the car daemon.
 *
 * HOW IT WORKS:
 *   The expected signing cert SHA-256 is baked into BuildConfig.RELEASE_CERT_SHA256
 *   at CI build time. When that field is empty (dev builds, unsigned APKs) the check
 *   is a no-op — unsigned builds are never blocked.
 *
 * ENFORCEMENT:
 *   On signature mismatch, isTampered() returns true and callers refuse privileged
 *   actions (IPC secret fetches). Non-privileged UI still works.
 *
 * PLAY INTEGRITY:
 *   Investigated and DROPPED. BYD DiLink v3 is not a certified Android device and
 *   does not have Google Play Services. The Play Integrity API requires
 *   com.google.android.gms (Play Services) at runtime; without it every API call
 *   throws IntegrityServiceConnectionException. Certificate self-check provides
 *   equivalent anti-repackaging protection without requiring Play Services.
 *
 * KILL-SWITCH:
 *   Set BuildConfig.RELEASE_CERT_SHA256 to "" at build time to skip enforcement.
 *   (This is the default for dev/unsigned builds.)
 */
public class AppIntegrityCheck {

    private static final String SECURITY_LOG = "/data/local/tmp/bladewatch_security.log";

    private static volatile Boolean _tampered = null; // null = not yet checked

    /**
     * Returns true if the APK signing cert does NOT match the expected release cert.
     * Always returns false when BuildConfig.RELEASE_CERT_SHA256 is empty (dev/unsigned).
     * Caches the result after the first check.
     */
    public static boolean isTampered(Context ctx) {
        if (_tampered != null) return _tampered;
        _tampered = computeTampered(ctx);
        return _tampered;
    }

    /**
     * Returns the cached result without requiring a Context.
     * Returns false if isTampered() has not been called yet (fail-open).
     * Safe to call from any thread after BladeWatchApplication.onCreate() runs.
     */
    public static boolean isTamperedCached() {
        return Boolean.TRUE.equals(_tampered);
    }

    /** Force a fresh re-check (unit-test helper). */
    public static void resetForTest() {
        _tampered = null;
    }

    // --- implementation ---

    private static boolean computeTampered(Context ctx) {
        String expected = BuildConfig.RELEASE_CERT_SHA256;
        if (expected == null || expected.isEmpty()) {
            // Dev / unsigned build — no expected cert configured, skip check
            return false;
        }
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);

            Signature[] sigs = null;
            if (android.os.Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                sigs = pi.signingInfo.getApkContentsSigners();
            }
            if (sigs == null) {
                // Older API fallback — counts as unsigned/dev, no enforcement
                return false;
            }

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Signature sig : sigs) {
                sha.reset();
                String computed = bytesToHex(sha.digest(sig.toByteArray()));
                if (computed.equalsIgnoreCase(expected)) {
                    return false; // cert matches
                }
            }

            // No cert matched — report tampering
            reportEvent("SIGNATURE_MISMATCH", "expected=" + expected);
            return true;

        } catch (Exception e) {
            // PackageManager unavailable (e.g., test context) — fail-open
            return false;
        }
    }

    private static void reportEvent(String type, String detail) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String entry = ts + " [INTEGRITY:" + type + "] " + detail + "\n";
            try (FileWriter fw = new FileWriter(SECURITY_LOG, true)) {
                fw.write(entry);
            }
        } catch (Exception ignored) {}
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
