# proguard-rules-strip-logs.pro
#
# Strips DaemonLogger calls from release builds.
# This file is EXCLUDED when DaemonLogConfig flags are enabled.
#
# Console logging (android.util.Log, System.out) is stripped separately
# in proguard-rules.pro and is ALWAYS stripped in release builds.

# Strip DaemonLogger (file logging for daemons)
-assumenosideeffects class com.loabletech.bladewatch.logging.DaemonLogger {
    public void debug(...);
    public void info(...);
    public void warn(...);
    public void error(...);
    public void log(...);
    public static void log(...);
    public static void logError(...);
}

# Strip LogManager (file logging for app context)
-assumenosideeffects class com.loabletech.bladewatch.logging.LogManager {
    public void log(...);
    public void debug(...);
    public void info(...);
    public void warn(...);
    public void error(...);
}
