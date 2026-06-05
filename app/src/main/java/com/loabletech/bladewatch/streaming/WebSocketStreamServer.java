package com.overdrive.app.streaming;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;

import android.media.MediaCodec;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import com.overdrive.app.logging.DaemonLogger;

public class WebSocketStreamServer extends WebSocketServer
        implements HardwareEventRecorderGpu.StreamCallback {

    private static final String TAG = "WSStreamServer";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final int PORT = 8887;
    private static final long IDLE_TIMEOUT_MS = 30_000;

    private volatile byte[] cachedSpsPps = null;
    
    /** Returns cached SPS/PPS for late-joining clients. */
    public byte[] getCachedSpsPps() { return cachedSpsPps; }
    
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Timer idleTimer;
    private volatile long lastClientDisconnectTime = 0;
    private volatile boolean idleShutdownTriggered = false;
    private Runnable idleShutdownCallback;
    private long frameCount = 0;
    private long lastLogTime = 0;
    
    // Track external clients (e.g., from HttpServer /ws path)
    private volatile int externalClientCount = 0;
    
    // SOTA FIX: Reusable frame buffer to eliminate GC pressure
    // H.264 frames are typically 50-200KB, allocate 512KB to handle spikes
    private byte[] reusableFrameBuffer = new byte[512 * 1024];

    public WebSocketStreamServer() {
        super(new InetSocketAddress(PORT));
        setReuseAddr(true);
        setConnectionLostTimeout(30);
        logger.info("WebSocketStreamServer created on port " + PORT);
    }

    public WebSocketStreamServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        setConnectionLostTimeout(30);
        logger.info("WebSocketStreamServer created on port " + port);
    }

    public void setIdleShutdownCallback(Runnable callback) {
        this.idleShutdownCallback = callback;
    }
    
    /**
     * Register an external client (e.g., from HttpServer /ws path).
     * This prevents idle timeout while external clients are connected.
     */
    public synchronized void registerExternalClient() {
        externalClientCount++;
        cancelIdleTimer();
        logger.info("External client registered (total: " + externalClientCount + ")");
    }
    
    /**
     * Unregister an external client.
     */
    public synchronized void unregisterExternalClient() {
        externalClientCount = Math.max(0, externalClientCount - 1);
        logger.info("External client unregistered (remaining: " + externalClientCount + ")");
        if (clients.isEmpty() && externalClientCount == 0) {
            lastClientDisconnectTime = System.currentTimeMillis();
            startIdleTimer();
        }
    }
    
    /**
     * Check if there are any active clients (internal or external).
     */
    public boolean hasActiveClients() {
        return !clients.isEmpty() || externalClientCount > 0;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        logger.info("WS Client connected: " + conn.getRemoteSocketAddress() + " (total: " + clients.size() + ")");
        cancelIdleTimer();
        idleShutdownTriggered = false;
        if (cachedSpsPps != null) {
            try {
                conn.send(cachedSpsPps);
                logger.info("Sent cached SPS/PPS (" + cachedSpsPps.length + " bytes)");
            } catch (Exception e) {
                logger.error("Failed to send SPS/PPS", e);
            }
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        logger.info("WS Client disconnected (remaining: " + clients.size() + ")");
        if (clients.isEmpty()) {
            lastClientDisconnectTime = System.currentTimeMillis();
            startIdleTimer();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if ("keyframe".equals(message)) {
            logger.info("Client requested keyframe");
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) { }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            clients.remove(conn);
            logger.error("WS Error: " + ex.getMessage());
            if (clients.isEmpty()) {
                lastClientDisconnectTime = System.currentTimeMillis();
                startIdleTimer();
            }
        } else {
            logger.error("WS Server error: " + ex.getMessage());
        }
    }

    @Override
    public void onStart() {
        logger.info("WebSocket Stream Server started on port " + PORT);
        lastClientDisconnectTime = System.currentTimeMillis();
        startIdleTimer();
    }

    private synchronized void startIdleTimer() {
        cancelIdleTimer();
        idleTimer = new Timer("WS-IdleTimer", true);
        idleTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkIdleTimeout();
            }
        }, IDLE_TIMEOUT_MS, 5000);
        logger.info("Idle timer started - shutdown after " + (IDLE_TIMEOUT_MS / 1000) + "s");
    }

    private synchronized void cancelIdleTimer() {
        if (idleTimer != null) {
            idleTimer.cancel();
            idleTimer = null;
        }
    }

    private synchronized void checkIdleTimeout() {
        if (!clients.isEmpty() || externalClientCount > 0) {
            cancelIdleTimer();
            return;
        }
        long idleTime = System.currentTimeMillis() - lastClientDisconnectTime;
        if (idleTime >= IDLE_TIMEOUT_MS && !idleShutdownTriggered) {
            idleShutdownTriggered = true;
            logger.info("Idle timeout (" + (idleTime / 1000) + "s) - triggering shutdown");
            cancelIdleTimer();
            if (idleShutdownCallback != null) {
                try {
                    idleShutdownCallback.run();
                } catch (Exception e) {
                    logger.error("Idle shutdown callback error", e);
                }
            }
        }
    }

    @Override
    public void onSpsPps(ByteBuffer sps, ByteBuffer pps) {
        int spsSize = sps.remaining();
        int ppsSize = pps.remaining();
        cachedSpsPps = new byte[spsSize + ppsSize];
        sps.get(cachedSpsPps, 0, spsSize);
        pps.get(cachedSpsPps, spsSize, ppsSize);
        logger.info("Cached SPS/PPS: " + spsSize + " + " + ppsSize + " bytes");
        sendToAll(cachedSpsPps);
    }

    @Override
    public void onH264Packet(ByteBuffer data, MediaCodec.BufferInfo info) {
        if (clients.isEmpty()) return;
        
        // SOTA FIX: Reuse buffer instead of allocating new byte[] per frame
        // This eliminates ~1.5MB/sec of GC pressure at 15 FPS
        int frameSize = info.size;
        if (frameSize > reusableFrameBuffer.length) {
            // Rare case: frame larger than buffer, resize once
            reusableFrameBuffer = new byte[frameSize * 2];
            logger.warn("Resized frame buffer to " + reusableFrameBuffer.length + " bytes");
        }
        
        data.position(info.offset);
        data.get(reusableFrameBuffer, 0, frameSize);
        
        // Send to all clients (they copy internally)
        sendToAll(reusableFrameBuffer, frameSize);
        
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 10000) {
            logger.info("Stats: " + frameCount + " frames, " + clients.size() + " clients");
            lastLogTime = now;
        }
    }

    private void sendToAll(byte[] data) {
        sendToAll(data, data.length);
    }
    
    private void sendToAll(byte[] data, int length) {
        for (WebSocket conn : clients) {
            try {
                if (conn.isOpen()) {
                    // WebSocket library copies data internally, safe to reuse buffer
                    conn.send(ByteBuffer.wrap(data, 0, length));
                }
                else clients.remove(conn);
            } catch (Exception e) {
                clients.remove(conn);
            }
        }
    }

    public int getClientCount() { return clients.size(); }
    public boolean hasClients() { return !clients.isEmpty(); }

    public void shutdown() {
        try {
            cancelIdleTimer();
            for (WebSocket conn : clients) {
                try { conn.close(); } catch (Exception ignored) {}
            }
            clients.clear();
            cachedSpsPps = null;
            frameCount = 0;
            stop(1000);
            logger.info("WebSocket Stream Server stopped");
        } catch (Exception e) {
            logger.error("Error stopping server", e);
        }
    }
}