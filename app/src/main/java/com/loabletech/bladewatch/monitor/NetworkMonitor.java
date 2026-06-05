package com.overdrive.app.monitor;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Network Monitor - provides WiFi SSID, IP address, or Mobile Data status
 * for the HTTP status API sidebar display.
 *
 * Strategy:
 * 1. Try standard Android APIs (ConnectivityManager/WifiManager) first
 * 2. Fall back to shell commands (ip addr, dumpsys wifi) if Android APIs fail
 *
 * The shell fallback uses Runtime.exec() directly (same pattern as AccSentryDaemon,
 * SentryDaemon, etc.) — NOT AdbShellExecutor which requires ADB over TCP.
 */
public class NetworkMonitor {

    private static volatile String networkType = "none";
    private static volatile String wifiSsid = "";
    private static volatile String ipAddress = "";
    private static volatile int signalPercent = -1;
    private static volatile long lastUpdate = 0;
    private static volatile Context appContext;
    private static volatile boolean shellFallbackLogged = false;

    public static void init(Context context) {
        appContext = context;
        CameraDaemon.log("NetworkMonitor: init with context=" +
                (context != null ? context.getClass().getSimpleName() : "null"));
        refresh();
    }

    /**
     * Refresh network state. Tries Android APIs first, falls back to shell.
     */
    public static void refresh() {
        // Try Android APIs first
        if (appContext != null && tryAndroidApis()) {
            return;
        }
        // Fallback: shell commands
        tryShellFallback();
    }

    // ==================== ANDROID API APPROACH ====================

