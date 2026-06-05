package com.overdrive.app.monitor;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SOTA Performance Monitor - Tracks CPU, Memory, GPU, and app-specific metrics.
 * 
 * Collects metrics at configurable intervals and maintains history for charting.
 * Optimized for minimal overhead while providing comprehensive instrumentation.
 * 
 * ON-DEMAND ARCHITECTURE:
 * - CPU/GPU/Memory polling only runs when clients are actively viewing the performance page
 * - Uses reference counting to track active clients
 * - Auto-starts when first client connects, auto-stops when last client disconnects
 * - Clients must send heartbeats to maintain connection (timeout: 10 seconds)
 */
public class PerformanceMonitor {
    
    private static final String TAG = "PerformanceMonitor";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Singleton
    private static PerformanceMonitor instance;
    private static final Object lock = new Object();
    
    // Configuration
    private static final int HISTORY_SIZE = 60;  // 60 samples = 1 minute at 1s interval
    private static final long SAMPLE_INTERVAL_MS = 1000;  // 1 second
    private static final long CLIENT_TIMEOUT_MS = 10_000;  // 10 seconds heartbeat timeout
    private static final long CLEANUP_INTERVAL_MS = 5_000;  // Check for stale clients every 5s
    
    // Context
    private Context context;
    private ActivityManager activityManager;
    private int pid;
    private int uid;
    
    // Metrics history (circular buffers)
    private final LinkedList<PerformanceSnapshot> history = new LinkedList<>();
    private final AtomicReference<PerformanceSnapshot> latestSnapshot = new AtomicReference<>();
    
    // CPU tracking
    private long lastCpuTime = 0;
    private long lastAppCpuTime = 0;
    private long lastCpuTimeForApp = 0;  // Tracks CPU time baseline for app CPU calculation
    private long lastIdleTime = 0;
    
    // Scheduler
    private ScheduledExecutorService scheduler;
    private volatile boolean isRunning = false;
    
    // SOTA: Client connection tracking for on-demand polling
    private final java.util.concurrent.ConcurrentHashMap<String, Long> activeClients = new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledExecutorService clientCleanupScheduler;
    
    private PerformanceMonitor() {}
    
