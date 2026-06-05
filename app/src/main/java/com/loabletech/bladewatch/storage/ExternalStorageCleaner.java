package com.overdrive.app.storage;

import android.os.StatFs;
import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExternalStorageCleaner - Aggressive cleanup of external app recordings (BYD CDR/Dashcam)
 * 
 * SOTA: Ensures Overdrive always has reserved space on SD card by
 * cleaning up oldest files from BYD CDR (built-in dashcam) when needed.
 * 
 * Features:
 * - Auto-discovery of SD card and CDR recording directories
 * - Configurable reserved space (default 2GB)
 * - Oldest-first deletion strategy
 * - Protected files (last N hours) option
 * - Minimum file retention (always keep N newest files)
 * - Detailed logging of all deletions
 * - Periodic monitoring (every 60 seconds when active)
 * - Thread-safe operations
 */
public class ExternalStorageCleaner {
    private static final String TAG = "ExternalStorageCleaner";
    
    // Hybrid logger
    private static boolean useDaemonLogger = false;
    private static com.overdrive.app.logging.DaemonLogger daemonLogger = null;
    
    public static void enableDaemonLogging() {
        useDaemonLogger = true;
        daemonLogger = com.overdrive.app.logging.DaemonLogger.getInstance(TAG);
    }
    
