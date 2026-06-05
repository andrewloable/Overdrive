package com.overdrive.app.notifications.push;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent VAPID keypair (ECDSA secp256r1 / P-256).
 *
 * <p>Generated once on first read, stored as a JSON file at the configured
 * path. Subsequent loads return the same keypair so the public key stays
 * stable for the life of the install — Web Push subscriptions are bound to
 * the public key, so rotating it would invalidate every existing
 * subscription on every phone.
 */
public final class VapidKeyStore {

    private final File keyFile;
    private final AtomicReference<KeyPair> cached = new AtomicReference<>();

    /** Uncompressed P-256 public key, 65 bytes (0x04 || X || Y), base64url-encoded. */
    private volatile String publicKeyB64Url;

    public VapidKeyStore(File keyFile) {
        this.keyFile = keyFile;
    }

    public synchronized KeyPair load() throws Exception {
        KeyPair kp = cached.get();
        if (kp != null) return kp;

        if (keyFile.exists() && keyFile.length() > 0) {
            kp = readFromDisk();
        }
        if (kp == null) {
            kp = generateAndPersist();
        }
        cached.set(kp);
        publicKeyB64Url = encodeUncompressedPublicKey((ECPublicKey) kp.getPublic());
        return kp;
    }

    /**
     * Public key in the form Web Push clients expect for
     * {@code applicationServerKey} — uncompressed P-256, base64url, no padding.
     */
    public String publicKeyB64Url() throws Exception {
        if (publicKeyB64Url == null) load();
        return publicKeyB64Url;
    }

    public ECPrivateKey privateKey() throws Exception {
        return (ECPrivateKey) load().getPrivate();
    }

    public ECPublicKey publicKey() throws Exception {
        return (ECPublicKey) load().getPublic();
    }

    // ==================== INTERNAL ====================

    private KeyPair readFromDisk() {
        try (FileInputStream fis = new FileInputStream(keyFile)) {
            byte[] bytes = readAll(fis);
            String json = new String(bytes, "UTF-8");
            org.json.JSONObject j = new org.json.JSONObject(json);
            byte[] privDer = Base64.decode(j.getString("privPkcs8"), Base64.NO_WRAP);
            byte[] pubDer = Base64.decode(j.getString("pubX509"), Base64.NO_WRAP);

            KeyFactory kf = KeyFactory.getInstance("EC");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privDer));
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubDer));
            return new KeyPair(pub, priv);
        } catch (Exception e) {
            return null;
        }
    }

    private KeyPair generateAndPersist() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();

        File parent = keyFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        org.json.JSONObject j = new org.json.JSONObject();
        j.put("privPkcs8", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP));
        j.put("pubX509", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP));
        j.put("createdAt", System.currentTimeMillis());

        File tmp = new File(keyFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(j.toString().getBytes("UTF-8"));
            fos.getFD().sync();
        }
        if (!tmp.renameTo(keyFile)) {
            // renameTo fails silently on some filesystems if dest exists
            keyFile.delete();
            if (!tmp.renameTo(keyFile)) {
                throw new java.io.IOException("Failed to persist VAPID key file");
            }
        }
        return kp;
    }

    private static byte[] readAll(FileInputStream fis) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * Encode P-256 public key as uncompressed point (0x04 || X || Y), 65 bytes,
     * base64url-encoded with no padding (Web Push spec).
     */
    private static String encodeUncompressedPublicKey(ECPublicKey pub) {
        java.math.BigInteger x = pub.getW().getAffineX();
        java.math.BigInteger y = pub.getW().getAffineY();
        byte[] xBytes = unsignedBytes(x, 32);
        byte[] yBytes = unsignedBytes(y, 32);
        byte[] raw = new byte[65];
        raw[0] = 0x04;
        System.arraycopy(xBytes, 0, raw, 1, 32);
        System.arraycopy(yBytes, 0, raw, 33, 32);
        return Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /** Big-endian unsigned bytes, left-padded to {@code length}. */
    static byte[] unsignedBytes(java.math.BigInteger bi, int length) {
        byte[] signed = bi.toByteArray();
        if (signed.length == length) return signed;
        byte[] out = new byte[length];
        if (signed.length < length) {
            System.arraycopy(signed, 0, out, length - signed.length, signed.length);
        } else {
            // strip leading sign byte
            System.arraycopy(signed, signed.length - length, out, 0, length);
        }
        return out;
    }
}
