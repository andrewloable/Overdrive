package com.overdrive.app.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts secrets before they are logged.
 */
public final class SecretRedactor {

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9._\\-+/=]+)");
    private static final Pattern TOKEN_LABEL_PATTERN = Pattern.compile("(?i)\\b(token|reserved token|enable token|bot token)\\s*[:=]\\s*([^\\s,;]+)");
    private static final Pattern TELEGRAM_TOKEN_PATTERN = Pattern.compile("\\b\\d{6,}:[A-Za-z0-9_-]{10,}\\b");

    private SecretRedactor() {}

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() < 8) {
            return "[REDACTED]";
        }
        return value.substring(0, 4) + "…" + value.substring(value.length() - 4)
                + "#" + sha256Prefix(value);
    }

    public static String redactToken(String label, String value) {
        if (value == null || value.isEmpty()) {
            return label + ": [REDACTED]";
        }
        return label + ": " + redact(value);
    }

    public static String redactMessage(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String redacted = redactPattern(message, BEARER_PATTERN, 1);
        redacted = redactPattern(redacted, TOKEN_LABEL_PATTERN, 2);
        redacted = redactTelegramTokens(redacted);
        return redacted;
    }

    private static String redactPattern(String input, Pattern pattern, int groupToRedact) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(groupToRedact);
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(0).replace(token, redact(token))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String redactTelegramTokens(String input) {
        Matcher matcher = TELEGRAM_TOKEN_PATTERN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(redact(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0000";
        }
    }
}
