package net.bladewatch.app.server;

import net.bladewatch.app.config.SecretConfigStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * Characterization tests — pins CURRENT TcpCommandServer command dispatch behavior
 * before uy93.2 (constrain/remove free-form 'shell' IPC). Tests only commands that
 * are unit-testable without CameraDaemon (which requires the full Android runtime).
 *
 * Commands that call CameraDaemon (start, stop, status, getFrame, setOutput, shutdown,
 * setStreamMode, getStreamMode, enableSurveillance, disableSurveillance, surveillanceStatus,
 * setAccState, setRecordingMode, getRecordingMode, setBitrate with valid values,
 * setCodec with valid values, setRecordingsStorageType, setRecordingsLimitMb) are
 * integration-only and cannot be pinned here.
 */
public class TcpCommandServerTest {

    private TcpCommandServer server;
    private File tempStoreFile;

    @Before
    public void setUp() throws Exception {
        server = new TcpCommandServer(19876);
        tempStoreFile = File.createTempFile("bladewatch_secrets_test", ".json");
        tempStoreFile.deleteOnExit();
        TcpCommandServer.secretStoreForTest = new SecretConfigStore(tempStoreFile);
    }

    @After
    public void tearDown() {
        TcpCommandServer.secretStoreForTest = null;
        if (tempStoreFile != null) tempStoreFile.delete();
    }

    // --- ping ---