    private static boolean tryAndroidApis() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                CameraDaemon.log("NetworkMonitor: ConnectivityManager is null");
                return false;
            }

            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) {
                // Silent fallback — common when running as UID 2000 (shell daemon)
                return false;
            }

            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps == null) {
                return false;
            }

            // Get IP from LinkProperties
            String ip = "";
            try {
                LinkProperties lp = cm.getLinkProperties(activeNetwork);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            ip = addr.getHostAddress();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // LinkProperties may fail under UID 2000 — not critical, IP from shell
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                networkType = "wifi";
                ipAddress = ip;
                readWifiDetailsAndroid();
                lastUpdate = System.currentTimeMillis();
                return true;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                networkType = "cellular";
                ipAddress = ip;
                wifiSsid = "";
                signalPercent = -1;
                lastUpdate = System.currentTimeMillis();
                return true;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                networkType = "ethernet";
                ipAddress = ip;
                wifiSsid = "";
                signalPercent = -1;
                lastUpdate = System.currentTimeMillis();
                return true;
            }

            return false;
        } catch (SecurityException e) {
            // UID 2000 (shell) cannot access package manager APIs on some devices.
            // "Package android does not belong to 2000" — expected on DiLink 5.
            // Silent fallback to shell commands which work fine under UID 2000.
            if (!shellFallbackLogged) {
                CameraDaemon.log("NetworkMonitor: Android APIs unavailable (UID 2000), using shell fallback");
                shellFallbackLogged = true;
            }
            return false;
        } catch (Exception e) {
            // Other unexpected errors — log once then go silent
            if (!shellFallbackLogged) {
                CameraDaemon.log("NetworkMonitor: Android API error: " + e.getMessage() + " — using shell fallback");
                shellFallbackLogged = true;
            }
            return false;
        }
    }

    private static void readWifiDetailsAndroid() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                CameraDaemon.log("NetworkMonitor: WifiManager is null");
                wifiSsid = "WiFi";
                return;
            }
            WifiInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network activeNetwork = cm != null ? cm.getActiveNetwork() : null;
                NetworkCapabilities caps = cm != null && activeNetwork != null
                    ? cm.getNetworkCapabilities(activeNetwork)
                    : null;
                // API 31+ moved Wi-Fi identity to NetworkCapabilities; older
                // Android Auto head units still use WifiManager below.
                info = caps != null ? (caps.getTransportInfo() instanceof WifiInfo
                    ? (WifiInfo) caps.getTransportInfo()
                    : null) : null;
            } else {
                @SuppressWarnings("deprecation")
                WifiInfo legacyInfo = wm.getConnectionInfo();
                info = legacyInfo;
            }
            if (info == null) {
                wifiSsid = "WiFi";
                return;
            }
            String ssid = info.getSSID();
            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if (ssid == null || ssid.contains("unknown") || ssid.equals("<none>")) {
                // SSID hidden by Android — try shell fallback for SSID only
                String shellSsid = shellGetWifiSsid();
                wifiSsid = (shellSsid != null && !shellSsid.isEmpty()) ? shellSsid : "WiFi";
            } else {
                wifiSsid = ssid;
            }
            int rssi = info.getRssi();
            signalPercent = Math.max(0, Math.min(100, (rssi + 90) * 100 / 60));
        } catch (Exception e) {
            CameraDaemon.log("NetworkMonitor: WifiInfo error: " + e.getMessage());
            wifiSsid = "WiFi";
        }
    }

    // ==================== SHELL FALLBACK ====================

    private static void tryShellFallback() {
        try {
            // Check wlan0 for WiFi
            String wlanIp = execShell("ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");
            if (!wlanIp.isEmpty()) {
                networkType = "wifi";
                ipAddress = wlanIp;
                String ssid = shellGetWifiSsid();
                wifiSsid = (ssid != null && !ssid.isEmpty()) ? ssid : "WiFi";
                shellGetWifiSignal();
                lastUpdate = System.currentTimeMillis();
                return;
            }

            // Check rmnet for mobile data
            String[] rmnetIfaces = {"rmnet_data0", "rmnet_data1", "rmnet_data2", "rmnet_data3"};
            for (String iface : rmnetIfaces) {
                String rmnetIp = execShell("ip addr show " + iface + " 2>/dev/null | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");
                if (!rmnetIp.isEmpty()) {
                    networkType = "cellular";
                    ipAddress = rmnetIp;
                    wifiSsid = "";
                    signalPercent = -1;
                    lastUpdate = System.currentTimeMillis();
                    return;
                }
            }

            // Check eth0
            String ethIp = execShell("ip addr show eth0 2>/dev/null | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");
            if (!ethIp.isEmpty()) {
                networkType = "ethernet";
                ipAddress = ethIp;
                wifiSsid = "";
                signalPercent = -1;
                lastUpdate = System.currentTimeMillis();
                return;
            }

            // No network
            networkType = "none";
            ipAddress = "";
            wifiSsid = "";
            signalPercent = -1;
            lastUpdate = System.currentTimeMillis();
        } catch (Exception e) {
            CameraDaemon.log("NetworkMonitor: shell fallback error: " + e.getMessage());
        }
    }

    /**
     * Get WiFi SSID via shell (dumpsys wifi or wpa_cli).
     */
    private static String shellGetWifiSsid() {
        // Try dumpsys wifi — look for SSID in mWifiInfo line
        String dump = execShell("dumpsys wifi 2>/dev/null | grep 'mWifiInfo' | head -1");
        if (!dump.isEmpty()) {
            int idx = dump.indexOf("SSID: ");
            if (idx >= 0) {
                idx += 6;
                int end = dump.indexOf(",", idx);
                if (end < 0) end = dump.length();
                String ssid = dump.substring(idx, end).trim();
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("<none>")) {
                    return ssid;
                }
            }
        }
        // Fallback: wpa_cli
        String wpa = execShell("wpa_cli -i wlan0 status 2>/dev/null | grep '^ssid='");
        if (wpa.startsWith("ssid=")) {
            return wpa.substring(5).trim();
        }
        return null;
    }

    private static void shellGetWifiSignal() {
        String wpa = execShell("wpa_cli -i wlan0 signal_poll 2>/dev/null | grep '^RSSI='");
        if (wpa.startsWith("RSSI=")) {
            try {
                int rssi = Integer.parseInt(wpa.substring(5).trim());
                signalPercent = Math.max(0, Math.min(100, (rssi + 90) * 100 / 60));
                return;
            } catch (NumberFormatException ignored) {}
        }
        signalPercent = -1;
    }

    // ==================== STATUS API ====================

    /**
     * Get network info as JSON for the /status endpoint.
     * Auto-refreshes if data is older than 10 seconds.
     */
    public static JSONObject getNetworkInfo() {
        if (System.currentTimeMillis() - lastUpdate > 10000) {
            refresh();
        }
        JSONObject net = new JSONObject();
        try {
            net.put("type", networkType);
            net.put("ssid", wifiSsid);
            net.put("ip", ipAddress);
            net.put("signal", signalPercent);
        } catch (Exception ignored) {}
        return net;
    }

    // ==================== SHELL UTIL ====================

    private static String execShell(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            process.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
