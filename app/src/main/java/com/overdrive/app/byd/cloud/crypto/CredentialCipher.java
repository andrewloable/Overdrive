package com.overdrive.app.byd.cloud.crypto;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protects sensitive credential fields at rest.
 */
public final class CredentialCipher {

    private static final String TAG = "CredentialCipher";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String ENC_PREFIX = "ENC:";
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String KD_SALT = "overdrive-byd-cred-v1";
    private static final String DID_PATH = "/data/local/tmp/.byd_device_id";

    private CredentialCipher() {}

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    /**
     * Encrypt a plaintext credential for storage.
     * Returns the original value if encryption fails.
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        if (isEncrypted(plaintext)) return plaintext;

        try {
            byte[] key = deriveKey();
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));

            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);

            return ENC_PREFIX + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            logger.warn("Credential protection failed: " + e.getMessage());
            return plaintext;
        }
    }

    /**
     * Decrypt a stored credential.
     * Values without the encrypted marker are returned as-is (legacy support).
     * Returns empty string on decryption failure (forces re-setup).
     */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!isEncrypted(stored)) return stored;

        try {
            byte[] combined = android.util.Base64.decode(
                    stored.substring(ENC_PREFIX.length()), android.util.Base64.NO_WRAP);

            if (combined.length < IV_LEN + 1) return stored;

            byte[] iv = new byte[IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);

            byte[] ct = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, IV_LEN, ct, 0, ct.length);

            byte[] key = deriveKey();
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));

            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Credential recovery failed: " + e.getMessage());
            return "";
        }
    }

    private static byte[] deriveKey() throws Exception {
        String did = readDid();
        String bp;
        try {
            bp = android.os.Build.FINGERPRINT;
        } catch (Exception e) {
            bp = "unknown";
        }
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        return d.digest((KD_SALT + ":" + did + ":" + bp).getBytes(StandardCharsets.UTF_8));
    }

    private static String readDid() {
        try {
            File f = new File(DID_PATH);
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String id = r.readLine();
                r.close();
                if (id != null && !id.trim().isEmpty()) return id.trim();
            }
        } catch (Exception ignored) {}
        return "overdrive-default-device";
    }
}