    @Test
    public void pingReturnsOkAndPong() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "ping");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
        Assert.assertEquals("pong", resp.getString("message"));
    }

    // --- auth_invalidate ---

    @Test
    public void authInvalidateReturnsOk() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "auth_invalidate");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
        Assert.assertTrue(resp.getString("message").contains("invalidated"));
    }

    // --- secret_get ---

    @Test
    public void secretGetReturnEmptyStringForMissingKey() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "secret_get");
        cmd.put("section", "test");
        cmd.put("key", "nonexistent");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
        Assert.assertEquals("", resp.getString("value"));
    }

    @Test
    public void secretGetReturnsStoredValue() throws Exception {
        // Write first
        JSONObject put = new JSONObject();
        put.put("cmd", "secret_put");
        put.put("section", "test");
        put.put("key", "myKey");
        put.put("value", "myValue");
        JSONObject putResp = server.processCommand(put);
        Assert.assertEquals("ok", putResp.getString("status"));

        // Then read
        JSONObject get = new JSONObject();
        get.put("cmd", "secret_get");
        get.put("section", "test");
        get.put("key", "myKey");
        JSONObject getResp = server.processCommand(get);
        Assert.assertEquals("ok", getResp.getString("status"));
        Assert.assertEquals("myValue", getResp.getString("value"));
    }

    // --- secret_put ---

    @Test
    public void secretPutStringReturnsOk() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "secret_put");
        cmd.put("section", "tokens");
        cmd.put("key", "apiKey");
        cmd.put("value", "tok_abc123");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
    }

    @Test
    public void secretPutBooleanReturnsOk() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "secret_put");
        cmd.put("section", "flags");
        cmd.put("key", "enabled");
        cmd.put("value", true);
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
    }

    @Test
    public void secretPutLongReturnsOk() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "secret_put");
        cmd.put("section", "numbers");
        cmd.put("key", "count");
        cmd.put("value", 42);
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
    }

    // --- secret_get_section ---

    @Test
    public void secretGetSectionReturnsEmptyObjectForMissingSection() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "secret_get_section");
        cmd.put("section", "nosuchsection");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
        JSONObject section = resp.getJSONObject("section");
        Assert.assertEquals(0, section.length());
    }

    @Test
    public void secretGetSectionReturnsWrittenKeys() throws Exception {
        // Write a key first
        JSONObject put = new JSONObject();
        put.put("cmd", "secret_put");
        put.put("section", "mysec");
        put.put("key", "k1");
        put.put("value", "v1");
        server.processCommand(put);

        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "secret_get_section");
        cmd.put("section", "mysec");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
        Assert.assertEquals("v1", resp.getJSONObject("section").getString("k1"));
    }

    // --- secret_delete ---

    @Test
    public void secretDeleteExistingKeyReturnsOk() throws Exception {
        // Write then delete
        JSONObject put = new JSONObject();
        put.put("cmd", "secret_put");
        put.put("section", "s");
        put.put("key", "k");
        put.put("value", "v");
        server.processCommand(put);

        JSONObject del = new JSONObject();
        del.put("cmd", "secret_delete");
        del.put("section", "s");
        del.put("key", "k");
        JSONObject resp = server.processCommand(del);
        Assert.assertEquals("ok", resp.getString("status"));

        // Verify deleted
        JSONObject get = new JSONObject();
        get.put("cmd", "secret_get");
        get.put("section", "s");
        get.put("key", "k");
        Assert.assertEquals("", server.processCommand(get).getString("value"));
    }

    @Test
    public void secretDeleteMissingKeyReturnsOk() throws Exception {
        // Deleting a key that was never written: store returns ok (idempotent)
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "secret_delete");
        cmd.put("section", "empty");
        cmd.put("key", "ghost");
        JSONObject resp = server.processCommand(cmd);
        // ok (section didn't exist, no-op delete)
        Assert.assertEquals("ok", resp.getString("status"));
    }

    // --- getQualitySettings ---

    @Test
    public void getQualitySettingsReturnsOkWithShape() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "getQualitySettings");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("ok", resp.getString("status"));
        // Must have bitrate + codec + option maps
        Assert.assertNotNull(resp.opt("bitrate"));
        Assert.assertNotNull(resp.opt("codec"));
        Assert.assertTrue(resp.has("bitrateOptions"));
        Assert.assertTrue(resp.has("codecOptions"));
    }

    @Test
    public void getQualitySettingsBitrateOptionsHasExpectedKeys() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "getQualitySettings");
        JSONObject resp = server.processCommand(cmd);
        JSONObject opts = resp.getJSONObject("bitrateOptions");
        Assert.assertTrue(opts.has("LOW"));
        Assert.assertTrue(opts.has("MEDIUM"));
        Assert.assertTrue(opts.has("HIGH"));
    }

    // --- shell removed by uy93.2 ---

    @Test
    public void shellCommandRemovedUy93_2() throws Exception {
        // uy93.2: free-form "shell" IPC removed — must now return "Unknown command"
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "shell");
        cmd.put("command", "echo bladewatch_test");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("error", resp.getString("status"));
        Assert.assertTrue(resp.getString("message").contains("shell"));
    }

    // --- setBitrate validation path (no CameraDaemon call) ---

    @Test
    public void setBitrateWithInvalidValueReturnsError() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "setBitrate");
        cmd.put("value", "EXTREME");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("error", resp.getString("status"));
    }

    // --- setCodec validation path ---

    @Test
    public void setCodecWithInvalidValueReturnsError() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "setCodec");
        cmd.put("value", "VP9");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("error", resp.getString("status"));
    }

    // --- setStreamMode validation path ---

    @Test
    public void setStreamModeWithInvalidValueReturnsError() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "setStreamMode");
        cmd.put("mode", "unknown");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("error", resp.getString("status"));
    }

    // --- setRecordingMode validation path ---

    @Test
    public void setRecordingModeWithEmptyModeReturnsError() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "setRecordingMode");
        cmd.put("mode", "");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("error", resp.getString("status"));
    }

    // --- unknown command ---

    @Test
    public void unknownCommandReturnsError() throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("cmd", "totally_unknown_command");
        JSONObject resp = server.processCommand(cmd);
        Assert.assertEquals("error", resp.getString("status"));
        Assert.assertTrue(resp.getString("message").contains("totally_unknown_command"));
    }
}
