package com.overdrive.app.telegram;

import com.overdrive.app.telegram.model.DaemonInfo;
import com.overdrive.app.telegram.model.DaemonStatus;

import java.util.List;

/**
 * Interface for managing daemon processes.
 */
public interface IDaemonManager {
    
    /**
     * List all registered daemons with their status.
     */
    List<DaemonInfo> listDaemons();
    
    /**
     * Start a daemon by name.
     * @param name daemon name (e.g., "camera", "surveillance", "tunnel")
     * @return true if start command sent successfully
     */
    boolean startDaemon(String name);
    
    /**
     * Stop a daemon by name.
     * @param name daemon name
     * @return true if stop command sent successfully
     */
    boolean stopDaemon(String name);
    
    /**
     * Get status of a specific daemon.
     * @param name daemon name
     * @return DaemonStatus
     */
    DaemonStatus getDaemonStatus(String name);
    
    /**
     * Check if daemon exists.
     */
    boolean hasDaemon(String name);
}
