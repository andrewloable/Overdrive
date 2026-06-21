package net.bladewatch.app.server;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Characterization tests — pins CURRENT IpcTokenManager.isValid() accept/reject semantics
 * before uy93.3 (constant-time comparison). After the fix, timing changes but accept/reject
 * must remain identical.
 */
public class IpcTokenManagerTest {

    // A well-formed token: exactly 32 chars from TOKEN_CHARS
    private static final String VALID_TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz012345";

    @Before
    public void setUp() {
        // Inject the expected token directly via the package-private cache seam,
        // bypassing /data/local/tmp which is unavailable in JVM unit tests.
        IpcTokenManager.cachedToken = VALID_TOKEN;
    }

    @After
    public void tearDown() {
        IpcTokenManager.cachedToken = null;
    }

    @Test
    public void validTokenIsAccepted() {
        Assert.assertTrue(IpcTokenManager.isValid(VALID_TOKEN));
    }

    @Test
    public void wrongTokenIsRejected() {
        Assert.assertFalse(IpcTokenManager.isValid("WrongToken_______________________X"));
    }

    @Test
    public void emptyTokenIsRejected() {
        Assert.assertFalse(IpcTokenManager.isValid(""));
    }

    @Test
    public void nullTokenIsRejected() {
        Assert.assertFalse(IpcTokenManager.isValid(null));
    }

    @Test
    public void tokenWithCorrectLengthButWrongCharsIsRejected() {
        // Differs at last character
        String almostRight = VALID_TOKEN.substring(0, 31) + "9";
        boolean sameToken = almostRight.equals(VALID_TOKEN);
        if (!sameToken) {
            Assert.assertFalse(IpcTokenManager.isValid(almostRight));
        }
        // If by chance VALID_TOKEN ends in '9', the test is vacuous — acceptable edge case
    }

    @Test
    public void tokenWithLeadingSpaceIsRejected() {
        Assert.assertFalse(IpcTokenManager.isValid(" " + VALID_TOKEN.substring(1)));
    }

    @Test
    public void tokenCacheNullMeansNothingIsValid() {
        IpcTokenManager.cachedToken = null;
        // No token file at /data/local/tmp in JVM env → getToken() returns null → isValid = false
        Assert.assertFalse(IpcTokenManager.isValid(VALID_TOKEN));
    }
}
