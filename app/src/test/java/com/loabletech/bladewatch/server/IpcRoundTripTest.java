package net.bladewatch.app.server;

import net.bladewatch.app.config.SecretConfigStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Integration characterization test — pins the CURRENT IPC round-trip behavior
 * BEFORE uy93.1 (peer UID gate fix) and uy93.3 (constant-time token compare).
 *
 * Uses a daemon-side loopback harness: starts TcpCommandServer and SurveillanceIpcServer
 * on ephemeral test ports, then drives them via raw sockets that mimic the same wire
 * protocol as CameraDaemonClient / LocationSidecarService. After uy93.1/uy93.3, re-run
 * this test to verify the legitimate app round-trip still works.
 *
 * Why raw sockets instead of CameraDaemonClient directly: CameraDaemonClient imports
 * android.util.Log and calls DaemonReadinessChecker (sentinel-file + real port) which
 * cannot run in a JVM unit test. The raw client exercises the identical wire protocol.
 */
public class IpcRoundTripTest {

    // Well-formed 32-char token (same charset as IpcTokenManager.TOKEN_CHARS)
    private static final String TEST_TOKEN = "TestTokenABCDEFGHIJKLMNOPQR012345";

    private TcpCommandServer tcpServer;
    private SurveillanceIpcServer survServer;
    private Thread tcpThread;
    private Thread survThread;
    private int tcpPort;
    private int survPort;
    private File tempStoreFile;

