package com.overdrive.app.bridge;

import android.webkit.JavascriptInterface;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.PerformanceMonitor;

import org.json.JSONObject;

/**
 * JavaScript bridge for performance metrics access from HTML UI.
 * 
 * Exposes CPU, GPU, memory, and app-specific metrics to WebView JavaScript.
 * Provides both current snapshot and historical data for charting.
 */
public class PerformanceBridge {
    
    private static final String TAG = "PerformanceBridge";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private final PerformanceMonitor monitor;
    
    public PerformanceBridge() {
        this.monitor = PerformanceMonitor.getInstance();
    }
    
    /**
     * Get current performance snapshot as JSON string.
     * 
     * @return JSON string with CPU, GPU, memory, and app metrics
     */
    @JavascriptInterface
    public String getPerformanceData() {
        try {
            JSONObject data = monitor.getLatestAsJson();
            return data.toString();
        } catch (Exception e) {
            logger.error("Failed to get performance data", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get performance history for charting.
     * Returns last 60 samples (1 minute at 1s intervals).
     * 
     * @return JSON array of performance snapshots
     */
    @JavascriptInterface
    public String getPerformanceHistory() {
        try {
            return monitor.getHistoryAsJson().toString();
        } catch (Exception e) {
            logger.error("Failed to get performance history", e);
            return "[]";
        }
    }
    
    /**
     * Get full performance report including current + history.
     * 
     * @return JSON object with current, history, and metadata
     */
    @JavascriptInterface
    public String getFullPerformanceReport() {
        try {
            return monitor.getFullReport().toString();
        } catch (Exception e) {
            logger.error("Failed to get full report", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Check if performance monitoring is active.
     * 
     * @return "true" or "false"
     */
    @JavascriptInterface
    public String isMonitoringActive() {
        return String.valueOf(monitor.isRunning());
    }
    
    /**
     * Start performance monitoring if not already running.
     */
    @JavascriptInterface
    public void startMonitoring() {
        try {
            monitor.start();
            logger.info("Performance monitoring started via bridge");
        } catch (Exception e) {
            logger.error("Failed to start monitoring", e);
        }
    }
    
    /**
     * Stop performance monitoring.
     */
    @JavascriptInterface
    public void stopMonitoring() {
        try {
            monitor.stop();
            logger.info("Performance monitoring stopped via bridge");
        } catch (Exception e) {
            logger.error("Failed to stop monitoring", e);
        }
    }
}
