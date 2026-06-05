package com.overdrive.app.daemon.proxy;

/**
 * Short alias for string decryption.
 * 
 * Usage: S.d("encrypted_base64") -> decrypted string
 * 
 * This class exists purely for brevity in obfuscated code.
 * The actual decryption happens in Safe.java (pure Java AES-256-CBC).
 */
public final class S {
    private S() {} // No instantiation
    
    /**
     * Decrypt an encrypted string.
     * @param e Base64-encoded AES-encrypted string
     * @return Decrypted plaintext
     */
    public static String d(String e) {
        return Safe.s(e);
    }
}