    @Before
    public void setUp() throws Exception {
        // 1. Token seam: inject the test token so IpcTokenManager.isValid() accepts it
        IpcTokenManager.cachedToken = TEST_TOKEN;

        // 2. Peer UID seam: inject shell UID so PeerCredentials.isTrusted() passes
        //    (loopback sockets can't be resolved via /proc/net/tcp on macOS)
        PeerCredentials.peerUidForTest = 2000; // SHELL_UID — always trusted

        // 3. Secret store seam: inject a writable temp file
        tempStoreFile = File.createTempFile("ipc_test_secrets", ".json");
        tempStoreFile.deleteOnExit();
        TcpCommandServer.secretStoreForTest = new SecretConfigStore(tempStoreFile);

        // 4. Start TcpCommandServer on an ephemeral port
        tcpPort = findFreePort();
        tcpServer = new TcpCommandServer(tcpPort);
        tcpThread = new Thread(tcpServer::start, "test-tcp-server");
        tcpThread.setDaemon(true);
        tcpThread.start();

        // 5. Start SurveillanceIpcServer on an ephemeral port
        survPort = findFreePort();
        survServer = new SurveillanceIpcServer(survPort);
        survThread = new Thread(survServer, "test-surv-server");
        survThread.setDaemon(true);
        survThread.start();

        // Let servers bind
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

    // --- TcpCommandServer: secret_put → secret_get round-trip ---

    @Test
    public void secretPutThenGetRoundTripSucceeds() throws Exception {
        try (TcpClient client = new TcpClient(tcpPort)) {
            client.connect(TEST_TOKEN);

            // Put
            JSONObject put = new JSONObject();
            put.put("cmd", "secret_put");
            put.put("section", "tunnel");
            put.put("key", "zrokToken");
            put.put("value", "zrok_test_value_abc123");
            JSONObject putResp = client.send(put);
            Assert.assertEquals("secret_put must return ok", "ok", putResp.getString("status"));

            // Get
            JSONObject get = new JSONObject();
            get.put("cmd", "secret_get");
            get.put("section", "tunnel");
            get.put("key", "zrokToken");
            JSONObject getResp = client.send(get);
            Assert.assertEquals("ok", getResp.getString("status"));
            Assert.assertEquals("Value must round-trip",
                    "zrok_test_value_abc123", getResp.getString("value"));
        }
    }

    @Test
    public void secretGetSectionRoundTripSucceeds() throws Exception {
        try (TcpClient client = new TcpClient(tcpPort)) {
            client.connect(TEST_TOKEN);

            // Write two keys in the same section
            JSONObject put1 = new JSONObject();
            put1.put("cmd", "secret_put");
            put1.put("section", "auth");
            put1.put("key", "deviceId");
            put1.put("value", "byd-device-001");
            client.send(put1);

            JSONObject put2 = new JSONObject();
            put2.put("cmd", "secret_put");
            put2.put("section", "auth");
            put2.put("key", "deviceSecret");
            put2.put("value", "s3cr3t");
            client.send(put2);

            // Read the whole section
            JSONObject get = new JSONObject();
            get.put("cmd", "secret_get_section");
            get.put("section", "auth");
            JSONObject getResp = client.send(get);
            Assert.assertEquals("ok", getResp.getString("status"));
            JSONObject section = getResp.getJSONObject("section");
            Assert.assertEquals("byd-device-001", section.getString("deviceId"));
            Assert.assertEquals("s3cr3t", section.getString("deviceSecret"));
        }
    }

    @Test
    public void unauthorizedTokenIsRejected() throws Exception {
        // The server disconnects on bad auth — client gets EOF or Unauthorized
        try (TcpClient client = new TcpClient(tcpPort)) {
            boolean connected = client.connectRaw("WRONG_TOKEN_xxxxxxxxxxxxxxxxxxxxxxxx");
            // Server may disconnect or respond; either way the next command fails
            if (connected) {
                JSONObject ping = new JSONObject();
                ping.put("cmd", "ping");
                JSONObject resp = client.sendRaw(ping);
                // If resp is not null, it should be an error
                if (resp != null) {
                    Assert.assertEquals("Unauthorized token must be rejected",
                            "error", resp.optString("status", "error"));
                }
                // If resp is null, the server closed the connection — also correct
            }
        }
    }

    // --- SurveillanceIpcServer: UPDATE_GPS round-trip ---

    @Test
    public void updateGpsRoundTripSucceeds() throws Exception {
        JSONObject request = new JSONObject();
        request.put("token", TEST_TOKEN);
        request.put("command", "UPDATE_GPS");
        request.put("lat", 14.5995);
        request.put("lng", 120.9842);
        request.put("speed", 0.0);
        request.put("heading", 0.0);
        request.put("accuracy", 5.0);
        request.put("altitude", 10.0);
        request.put("time", System.currentTimeMillis());

        JSONObject response = sendSingleSurvMessage(request);
        Assert.assertNotNull("UPDATE_GPS must return a response", response);
        Assert.assertTrue("UPDATE_GPS must succeed", response.optBoolean("success", false));
    }

    @Test
    public void updateGpsWithUnauthorizedTokenIsRejected() throws Exception {
        JSONObject request = new JSONObject();
        request.put("token", "WRONG_TOKEN_xxxxxxxxxxxxxxxxxxxxxxxx");
        request.put("command", "UPDATE_GPS");
        request.put("lat", 14.5995);
        request.put("lng", 120.9842);

        JSONObject response = sendSingleSurvMessage(request);
        Assert.assertNotNull(response);
        Assert.assertFalse("Wrong token must be rejected",
                response.optBoolean("success", true));
        Assert.assertEquals("Unauthorized", response.optString("error", ""));
    }

    // ==================== helpers ====================

    private JSONObject sendSingleSurvMessage(JSONObject request) throws Exception {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", survPort), 3000);
            sock.setSoTimeout(5000);
            PrintWriter writer = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            writer.println(request.toString());
            String line = reader.readLine();
            return line != null ? new JSONObject(line) : null;
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    /** Minimal raw socket client mimicking CameraDaemonClient's wire protocol. */
    static class TcpClient implements AutoCloseable {
        private final int port;
        private Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;

        TcpClient(int port) {
            this.port = port;
        }

        void connect(String token) throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
            // Mirror CameraDaemonClient: send auth token as first message
            JSONObject auth = new JSONObject();
            auth.put("token", token);
            writer.println(auth.toString());
        }

        boolean connectRaw(String token) throws Exception {
            try {
                connect(token);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        JSONObject send(JSONObject cmd) throws Exception {
            writer.println(cmd.toString());
            String line = reader.readLine();
            return line != null ? new JSONObject(line) : null;
        }

        JSONObject sendRaw(JSONObject cmd) {
            try {
                return send(cmd);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void close() throws Exception {
            if (socket != null) socket.close();
        }
    }
}
