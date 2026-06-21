package net.bladewatch.app.server;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Characterization tests — pins CURRENT isTrusted() behavior before uy93.1 (fail-open fix).
 * Tests annotated BASELINE mark behavior that uy93.1 / uy93.17 will flip; all others must
 * remain true after the fix.
 */
public class PeerCredentialsTest {

    @Before
    public void setUp() {
        PeerCredentials.appUidOverrideForTest = null;
    }

    @After
    public void tearDown() {
        PeerCredentials.appUidOverrideForTest = null;
    }

    // --- allow-listed system identities ---

    @Test
    public void rootUidIsTrusted() {
        Assert.assertTrue(PeerCredentials.isTrusted(0));
    }

    @Test
    public void systemUidIsTrusted() {
        Assert.assertTrue(PeerCredentials.isTrusted(1000));
    }

    @Test
    public void shellUidIsTrusted() {
        Assert.assertTrue(PeerCredentials.isTrusted(2000));
    }

    // --- unresolved UID is always rejected ---

    @Test
    public void negativeOneIsNotTrusted() {
        Assert.assertFalse(PeerCredentials.isTrusted(-1));
    }

    @Test
    public void negativeUidIsNotTrusted() {
        Assert.assertFalse(PeerCredentials.isTrusted(-999));
    }

    // --- resolved app UID is trusted (via injected seam) ---

    @Test
    public void resolvedAppUidIsTrusted() {
        PeerCredentials.appUidOverrideForTest = 10142; // arbitrary regular app UID
        Assert.assertTrue(PeerCredentials.isTrusted(10142));
    }

    @Test
    public void resolvedAppUidMatchesAcrossAndroidUsers() {
        // User 10 app: uid = 10 * 100000 + appId. Same appId mod 100000 must match.
        int appUid = 10142;
        int user10Uid = 10 * 100000 + (appUid % 100000); // 1010142
        PeerCredentials.appUidOverrideForTest = appUid;
        Assert.assertTrue("Multi-user uid should match via modulo", PeerCredentials.isTrusted(user10Uid));
    }

    @Test
    public void differentAppUidIsNotTrusted() {
        PeerCredentials.appUidOverrideForTest = 10142;
        Assert.assertFalse(PeerCredentials.isTrusted(10999));
    }

    // --- BASELINE: fail-open path when app-UID resolution is unavailable ---
    // uy93.1 replaces this with a positive identity factor; uy93.17 flips this assertion to false.

    @Test
    public void failOpenRemovedUy93_1() {
        // uy93.1: fail-open removed — uid>=10000 must be DENIED when app UID is unresolved
        PeerCredentials.appUidOverrideForTest = -1; // simulate resolution failure
        Assert.assertFalse("uy93.1: uid>=10000 must be denied when app UID unresolved",
                PeerCredentials.isTrusted(10500));
    }

    @Test
    public void failOpenDoesNotTrustUidsBelowAppRange() {
        // UIDs 3000-9999 are not root/system/shell and below app range — rejected even fail-open.
        PeerCredentials.appUidOverrideForTest = -1;
        Assert.assertFalse(PeerCredentials.isTrusted(5000));
    }
}