    public static PerformanceMonitor getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new PerformanceMonitor();
                }
            }
        }
        return instance;
    }
    
    // ==================== LIFECYCLE ====================
    
    public void init(Context context) {
        this.context = context;
        if (context != null) {
            this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        }
        this.pid = Process.myPid();
        this.uid = Process.myUid();
        logger.info("PerformanceMonitor initialized (PID: " + pid + ", UID: " + uid + ")");
    }
    
    public void start() {
        if (isRunning) return;
        isRunning = true;
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PerfMonitor");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(this::collectMetrics, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("Performance monitoring started");
    }
    
    public void stop() {
        isRunning = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        logger.info("Performance monitoring stopped");
    }
    
    // ==================== SOTA: ON-DEMAND CLIENT MANAGEMENT ====================
    
    /**
     * Register a client connection. Starts monitoring if this is the first client.
     * @param clientId Unique identifier for the client (e.g., session ID or IP:port)
     */
    public void clientConnected(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            clientId = "anonymous-" + System.currentTimeMillis();
        }
        
        boolean wasEmpty = activeClients.isEmpty();
        activeClients.put(clientId, System.currentTimeMillis());
        
        logger.debug("Client connected: " + clientId + " (total: " + activeClients.size() + ")");
        
        // Start monitoring if this is the first client
        if (wasEmpty) {
            logger.info("First client connected - starting performance monitoring");
            start();
            startClientCleanup();
        }
    }
    
    /**
     * Update client heartbeat timestamp.
     * @param clientId Client identifier
     */
    public void clientHeartbeat(String clientId) {
        if (clientId != null && activeClients.containsKey(clientId)) {
            activeClients.put(clientId, System.currentTimeMillis());
        } else if (clientId != null) {
            // New client via heartbeat - register it
            clientConnected(clientId);
        }
    }
    
    /**
     * Unregister a client connection. Stops monitoring if this was the last client.
     * @param clientId Client identifier
     */
    public void clientDisconnected(String clientId) {
        if (clientId == null) return;
        
        Long removed = activeClients.remove(clientId);
        if (removed != null) {
            logger.debug("Client disconnected: " + clientId + " (remaining: " + activeClients.size() + ")");
            
            // Stop monitoring if no clients remain
            if (activeClients.isEmpty()) {
                logger.info("Last client disconnected - stopping performance monitoring");
                stop();
                stopClientCleanup();
            }
        }
    }
    
    /**
     * Start the client cleanup scheduler to remove stale clients.
     */
    private void startClientCleanup() {
        if (clientCleanupScheduler != null) return;
        
        clientCleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PerfClientCleanup");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        clientCleanupScheduler.scheduleAtFixedRate(this::cleanupStaleClients, 
            CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Stop the client cleanup scheduler.
     */
    private void stopClientCleanup() {
        if (clientCleanupScheduler != null) {
            clientCleanupScheduler.shutdownNow();
            clientCleanupScheduler = null;
        }
    }
    
    /**
     * Remove clients that haven't sent a heartbeat within the timeout period.
     */
    private void cleanupStaleClients() {
        long now = System.currentTimeMillis();
        java.util.List<String> staleClients = new java.util.ArrayList<>();
        
        for (java.util.Map.Entry<String, Long> entry : activeClients.entrySet()) {
            if (now - entry.getValue() > CLIENT_TIMEOUT_MS) {
                staleClients.add(entry.getKey());
            }
        }
        
        for (String clientId : staleClients) {
            logger.debug("Removing stale client: " + clientId);
            clientDisconnected(clientId);
        }
    }
    
    /**
     * Get the number of active clients.
     */
    public int getActiveClientCount() {
        return activeClients.size();
    }
    
    /**
     * Check if any clients are connected.
     */
    public boolean hasActiveClients() {
        return !activeClients.isEmpty();
    }
    
    // ==================== METRICS COLLECTION ====================
    
    private void collectMetrics() {
        try {
            PerformanceSnapshot snapshot = new PerformanceSnapshot();
            snapshot.timestamp = System.currentTimeMillis();
            
            // CPU metrics
            collectCpuMetrics(snapshot);
            
            // Memory metrics
            collectMemoryMetrics(snapshot);
            
            // GPU metrics (estimated from thermal/frequency)
            collectGpuMetrics(snapshot);
            
            // App-specific metrics
            collectAppMetrics(snapshot);
            
            // Store in history
            synchronized (history) {
                history.addLast(snapshot);
                while (history.size() > HISTORY_SIZE) {
                    history.removeFirst();
                }
            }
            latestSnapshot.set(snapshot);
            
        } catch (Exception e) {
            logger.error("Failed to collect metrics", e);
        }
    }
    
    private void collectCpuMetrics(PerformanceSnapshot snapshot) {
        try {
            // Read /proc/stat for system CPU
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String line = reader.readLine();
            reader.close();
            
            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.split("\\s+");
                long user = Long.parseLong(parts[1]);
                long nice = Long.parseLong(parts[2]);
                long system = Long.parseLong(parts[3]);
                long idle = Long.parseLong(parts[4]);
                long iowait = Long.parseLong(parts[5]);
                long irq = Long.parseLong(parts[6]);
                long softirq = Long.parseLong(parts[7]);
                
                long totalCpu = user + nice + system + idle + iowait + irq + softirq;
                long totalIdle = idle + iowait;
                
                if (lastCpuTime > 0) {
                    long cpuDelta = totalCpu - lastCpuTime;
                    long idleDelta = totalIdle - lastIdleTime;
                    
                    if (cpuDelta > 0) {
                        snapshot.cpuUsagePercent = 100.0 * (cpuDelta - idleDelta) / cpuDelta;
                    }
                }
                
                lastCpuTime = totalCpu;
                lastIdleTime = totalIdle;
            }
            
            // Read /proc/[pid]/stat for app CPU
            RandomAccessFile appReader = new RandomAccessFile("/proc/" + pid + "/stat", "r");
            String appLine = appReader.readLine();
            appReader.close();
            
            if (appLine != null) {
                String[] parts = appLine.split("\\s+");
                if (parts.length > 14) {
                    long utime = Long.parseLong(parts[13]);
                    long stime = Long.parseLong(parts[14]);
                    long appCpuTime = utime + stime;
                    
                    if (lastAppCpuTime > 0 && lastCpuTime > 0) {
                        long appDelta = appCpuTime - lastAppCpuTime;
                        long cpuDelta = lastCpuTime - lastCpuTimeForApp;
                        
                        if (cpuDelta > 0) {
                            // Calculate app CPU as percentage of total CPU time
                            // This ensures app CPU <= system CPU (logically correct)
                            // Multiply by number of cores since /proc/stat is aggregate
                            int numCores = Runtime.getRuntime().availableProcessors();
                            snapshot.appCpuUsagePercent = 100.0 * appDelta / cpuDelta * numCores;
                            // Clamp: app CPU should never exceed system CPU usage
                            snapshot.appCpuUsagePercent = Math.min(snapshot.cpuUsagePercent, 
                                Math.max(0.0, snapshot.appCpuUsagePercent));
                        }
                    }
                    lastAppCpuTime = appCpuTime;
                    lastCpuTimeForApp = lastCpuTime;
                }
            }
            
            // CPU frequency
            snapshot.cpuFreqMhz = readCpuFrequency();
            
            // CPU temperature
            snapshot.cpuTempCelsius = readCpuTemperature();
            
        } catch (Exception e) {
            logger.debug("CPU metrics error: " + e.getMessage());
        }
    }

    
    private void collectMemoryMetrics(PerformanceSnapshot snapshot) {
        try {
            // System memory from /proc/meminfo
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            long memTotal = 0, memFree = 0, memAvailable = 0, buffers = 0, cached = 0;
            
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    long value = Long.parseLong(parts[1]); // in KB
                    switch (parts[0]) {
                        case "MemTotal:": memTotal = value; break;
                        case "MemFree:": memFree = value; break;
                        case "MemAvailable:": memAvailable = value; break;
                        case "Buffers:": buffers = value; break;
                        case "Cached:": cached = value; break;
                    }
                }
            }
            reader.close();
            
            snapshot.memTotalMb = memTotal / 1024.0;
            snapshot.memUsedMb = (memTotal - memAvailable) / 1024.0;
            snapshot.memUsagePercent = 100.0 * (memTotal - memAvailable) / memTotal;
            
            // App memory via Debug API
            Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memInfo);
            
            snapshot.appMemoryMb = memInfo.getTotalPss() / 1024.0;  // PSS in KB -> MB
            snapshot.appNativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0);
            snapshot.appJavaHeapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0);
            
        } catch (Exception e) {
            logger.debug("Memory metrics error: " + e.getMessage());
        }
    }
    
    private void collectGpuMetrics(PerformanceSnapshot snapshot) {
        try {
            // SOTA: Auto-detect GPU via /sys/class/devfreq/
            // Modern kernels expose GPU as a generic 'devfreq' device - works on MediaTek, Snapdragon, Exynos
            java.io.File devfreqDir = new java.io.File("/sys/class/devfreq/");
            if (devfreqDir.exists() && devfreqDir.isDirectory()) {
                java.io.File[] devices = devfreqDir.listFiles();
                if (devices != null) {
                    for (java.io.File device : devices) {
                        String name = device.getName().toLowerCase();
                        // Filter for known GPU device names
                        if (name.contains("kgsl") ||   // Adreno (Qualcomm)
                            name.contains("mali") ||   // Mali (ARM)
                            name.contains("gpu")  ||   // Generic
                            name.contains("g3d")) {    // PowerVR/Other
                            
                            // 1. Get Frequency
                            long freq = readLongFromFile(new java.io.File(device, "cur_freq"));
                            if (freq > 0) {
                                snapshot.gpuFreqMhz = normalizeFrequency(freq);
                                // logger.debug("GPU freq from devfreq/" + name + " = " + snapshot.gpuFreqMhz + " MHz");
                            }
                            
                            // 2. Get Load/Busy - different drivers expose load differently
                            // Try standard 'load' (0-100)
                            long load = readLongFromFile(new java.io.File(device, "load"));
                            if (load >= 0 && load <= 100) {
                                snapshot.gpuUsagePercent = load;
                                // logger.debug("GPU load from devfreq/" + name + "/load = " + load + "%");
                                break;
                            }
                            
                            // Try 'gpu_busy_percentage' (Qualcomm specific)
                            long busyPercent = readLongFromFile(new java.io.File(device, "gpu_busy_percentage"));
                            if (busyPercent >= 0 && busyPercent <= 100) {
                                snapshot.gpuUsagePercent = busyPercent;
                                // logger.debug("GPU busy from devfreq/" + name + "/gpu_busy_percentage = " + busyPercent + "%");
                                break;
                            }
                            
                            // If we found freq but no load, continue to try other methods
                            if (snapshot.gpuFreqMhz > 0) break;
                        }
                    }
                }
            }
            
            // Fallback: Try legacy kgsl paths directly (Qualcomm Adreno)
            if (snapshot.gpuFreqMhz == 0) {
                long freq = readLongFromFile(new java.io.File("/sys/class/kgsl/kgsl-3d0/gpuclk"));
                if (freq > 0) {
                    snapshot.gpuFreqMhz = normalizeFrequency(freq);
                    // logger.debug("GPU freq from kgsl/gpuclk = " + snapshot.gpuFreqMhz + " MHz");
                }
            }
            
            // Try kgsl gpu_busy (returns "busy_time total_time" format)
            if (snapshot.gpuUsagePercent == 0) {
                java.io.File gpuBusyFile = new java.io.File("/sys/class/kgsl/kgsl-3d0/gpu_busy");
                if (gpuBusyFile.exists() && gpuBusyFile.canRead()) {
                    try (BufferedReader br = new BufferedReader(new FileReader(gpuBusyFile))) {
                        String line = br.readLine();
                        if (line != null) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length >= 2) {
                                long busy = Long.parseLong(parts[0]);
                                long total = Long.parseLong(parts[1]);
                                if (total > 0) {
                                    snapshot.gpuUsagePercent = (busy * 100.0) / total;
                                    // logger.debug("GPU usage from kgsl/gpu_busy = " + snapshot.gpuUsagePercent + "%");
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            
            // If still no usage, estimate from frequency ratio
            if (snapshot.gpuUsagePercent == 0 && snapshot.gpuFreqMhz > 0) {
                double maxFreq = readGpuMaxFrequency();
                if (maxFreq > 0) {
                    snapshot.gpuUsagePercent = Math.min(100, (snapshot.gpuFreqMhz / maxFreq) * 100);
                    // logger.debug("GPU usage estimated from freq ratio: " + snapshot.gpuFreqMhz + "/" + maxFreq + " = " + snapshot.gpuUsagePercent + "%");
                }
            }
            
            // GPU temperature
            snapshot.gpuTempCelsius = readGpuTemperature();
            
        } catch (Exception e) {
            logger.debug("GPU metrics error: " + e.getMessage());
        }
    }
    
    /**
     * Normalize frequency to MHz - handles Hz, KHz, MHz inputs
     */
    private double normalizeFrequency(long val) {
        if (val > 1_000_000) return val / 1_000_000.0;  // Hz -> MHz
        if (val > 1_000) return val / 1_000.0;          // KHz -> MHz
        return val;                                      // Already MHz
    }
    
    /**
     * Robust single-line file reader - strips non-numeric chars for parsing
     */
    private long readLongFromFile(java.io.File file) {
        if (!file.exists() || !file.canRead()) return -1;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();
            if (line == null) return -1;
            // Scrub non-numeric characters (handles "45 %" or "45@1000")
            String numOnly = line.replaceAll("[^0-9]", "").trim();
            if (numOnly.isEmpty()) return -1;
            return Long.parseLong(numOnly);
        } catch (Exception e) {
            return -1;
        }
    }
    
    private void collectAppMetrics(PerformanceSnapshot snapshot) {
        try {
            // Thread count
            snapshot.threadCount = Thread.activeCount();
            
            // GC stats
            Runtime runtime = Runtime.getRuntime();
            snapshot.gcCount = 0;  // Would need to track via GC callbacks
            
            // File descriptors
            try {
                java.io.File fdDir = new java.io.File("/proc/" + pid + "/fd");
                String[] fds = fdDir.list();
                snapshot.openFileDescriptors = fds != null ? fds.length : 0;
            } catch (Exception ignored) {}
            
        } catch (Exception e) {
            logger.debug("App metrics error: " + e.getMessage());
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    private int readCpuFrequency() {
        String[] paths = {
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq"
        };
        
        for (String path : paths) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(path));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    return Integer.parseInt(line.trim()) / 1000;  // KHz to MHz
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }
    
    private double readCpuTemperature() {
        // Try CPU-specific thermal zones first
        String[] cpuThermalKeywords = {
            "cpu", "soc", "core", "cluster", "little", "big", "prime",
            "cpu-thermal", "cpu_thermal", "cpuss", "cpuss-0", "cpuss-1",
            "cpu-0-0", "cpu-0-1", "cpu-1-0", "cpu-1-1", "tsens_tz_sensor"
        };
        
        for (int i = 0; i < 30; i++) {
            try {
                String typePath = "/sys/class/thermal/thermal_zone" + i + "/type";
                BufferedReader typeReader = new BufferedReader(new FileReader(typePath));
                String type = typeReader.readLine();
                typeReader.close();
                
                if (type != null) {
                    String typeLower = type.toLowerCase().trim();
                    for (String keyword : cpuThermalKeywords) {
                        if (typeLower.contains(keyword)) {
                            String tempPath = "/sys/class/thermal/thermal_zone" + i + "/temp";
                            BufferedReader tempReader = new BufferedReader(new FileReader(tempPath));
                            String tempLine = tempReader.readLine();
                            tempReader.close();
                            
                            if (tempLine != null && !tempLine.trim().isEmpty()) {
                                double temp = Double.parseDouble(tempLine.trim());
                                double result = temp > 1000 ? temp / 1000.0 : temp;
                                // Only return if it's a reasonable temperature (10-120°C)
                                if (result >= 10 && result <= 120) {
                                    return result;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        
        // Fallback to direct paths
        String[] paths = {
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
            "/sys/kernel/cpu/temp"
        };
        
        for (String path : paths) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(path));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.trim().isEmpty()) {
                    double temp = Double.parseDouble(line.trim());
                    // Usually in millidegrees
                    return temp > 1000 ? temp / 1000.0 : temp;
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }
    
    /**
     * Read GPU max frequency for load estimation.
     */
    private double readGpuMaxFrequency() {
        String[] paths = {
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/devfreq/kgsl-3d0/max_freq",
            "/sys/devices/platform/mali.0/max_clock",
            "/sys/class/misc/mali0/device/max_clock",
            // Additional Qualcomm paths
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
            "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies"
        };
        
        for (String path : paths) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(path));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.trim().isEmpty()) {
                    // Handle available_frequencies format (space-separated list, highest first or last)
                    if (path.contains("available_frequencies")) {
                        String[] freqs = line.trim().split("\\s+");
                        long maxFreq = 0;
                        for (String f : freqs) {
                            try {
                                long freq = Long.parseLong(f.trim());
                                if (freq > maxFreq) maxFreq = freq;
                            } catch (Exception ignored) {}
                        }
                        if (maxFreq > 0) {
                            if (maxFreq > 1000000) return maxFreq / 1000000.0;
                            else if (maxFreq > 1000) return maxFreq / 1000.0;
                            else return maxFreq;
                        }
                    }
                    
                    long freq = Long.parseLong(line.trim());
                    // Convert to MHz based on magnitude
                    if (freq > 1000000) {
                        return freq / 1000000.0;  // Hz to MHz
                    } else if (freq > 1000) {
                        return freq / 1000.0;  // KHz to MHz
                    } else {
                        return freq;  // Already MHz
                    }
                }
            } catch (Exception ignored) {}
        }
        
        // Fallback: estimate max based on typical Adreno GPU max frequencies
        // If we're reading from kgsl (Qualcomm), assume typical max of 600-700 MHz
        return 650.0;  // Conservative estimate for Adreno GPUs
    }
    
    /**
     * Diagnostic method to discover available thermal zones and GPU paths.
     * Call this to identify what's available on the device.
     */
    public JSONObject discoverSystemPaths() {
        JSONObject discovery = new JSONObject();
        
        try {
            // Discover thermal zones
            JSONArray thermalZones = new JSONArray();
            for (int i = 0; i < 40; i++) {
                try {
                    String typePath = "/sys/class/thermal/thermal_zone" + i + "/type";
                    BufferedReader typeReader = new BufferedReader(new FileReader(typePath));
                    String type = typeReader.readLine();
                    typeReader.close();
                    
                    String tempPath = "/sys/class/thermal/thermal_zone" + i + "/temp";
                    BufferedReader tempReader = new BufferedReader(new FileReader(tempPath));
                    String tempLine = tempReader.readLine();
                    tempReader.close();
                    
                    if (type != null && tempLine != null) {
                        JSONObject zone = new JSONObject();
                        zone.put("zone", i);
                        zone.put("type", type.trim());
                        double temp = Double.parseDouble(tempLine.trim());
                        zone.put("temp", temp > 1000 ? temp / 1000.0 : temp);
                        thermalZones.put(zone);
                    }
                } catch (Exception ignored) {}
            }
            discovery.put("thermalZones", thermalZones);
            
            // Check GPU paths
            JSONArray gpuPaths = new JSONArray();
            String[] allGpuPaths = {
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/devices/platform/mali.0/clock",
                "/sys/class/misc/mali0/device/clock",
                "/sys/class/devfreq/mali/cur_freq",
                "/sys/kernel/gpu/gpu_clock",
                "/sys/class/devfreq/gpu/cur_freq"
            };
            
            for (String path : allGpuPaths) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(path));
                    String line = reader.readLine();
                    reader.close();
                    if (line != null) {
                        JSONObject pathInfo = new JSONObject();
                        pathInfo.put("path", path);
                        pathInfo.put("value", line.trim());
                        gpuPaths.put(pathInfo);
                    }
                } catch (Exception ignored) {}
            }
            discovery.put("gpuPaths", gpuPaths);
            
            // Check devfreq devices
            JSONArray devfreqDevices = new JSONArray();
            java.io.File devfreqDir = new java.io.File("/sys/class/devfreq");
            if (devfreqDir.exists() && devfreqDir.isDirectory()) {
                String[] devices = devfreqDir.list();
                if (devices != null) {
                    for (String device : devices) {
                        try {
                            String freqPath = "/sys/class/devfreq/" + device + "/cur_freq";
                            BufferedReader reader = new BufferedReader(new FileReader(freqPath));
                            String freq = reader.readLine();
                            reader.close();
                            
                            JSONObject devInfo = new JSONObject();
                            devInfo.put("device", device);
                            devInfo.put("cur_freq", freq != null ? freq.trim() : "N/A");
                            devfreqDevices.put(devInfo);
                        } catch (Exception ignored) {}
                    }
                }
            }
            discovery.put("devfreqDevices", devfreqDevices);
            
            logger.info("System discovery: " + discovery.toString());
            
        } catch (Exception e) {
            logger.error("Discovery failed", e);
        }
        
        return discovery;
    }
    
    private double readGpuTemperature() {
        // Try various thermal zones for GPU - expanded for different chipsets
        String[] gpuThermalKeywords = {
            "gpu", "adreno", "mali", "g3d", "graphics", "pvr", "vivante",
            "gpu-thermal", "gpu_thermal", "gpu-usr", "gpuss", "gpuss-0",
            "gpuss-1", "gpuss-max", "gpu-step"
        };
        
        for (int i = 0; i < 30; i++) {  // Increased range for more thermal zones
            try {
                String typePath = "/sys/class/thermal/thermal_zone" + i + "/type";
                BufferedReader typeReader = new BufferedReader(new FileReader(typePath));
                String type = typeReader.readLine();
                typeReader.close();
                
                if (type != null) {
                    String typeLower = type.toLowerCase().trim();
                    for (String keyword : gpuThermalKeywords) {
                        if (typeLower.contains(keyword)) {
                            String tempPath = "/sys/class/thermal/thermal_zone" + i + "/temp";
                            BufferedReader tempReader = new BufferedReader(new FileReader(tempPath));
                            String tempLine = tempReader.readLine();
                            tempReader.close();
                            
                            if (tempLine != null && !tempLine.trim().isEmpty()) {
                                double temp = Double.parseDouble(tempLine.trim());
                                double result = temp > 1000 ? temp / 1000.0 : temp;
                                // logger.debug("GPU temp from zone " + i + " (" + type + "): " + result + "°C");
                                return result;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        
        // Fallback: try direct GPU thermal paths
        String[] directGpuTempPaths = {
            "/sys/class/kgsl/kgsl-3d0/temp",
            "/sys/devices/platform/mali.0/temp",
            "/sys/kernel/gpu/gpu_temp",
            "/sys/devices/virtual/thermal/gpu/temp"
        };
        
        for (String path : directGpuTempPaths) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(path));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.trim().isEmpty()) {
                    double temp = Double.parseDouble(line.trim());
                    return temp > 1000 ? temp / 1000.0 : temp;
                }
            } catch (Exception ignored) {}
        }
        
        return 0;
    }

    
    // ==================== DATA ACCESS ====================
    
    public PerformanceSnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }
    
    public JSONObject getLatestAsJson() {
        PerformanceSnapshot snapshot = latestSnapshot.get();
        if (snapshot == null) {
            return new JSONObject();
        }
        return snapshot.toJson();
    }
    
    public JSONArray getHistoryAsJson() {
        JSONArray array = new JSONArray();
        synchronized (history) {
            for (PerformanceSnapshot snapshot : history) {
                array.put(snapshot.toJson());
            }
        }
        return array;
    }
    
    public JSONObject getFullReport() {
        try {
            JSONObject report = new JSONObject();
            report.put("current", getLatestAsJson());
            report.put("history", getHistoryAsJson());
            report.put("historySize", HISTORY_SIZE);
            report.put("sampleIntervalMs", SAMPLE_INTERVAL_MS);
            report.put("pid", pid);
            report.put("uid", uid);
            return report;
        } catch (Exception e) {
            logger.error("Failed to create full report", e);
            return new JSONObject();
        }
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    // ==================== SNAPSHOT DATA CLASS ====================
    
    public static class PerformanceSnapshot {
        public long timestamp;
        
        // CPU
        public double cpuUsagePercent;
        public double appCpuUsagePercent;
        public int cpuFreqMhz;
        public double cpuTempCelsius;
        
        // Memory
        public double memTotalMb;
        public double memUsedMb;
        public double memUsagePercent;
        public double appMemoryMb;
        public double appNativeHeapMb;
        public double appJavaHeapMb;
        
        // GPU
        public double gpuUsagePercent;
        public double gpuFreqMhz;
        public double gpuTempCelsius;
        
        // App
        public int threadCount;
        public int gcCount;
        public int openFileDescriptors;
        
        public JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("timestamp", timestamp);
                
                // CPU
                JSONObject cpu = new JSONObject();
                cpu.put("system", round(cpuUsagePercent));
                cpu.put("app", round(appCpuUsagePercent));
                cpu.put("freqMhz", cpuFreqMhz);
                cpu.put("tempC", round(cpuTempCelsius));
                json.put("cpu", cpu);
                
                // Memory
                JSONObject mem = new JSONObject();
                mem.put("totalMb", round(memTotalMb));
                mem.put("usedMb", round(memUsedMb));
                mem.put("usagePercent", round(memUsagePercent));
                mem.put("appTotalMb", round(appMemoryMb));
                mem.put("appNativeMb", round(appNativeHeapMb));
                mem.put("appJavaMb", round(appJavaHeapMb));
                json.put("memory", mem);
                
                // GPU
                JSONObject gpu = new JSONObject();
                gpu.put("usage", round(gpuUsagePercent));
                gpu.put("freqMhz", round(gpuFreqMhz));
                gpu.put("tempC", round(gpuTempCelsius));
                json.put("gpu", gpu);
                
                // App
                JSONObject app = new JSONObject();
                app.put("threads", threadCount);
                app.put("gcCount", gcCount);
                app.put("openFds", openFileDescriptors);
                json.put("app", app);
                
                return json;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
        
        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }
}
