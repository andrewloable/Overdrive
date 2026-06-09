package net.bladewatch.app.client;

import android.util.Log;

import net.bladewatch.app.server.IpcTokenManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TCP client for communicating with CameraDaemon.
 * Uses TCP on localhost to avoid SELinux cross-context restrictions.
 */
public class CameraDaemonClient {
    
    private static final String TAG = "CameraDaemonClient";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 19876;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    private volatile boolean connected = false;
    
    public interface ResponseCallback {
        void onResponse(JSONObject response);
        void onError(String error);
    }

    /**
     * Connect to CameraDaemon via TCP socket on localhost.
     * Waits up to 60s for the daemon ready sentinel (appropriate for cold-boot callers).
     */
    public boolean connect() {
        return connect(60_000);
    }

    /**
     * Connect with a caller-specified readiness timeout.
     * Use a short timeout (e.g. 2000ms) for mid-session reconnects where the daemon was already
     * known up; use the default 60s for cold-boot callers (SecretConfigBridge, etc.).
     *
     * ConnectException and SocketTimeoutException are both retried up to 3x; other exceptions
     * (auth/protocol errors) fail immediately without retry.
     */
    public boolean connect(long readinessTimeoutMs) {
        if (!DaemonReadinessChecker.waitUntilReady(readinessTimeoutMs)) {
            Log.w(TAG, "Daemon not ready after " + readinessTimeoutMs + "ms, aborting connect");
            return false;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(30000); // 30 second read timeout

                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

                connected = true;

                // Send auth token as the first message; server rejects without it
                String token = IpcTokenManager.getToken();
                if (token != null) {
                    JSONObject auth = new JSONObject();
                    auth.put("token", token);
                    writer.println(auth.toString());
                    writer.flush();
                }

                Log.d(TAG, "Connected to CameraDaemon on " + HOST + ":" + PORT);
                return true;
            } catch (java.io.IOException e) {
                // ConnectException (connection refused) and SocketTimeoutException (slow accept
                // during startup) are both IOException subclasses — retry both.
                Log.w(TAG, "connect attempt " + (attempt + 1) + "/3 failed: " + e.getMessage());
                connected = false;
                try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                socket = null;
                reader = null;
                writer = null;
                if (attempt < 2) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } catch (Exception e) {
                // Non-IO exceptions (auth/protocol/JSON errors) fail fast without retry.
                Log.e(TAG, "Failed to connect to " + HOST + ":" + PORT + ": " + e.getMessage());
                connected = false;
                return false;
            }
        }
        Log.e(TAG, "Failed to connect after 3 attempts");
        connected = false;
        return false;
    }

    /**
     * Disconnect from daemon (daemon keeps running).
     */
    public void disconnect() {
        connected = false;
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null) socket.close();
        } catch (Exception e) {
            Log.d(TAG, "Disconnect: " + e.getMessage());
        }
        socket = null;
        reader = null;
        writer = null;
        Log.d(TAG, "Disconnected from CameraDaemon (daemon still running)");
    }

    /**
     * Check if connected.
     */
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Send command and get response (blocking).
     */
    public JSONObject sendCommand(JSONObject command) throws Exception {
        if (!isConnected()) {
            // Mid-session reconnect: daemon was already known-up, so use a short readiness
            // timeout (2s) rather than the 60s cold-boot wait. This prevents one dropped
            // socket from stalling the executor for up to 60s.
            if (!connect(2_000)) {
                throw new Exception("Not connected to CameraDaemon");
            }
        }
        
        synchronized (this) {
            try {
                writer.println(command.toString());
                writer.flush();
                
                String response = reader.readLine();
                if (response == null) {
                    connected = false;
                    throw new Exception("CameraDaemon disconnected");
                }
                return new JSONObject(response);
            } catch (Exception e) {
                connected = false;
                throw e;
            }
        }
    }
    
    /**
     * Send command string and get response (blocking).
     */
    public JSONObject sendCommand(String commandJson) {
        try {
            return sendCommand(new JSONObject(commandJson));
        } catch (Exception e) {
            Log.e(TAG, "sendCommand error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Send command async.
     */
    public void sendCommandAsync(JSONObject command, ResponseCallback callback) {
        executor.submit(() -> {
            try {
                JSONObject response = sendCommand(command);
                if (callback != null) {
                    callback.onResponse(response);
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    // ==================== CONVENIENCE METHODS ====================

    /**
     * Ping daemon to check if it's alive.
     */
    public boolean ping() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "ping");
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "Ping failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Start recording specified cameras.
     */
    public void startRecording(Set<Integer> cameraIds, boolean enableStreaming, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "start");
            cmd.put("cameras", new JSONArray(cameraIds));
            cmd.put("stream", enableStreaming);
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Stop recording specified cameras (or all if null).
     */
    public void stopRecording(Set<Integer> cameraIds, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "stop");
            if (cameraIds != null) {
                cmd.put("cameras", new JSONArray(cameraIds));
            }
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Get daemon status.
     */
    public void getStatus(ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "status");
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Set output directory.
     */
    public void setOutputDir(String path, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setOutput");
            cmd.put("path", path);
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Enable/disable streaming for a camera.
     */
    public void setStreaming(int cameraId, boolean enable, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "stream");
            cmd.put("camera", cameraId);
            cmd.put("enable", enable);
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Get latest frame from a camera (blocking).
     * Returns base64-encoded JPEG or null on error.
     */
    public byte[] getFrame(int cameraId) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "getFrame");
            cmd.put("camera", cameraId);
            JSONObject response = sendCommand(cmd);
            if ("ok".equals(response.optString("status"))) {
                String base64 = response.optString("frame");
                if (base64 != null && !base64.isEmpty()) {
                    return android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getFrame error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Get frame dimensions for a camera.
     */
    public int[] getFrameDimensions(int cameraId) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "getFrame");
            cmd.put("camera", cameraId);
            JSONObject response = sendCommand(cmd);
            if ("ok".equals(response.optString("status"))) {
                return new int[] {
                    response.optInt("width", 0),
                    response.optInt("height", 0)
                };
            }
        } catch (Exception e) {
            Log.e(TAG, "getFrameDimensions error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Shutdown the daemon.
     */
    public void shutdown(ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "shutdown");
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    // ==================== QUALITY SETTINGS ====================

    /**
     * Set recording bitrate.
     * @param bitrate LOW (2 Mbps), MEDIUM (3 Mbps), or HIGH (6 Mbps)
     */
    public void setRecordingBitrate(String bitrate, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setBitrate");
            cmd.put("value", bitrate.toUpperCase());
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Set recording codec.
     * @param codec H264 or H265
     */
    public void setRecordingCodec(String codec, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setCodec");
            cmd.put("value", codec.toUpperCase());
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Get current quality settings from daemon.
     */
    public void getQualitySettings(ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "getQualitySettings");
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Set recording bitrate (blocking).
     * @param bitrate LOW, MEDIUM, or HIGH
     * @return true if successful
     */
    public boolean setRecordingBitrateSync(String bitrate) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setBitrate");
            cmd.put("value", bitrate.toUpperCase());
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingBitrate error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set recording codec (blocking).
     * @param codec H264 or H265
     * @return true if successful
     */
    public boolean setRecordingCodecSync(String codec) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setCodec");
            cmd.put("value", codec.toUpperCase());
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingCodec error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Set recordings storage type (blocking).
     * @param type INTERNAL or SD_CARD
     * @return true if successful
     */
    public boolean setRecordingsStorageTypeSync(String type) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setRecordingsStorageType");
            cmd.put("value", type.toUpperCase());
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingsStorageType error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set recordings storage limit (blocking).
     * @param limitMb limit in megabytes
     * @return true if successful
     */
    public boolean setRecordingsLimitMbSync(long limitMb) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setRecordingsLimitMb");
            cmd.put("value", limitMb);
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingsLimitMb error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Parse recording cameras from response.
     */
    public static Set<Integer> parseRecordingCameras(JSONObject response) {
        Set<Integer> cameras = new HashSet<>();
        try {
            JSONArray arr = response.optJSONArray("recording");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    cameras.add(arr.getInt(i));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
        return cameras;
    }

    /**
     * Cleanup client resources (daemon keeps running).
     */
    public void destroy() {
        disconnect();
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    // ==================== AUTH COMMANDS ====================

    /**
     * Invalidate auth cache in daemon.
     * Call this after regenerating device token to force daemon to reload auth state.
     * This ensures old JWTs signed with the previous secret are rejected.
     */
    public void invalidateAuthCache(ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "auth_invalidate");
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Invalidate auth cache in daemon (blocking).
     * @return true if successful
     */
    public boolean invalidateAuthCacheSync() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "auth_invalidate");
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "invalidateAuthCache error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Read a secret value from the daemon-owned secret store.
     */
    public String getSecret(String section, String key) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "secret_get");
            cmd.put("section", section);
            cmd.put("key", key);
            JSONObject response = sendCommand(cmd);
            if (!"ok".equals(response.optString("status"))) {
                return null;
            }
            String value = response.optString("value", "");
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            Log.e(TAG, "getSecret error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Read an entire secret section from the daemon-owned secret store.
     */
    public JSONObject getSecretSection(String section) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "secret_get_section");
            cmd.put("section", section);
            JSONObject response = sendCommand(cmd);
            if (!"ok".equals(response.optString("status"))) {
                return new JSONObject();
            }
            JSONObject value = response.optJSONObject("section");
            return value != null ? value : new JSONObject();
        } catch (Exception e) {
            Log.e(TAG, "getSecretSection error: " + e.getMessage());
            return new JSONObject();
        }
    }

    /**
     * Store a secret value in the daemon-owned secret store.
     */
    public boolean putSecret(String section, String key, Object value) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "secret_put");
            cmd.put("section", section);
            cmd.put("key", key);
            if (value != null) {
                cmd.put("value", value);
            }
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "putSecret error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Delete a secret value from the daemon-owned secret store.
     */
    public boolean deleteSecret(String section, String key) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "secret_delete");
            cmd.put("section", section);
            cmd.put("key", key);
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "deleteSecret error: " + e.getMessage());
            return false;
        }
    }
}
