package com.overdrive.app.notifications.push;

import android.util.Base64;

/**
 * Base64url-without-padding helpers. Web Push, JWT, and RFC 8291 all
 * require this exact encoding (RFC 4648 §5).
 */
final class B64Url {
    private B64Url() {}

    private static final int FLAGS = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    static String enc(byte[] bytes) {
        return Base64.encodeToString(bytes, FLAGS);
    }

    static byte[] dec(String s) {
        return Base64.decode(s, FLAGS);
    }
}
