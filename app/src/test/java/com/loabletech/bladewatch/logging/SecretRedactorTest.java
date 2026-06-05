package com.overdrive.app.logging;

import org.junit.Assert;
import org.junit.Test;

public class SecretRedactorTest {

    @Test
    public void redactsShortValuesCompletely() {
        Assert.assertEquals("[REDACTED]", SecretRedactor.redact("abcd"));
    }

    @Test
    public void redactsBearerTokensInMessages() {
        String redacted = SecretRedactor.redactMessage("Authorization: Bearer abcdefghijklmnop");
        Assert.assertFalse(redacted.contains("abcdefghijklmnop"));
        Assert.assertTrue(redacted.contains("Bearer "));
    }

    @Test
    public void redactsTelegramAndLabelledTokens() {
        String redacted = SecretRedactor.redactMessage(
                "Using reserved token: 123456:abcdefghi_jklmnop");
        Assert.assertFalse(redacted.contains("123456:abcdefghi_jklmnop"));
        Assert.assertTrue(redacted.contains("reserved token"));
    }

    @Test
    public void preservesNormalMessages() {
        String message = "Vehicle state updated";
        Assert.assertEquals(message, SecretRedactor.redactMessage(message));
    }
}