    private static void logInfo(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.info(msg);
        } else {
            Log.i(TAG, msg);
        }
    }
    
    private static void logWarn(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.warn(msg);
        } else {
            Log.w(TAG, msg);
        }
    }
    
    private static void logError(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.error(msg);
        } else {
            Log.e(TAG, msg);
        }
    }
    
    private static void logDebug(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.debug(msg);
        } else {
            Log.d(TAG, msg);
        }
    }

    // ==================== Constants ====================
    
    // Known SD card mount paths
    private static final String[] SD_CARD_PATHS = {
        "/storage/external_sd",
        "/storage/sdcard1",
        "/mnt/external_sd",
        "/mnt/sdcard/external_sd"
    };
    
    // Known BYD CDR (dashcam) recording directories
    private static final String[] CDR_SUBDIRS = {
        "Recorder/Normal",    // BYD built-in dashcam - normal recordings
        "Recorder",           // BYD built-in dashcam - root
        "Recorder/Video",
        "DCIM",
        "DCIM/Camera",
        "DCIM/100MEDIA",
        "DVR",
        "CDR",
        "行车记录仪",
        "Video",
        "Video/DVR",
        "Movies",
        "Movies/DVR",
        "Record",
        "CarRecord",
        "DashCam",
        "Camera"
    };
    
    // Video file extensions to clean
    private static final String[] VIDEO_EXTENSIONS = {
        ".mp4", ".MP4", ".avi", ".AVI", ".ts", ".TS", ".mov", ".MOV"
    };
    
    // Config file location (shared with StorageManager)
    private static final String CONFIG_FILE = "/data/local/tmp/overdrive_config.json";
    
    // Default configuration
    private static final long DEFAULT_RESERVED_SPACE_MB = 2048;  // 2GB
    private static final int DEFAULT_PROTECTED_HOURS = 24;       // 24 hours
    private static final int DEFAULT_MIN_FILES_KEEP = 10;        // Always keep 10 newest
    private static final long MONITOR_INTERVAL_SECONDS = 60;     // Check every minute
    
    // ==================== State ====================
    
    private boolean enabled = false;
    private long reservedSpaceMb = DEFAULT_RESERVED_SPACE_MB;
    private int protectedHours = DEFAULT_PROTECTED_HOURS;
    private int minFilesKeep = DEFAULT_MIN_FILES_KEEP;
    
    private String sdCardPath = null;
    private String cdrPath = null;
    private boolean sdCardAvailable = false;
    
    // Background monitor
    private ScheduledExecutorService monitorScheduler;
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);
    private final Object cleanupLock = new Object();
    
    // Statistics
    private long totalBytesFreed = 0;
    private int totalFilesDeleted = 0;
    private long lastCleanupTime = 0;
    
    // Singleton
    private static ExternalStorageCleaner instance;
    
    private ExternalStorageCleaner() {
        discoverPaths();
        loadConfig();

        // Restore monitoring across daemon restarts. Without this, the user has
        // to re-toggle the UI switch after every reboot to resume background
        // OEM-dashcam cleanup, even though `enabled=true` is persisted to disk.
        if (enabled && sdCardAvailable) {
            startMonitoring();
        }
    }
    
    public static synchronized ExternalStorageCleaner getInstance() {
        if (instance == null) {
            instance = new ExternalStorageCleaner();
        }
        return instance;
    }
    
    // ==================== Path Discovery ====================
    
    /**
     * Discover SD card path and CDR recording directory.
     * SOTA: Uses sm list-volumes to find mounted SD cards.
     */
    public void discoverPaths() {
        sdCardPath = null;
        cdrPath = null;
        sdCardAvailable = false;
        
        // Method 1: Check BYD system property for SD card UUID
        String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
        String sdExists = getSystemProperty("sys.byd.isSDExist");
        
        if ("true".equalsIgnoreCase(sdExists) && !sdUuid.isEmpty()) {
            String uuidPath = "/storage/" + sdUuid;
            File uuidDir = new File(uuidPath);
            if (uuidDir.exists() && uuidDir.isDirectory()) {
                sdCardPath = uuidPath;
                logInfo("Found SD card via UUID: " + sdCardPath);
            }
        }
        
        // Method 2: Check mounted public volumes via 'sm list-volumes'
        if (sdCardPath == null) {
            try {
                Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
                String line;
                
                while ((line = reader.readLine()) != null) {
                    // Parse lines like: "public:8,97 mounted 3661-3064"
                    line = line.trim();
                    if (line.startsWith("public:") && line.contains("mounted")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 3) {
                            String volumeUuid = parts[2];  // e.g., "3661-3064"
                            String mountPath = "/storage/" + volumeUuid;
                            File mountDir = new File(mountPath);
                            
                            if (mountDir.exists() && mountDir.isDirectory()) {
                                sdCardPath = mountPath;
                                logInfo("Found SD card via sm list-volumes: " + sdCardPath);
                                break;
                            }
                        }
                    }
                }
                reader.close();
                listProcess.waitFor();
            } catch (Exception e) {
                logDebug("Could not check sm list-volumes: " + e.getMessage());
            }
        }
        
        // Method 3: Check known paths
        if (sdCardPath == null) {
            for (String path : SD_CARD_PATHS) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory() && dir.canRead()) {
                    sdCardPath = path;
                    logInfo("Found SD card at: " + sdCardPath);
                    break;
                }
            }
        }
        
        if (sdCardPath == null) {
            logWarn("No SD card found");
            return;
        }
        
        sdCardAvailable = true;
        
        // Find CDR directory
        for (String subdir : CDR_SUBDIRS) {
            String fullPath = sdCardPath + "/" + subdir;
            File cdrDir = new File(fullPath);
            if (cdrDir.exists() && cdrDir.isDirectory() && containsVideoFiles(cdrDir)) {
                cdrPath = fullPath;
                logInfo("Found CDR directory: " + cdrPath);
                break;
            }
        }
        
        if (cdrPath == null) {
            logInfo("No CDR directory found on SD card");
            // Try to find any directory with video files
            cdrPath = findLargestVideoDirectory(new File(sdCardPath));
            if (cdrPath != null) {
                logInfo("Found video directory via scan: " + cdrPath);
            }
        }
    }
    
    /**
     * Scan SD card root to find the directory with the most video files.
     * Fallback when known CDR paths don't exist.
     */
    private String findLargestVideoDirectory(File root) {
        if (root == null || !root.exists()) return null;
        
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return null;
        
        String bestPath = null;
        int maxFiles = 0;
        
        for (File dir : dirs) {
            // Skip Android system directories
            String name = dir.getName();
            if (name.equals("Android") || name.equals(".") || name.equals("..") || 
                name.startsWith(".") || name.equals("Overdrive")) {
                continue;
            }
            
            int videoCount = countVideoFiles(dir);
            if (videoCount > maxFiles) {
                maxFiles = videoCount;
                bestPath = dir.getAbsolutePath();
            }
        }
        
        // Only return if we found at least 5 video files
        return maxFiles >= 5 ? bestPath : null;
    }
    
    /**
     * Count video files in a directory (non-recursive, just top level).
     */
    private int countVideoFiles(File dir) {
        if (dir == null || !dir.exists()) return 0;
        
        File[] files = dir.listFiles(file -> {
            if (!file.isFile()) return false;
            return isVideoFile(file);
        });
        
        return files != null ? files.length : 0;
    }
    
    /**
     * Get Android system property via reflection or shell.
     */
    private String getSystemProperty(String key) {
        try {
            // Try reflection first
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Exception e) {
            // Fall back to shell
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                reader.close();
                p.waitFor();
                return line != null ? line.trim() : "";
            } catch (Exception e2) {
                return "";
            }
        }
    }
    
    /**
     * Check if directory contains video files.
     */
    private boolean containsVideoFiles(File dir) {
        if (dir == null || !dir.exists()) return false;
        
        File[] files = dir.listFiles(file -> {
            if (!file.isFile()) return false;
            String name = file.getName().toLowerCase();
            for (String ext : VIDEO_EXTENSIONS) {
                if (name.endsWith(ext.toLowerCase())) return true;
            }
            return false;
        });
        
        return files != null && files.length > 0;
    }

    // ==================== Configuration ====================
    
    /**
     * Load configuration from config file.
     */
    public void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (!configFile.exists()) return;
            
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            
            JSONObject config = new JSONObject(sb.toString());
            JSONObject extCleanup = config.optJSONObject("externalCleanup");
            
            if (extCleanup != null) {
                enabled = extCleanup.optBoolean("enabled", false);
                reservedSpaceMb = extCleanup.optLong("reservedSpaceMb", DEFAULT_RESERVED_SPACE_MB);
                protectedHours = extCleanup.optInt("protectedHours", DEFAULT_PROTECTED_HOURS);
                minFilesKeep = extCleanup.optInt("minFilesKeep", DEFAULT_MIN_FILES_KEEP);
                
                // Override discovered CDR path if configured
                String configuredCdrPath = extCleanup.optString("cdrPath", "");
                if (!configuredCdrPath.isEmpty()) {
                    File cdrDir = new File(configuredCdrPath);
                    if (cdrDir.exists() && cdrDir.isDirectory()) {
                        cdrPath = configuredCdrPath;
                    }
                }
                
                logInfo("Loaded external cleanup config: enabled=" + enabled + 
                    ", reserved=" + reservedSpaceMb + "MB, protected=" + protectedHours + "h");
            }
        } catch (Exception e) {
            logWarn("Could not load external cleanup config: " + e.getMessage());
        }
    }
    
    /**
     * Save configuration to config file.
     */
    public void saveConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            JSONObject config;
            
            if (configFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                config = new JSONObject(sb.toString());
            } else {
                config = new JSONObject();
                config.put("version", 1);
            }
            
            JSONObject extCleanup = config.optJSONObject("externalCleanup");
            if (extCleanup == null) {
                extCleanup = new JSONObject();
            }
            
            extCleanup.put("enabled", enabled);
            extCleanup.put("reservedSpaceMb", reservedSpaceMb);
            extCleanup.put("protectedHours", protectedHours);
            extCleanup.put("minFilesKeep", minFilesKeep);
            if (cdrPath != null) {
                extCleanup.put("cdrPath", cdrPath);
            }
            
            config.put("externalCleanup", extCleanup);
            config.put("lastModified", System.currentTimeMillis());
            
            java.io.FileWriter writer = new java.io.FileWriter(configFile);
            writer.write(config.toString(2));
            writer.close();
            
            configFile.setReadable(true, false);
            configFile.setWritable(true, false);
            
            logInfo("Saved external cleanup config");
        } catch (Exception e) {
            logError("Could not save external cleanup config: " + e.getMessage());
        }
    }
    
    // ==================== Getters/Setters ====================
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { 
        this.enabled = enabled; 
        saveConfig();
        if (enabled) {
            startMonitoring();
        } else {
            stopMonitoring();
        }
    }
    
    public long getReservedSpaceMb() { return reservedSpaceMb; }
    public void setReservedSpaceMb(long mb) { 
        this.reservedSpaceMb = Math.max(100, Math.min(20000, mb));
        saveConfig();
    }
    
    public int getProtectedHours() { return protectedHours; }
    public void setProtectedHours(int hours) { 
        this.protectedHours = Math.max(0, Math.min(168, hours)); // 0-7 days
        saveConfig();
    }
    
    public int getMinFilesKeep() { return minFilesKeep; }
    public void setMinFilesKeep(int count) {
        this.minFilesKeep = Math.max(0, Math.min(100, count));
        saveConfig();
    }
    
    public boolean isSdCardAvailable() { return sdCardAvailable; }
    public String getSdCardPath() { return sdCardPath; }
    public String getCdrPath() { return cdrPath; }
    
    public long getTotalBytesFreed() { return totalBytesFreed; }
    public int getTotalFilesDeleted() { return totalFilesDeleted; }
    public long getLastCleanupTime() { return lastCleanupTime; }

    // ==================== Storage Stats ====================
    
    /**
     * Get available space on SD card in bytes.
     */
    public long getSdCardFreeSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get SD card free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on SD card in bytes.
     */
    public long getSdCardTotalSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Get total size of CDR recordings in bytes.
     */
    public long getCdrUsage() {
        if (cdrPath == null) return 0;
        return getDirectorySize(new File(cdrPath));
    }
    
    /**
     * Get count of CDR video files.
     */
    public int getCdrFileCount() {
        if (cdrPath == null) return 0;
        List<File> files = getCdrVideoFiles();
        return files.size();
    }
    
    /**
     * Get size of protected files (within protection window).
     */
    public long getProtectedSize() {
        if (cdrPath == null) return 0;
        
        long protectionCutoff = System.currentTimeMillis() - (protectedHours * 3600L * 1000L);
        long protectedSize = 0;
        
        for (File file : getCdrVideoFiles()) {
            if (file.lastModified() > protectionCutoff) {
                protectedSize += file.length();
            }
        }
        
        return protectedSize;
    }
    
    /**
     * Get size of deletable files (outside protection window, excluding min keep).
     */
    public long getDeletableSize() {
        if (cdrPath == null) return 0;
        
        List<File> files = getCdrVideoFiles();
        if (files.size() <= minFilesKeep) return 0;
        
        // Sort newest first
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        
        long protectionCutoff = System.currentTimeMillis() - (protectedHours * 3600L * 1000L);
        long deletableSize = 0;
        
        // Skip the newest minFilesKeep files
        for (int i = minFilesKeep; i < files.size(); i++) {
            File file = files.get(i);
            if (file.lastModified() < protectionCutoff) {
                deletableSize += file.length();
            }
        }
        
        return deletableSize;
    }
    
    private long getDirectorySize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getDirectorySize(file);
                }
            }
        }
        return size;
    }
    
    /**
     * Get all CDR video files, including subdirectories.
     */
    private List<File> getCdrVideoFiles() {
        List<File> videoFiles = new ArrayList<>();
        if (cdrPath == null) return videoFiles;
        
        collectVideoFiles(new File(cdrPath), videoFiles);
        return videoFiles;
    }
    
    private void collectVideoFiles(File dir, List<File> result) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles();
        if (files == null) {
            // Try shell fallback for permission issues
            files = listFilesViaShell(dir);
        }
        
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    collectVideoFiles(file, result);
                } else if (isVideoFile(file)) {
                    result.add(file);
                }
            }
        }
    }
    
    private boolean isVideoFile(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : VIDEO_EXTENSIONS) {
            if (name.endsWith(ext.toLowerCase())) return true;
        }
        return false;
    }
    
    private File[] listFilesViaShell(File dir) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ls", "-1", dir.getAbsolutePath()});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<File> files = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                files.add(new File(dir, line.trim()));
            }
            reader.close();
            p.waitFor();
            return files.toArray(new File[0]);
        } catch (Exception e) {
            return new File[0];
        }
    }

    // ==================== Cleanup Logic ====================
    
    /**
     * Result of a cleanup operation.
     */
    public static class CleanupResult {
        public final long bytesFreed;
        public final int filesDeleted;
        public final List<String> deletedFiles;
        public final String error;
        
        public CleanupResult(long bytesFreed, int filesDeleted, List<String> deletedFiles) {
            this.bytesFreed = bytesFreed;
            this.filesDeleted = filesDeleted;
            this.deletedFiles = deletedFiles;
            this.error = null;
        }
        
        public CleanupResult(String error) {
            this.bytesFreed = 0;
            this.filesDeleted = 0;
            this.deletedFiles = new ArrayList<>();
            this.error = error;
        }
        
        public boolean isSuccess() { return error == null; }
    }
    
    /**
     * Ensure reserved space is available on SD card.
     * Deletes oldest CDR files if needed.
     * 
     * @return CleanupResult with details of what was deleted
     */
    public CleanupResult ensureReservedSpace() {
        synchronized (cleanupLock) {
            if (!enabled) {
                return new CleanupResult("Cleanup not enabled");
            }
            
            if (sdCardPath == null || cdrPath == null) {
                discoverPaths();
                if (sdCardPath == null) {
                    return new CleanupResult("No SD card found");
                }
                if (cdrPath == null) {
                    return new CleanupResult("No CDR directory found");
                }
            }
            
            long freeSpace = getSdCardFreeSpace();
            long reservedBytes = reservedSpaceMb * 1024L * 1024L;
            
            if (freeSpace >= reservedBytes) {
                logDebug("SD card has sufficient space: " + formatSize(freeSpace) + 
                    " free, " + formatSize(reservedBytes) + " reserved");
                return new CleanupResult(0, 0, new ArrayList<>());
            }
            
            long toFree = reservedBytes - freeSpace;
            logInfo("Need to free " + formatSize(toFree) + " on SD card");
            
            return performCleanup(toFree);
        }
    }
    
    /**
     * Force cleanup to free specified amount of space.
     *
     * Gated on the {@code enabled} flag. This was previously documented as
     * "ignores the enabled flag" — that turned the manual-cleanup path into a
     * footgun where any caller (UI tap, HTTP POST, Telegram bot) could delete
     * OEM dashcam files even when the user had explicitly disabled the
     * feature in config. Honoring the flag here is the single source of
     * truth; callers should surface the rejection to the user.
     *
     * @param bytesToFree Minimum bytes to free
     * @return CleanupResult with details, or an error result if disabled
     */
    public CleanupResult forceCleanup(long bytesToFree) {
        synchronized (cleanupLock) {
            if (!enabled) {
                return new CleanupResult("External storage cleanup is disabled");
            }

            if (sdCardPath == null || cdrPath == null) {
                discoverPaths();
                if (cdrPath == null) {
                    return new CleanupResult("No CDR directory found");
                }
            }

            return performCleanup(bytesToFree);
        }
    }
    
    /**
     * Perform the actual cleanup operation.
     */
    private CleanupResult performCleanup(long bytesToFree) {
        List<File> files = getCdrVideoFiles();
        
        if (files.isEmpty()) {
            return new CleanupResult("No CDR files found");
        }
        
        // Sort oldest first
        files.sort(Comparator.comparingLong(File::lastModified));
        
        // Calculate protection cutoff
        long protectionCutoff = System.currentTimeMillis() - (protectedHours * 3600L * 1000L);
        
        // Determine how many files we must keep (newest N)
        int deletableCount = Math.max(0, files.size() - minFilesKeep);
        
        long freed = 0;
        int deleted = 0;
        List<String> deletedFiles = new ArrayList<>();
        
        for (int i = 0; i < deletableCount && freed < bytesToFree; i++) {
            File file = files.get(i);
            
            // Skip protected files
            if (file.lastModified() > protectionCutoff) {
                logDebug("Skipping protected file: " + file.getName() + 
                    " (age: " + getFileAge(file) + ")");
                continue;
            }
            
            long fileSize = file.length();
            String fileName = file.getName();
            
            if (deleteFile(file)) {
                freed += fileSize;
                deleted++;
                deletedFiles.add(fileName);
                logInfo("Deleted CDR file: " + fileName + " (" + formatSize(fileSize) + ")");
            } else {
                logWarn("Failed to delete: " + fileName);
            }
        }
        
        // Update statistics
        totalBytesFreed += freed;
        totalFilesDeleted += deleted;
        lastCleanupTime = System.currentTimeMillis();
        
        logInfo("CDR cleanup complete: freed " + formatSize(freed) + 
            " (" + deleted + " files)");
        
        return new CleanupResult(freed, deleted, deletedFiles);
    }
    
    /**
     * Delete a file, trying Java API first then shell fallback.
     */
    private boolean deleteFile(File file) {
        // Try Java delete
        if (file.delete()) {
            return true;
        }
        
        // Try shell rm
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"rm", "-f", file.getAbsolutePath()});
            int exitCode = p.waitFor();
            return exitCode == 0 && !file.exists();
        } catch (Exception e) {
            logWarn("Shell delete failed: " + e.getMessage());
            return false;
        }
    }
    
    private String getFileAge(File file) {
        long ageMs = System.currentTimeMillis() - file.lastModified();
        long hours = ageMs / (3600 * 1000);
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }

    // ==================== Background Monitoring ====================
    
    /**
     * Start periodic monitoring of SD card space.
     * Automatically triggers cleanup when space runs low.
     */
    public void startMonitoring() {
        if (!enabled || !sdCardAvailable) return;
        
        if (monitorScheduler != null && !monitorScheduler.isShutdown()) {
            return; // Already running
        }
        
        monitoringActive.set(true);
        
        monitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExternalStorageMonitor");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        monitorScheduler.scheduleAtFixedRate(() -> {
            try {
                if (enabled && sdCardAvailable) {
                    long freeSpace = getSdCardFreeSpace();
                    long reservedBytes = reservedSpaceMb * 1024L * 1024L;
                    
                    // Trigger cleanup at 90% of reserved threshold
                    if (freeSpace < reservedBytes * 0.9) {
                        logInfo("SD card space low (" + formatSize(freeSpace) + 
                            "), triggering cleanup");
                        ensureReservedSpace();
                    }
                }
            } catch (Exception e) {
                logWarn("Monitor error: " + e.getMessage());
            }
        }, MONITOR_INTERVAL_SECONDS, MONITOR_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        logInfo("Started external storage monitoring (interval=" + MONITOR_INTERVAL_SECONDS + "s)");
    }
    
    /**
     * Stop periodic monitoring.
     */
    public void stopMonitoring() {
        monitoringActive.set(false);
        
        if (monitorScheduler != null) {
            monitorScheduler.shutdown();
            try {
                if (!monitorScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    monitorScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitorScheduler.shutdownNow();
            }
            monitorScheduler = null;
            logInfo("Stopped external storage monitoring");
        }
    }
    
    /**
     * Check if monitoring is active.
     */
    public boolean isMonitoringActive() {
        return monitoringActive.get();
    }
    
    // ==================== Preview/Dry Run ====================
    
    /**
     * Preview what would be deleted without actually deleting.
     * Useful for UI to show user what will happen.
     * 
     * @param bytesToFree Target bytes to free
     * @return List of files that would be deleted with their sizes
     */
    public List<FileInfo> previewCleanup(long bytesToFree) {
        List<FileInfo> preview = new ArrayList<>();
        
        if (cdrPath == null) {
            discoverPaths();
            if (cdrPath == null) return preview;
        }
        
        List<File> files = getCdrVideoFiles();
        files.sort(Comparator.comparingLong(File::lastModified));
        
        long protectionCutoff = System.currentTimeMillis() - (protectedHours * 3600L * 1000L);
        int deletableCount = Math.max(0, files.size() - minFilesKeep);
        
        long wouldFree = 0;
        
        for (int i = 0; i < deletableCount && wouldFree < bytesToFree; i++) {
            File file = files.get(i);
            
            if (file.lastModified() > protectionCutoff) {
                continue; // Would be skipped
            }
            
            preview.add(new FileInfo(
                file.getName(),
                file.length(),
                file.lastModified(),
                file.getAbsolutePath()
            ));
            wouldFree += file.length();
        }
        
        return preview;
    }
    
    /**
     * File info for preview.
     */
    public static class FileInfo {
        public final String name;
        public final long size;
        public final long lastModified;
        public final String path;
        
        public FileInfo(String name, long size, long lastModified, String path) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
            this.path = path;
        }
    }
    
    // ==================== Utility ====================
    
    public static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format("%.1f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format("%.1f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format("%.1f KB", bytes / 1_000.0);
        }
        return bytes + " B";
    }
    
    /**
     * Refresh paths (call when SD card is mounted/unmounted).
     */
    public void refresh() {
        discoverPaths();
        if (enabled && sdCardAvailable && !monitoringActive.get()) {
            startMonitoring();
        }
    }
    
    /**
     * Shutdown cleaner and release resources.
     */
    public void shutdown() {
        stopMonitoring();
        logInfo("ExternalStorageCleaner shutdown complete");
    }
}
