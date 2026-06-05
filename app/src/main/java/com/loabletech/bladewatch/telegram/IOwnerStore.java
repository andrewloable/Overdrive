package com.overdrive.app.telegram;

import com.overdrive.app.telegram.model.NotificationPreferences;
import com.overdrive.app.telegram.model.OwnerInfo;

/**
 * Encrypted storage for owner data and preferences.
 */
public interface IOwnerStore {
    
    /**
     * Save owner info to encrypted storage.
     */
    void saveOwner(OwnerInfo owner);
    
    /**
     * Get stored owner info.
     * @return OwnerInfo or null if not set
     */
    OwnerInfo getOwner();
    
    /**
     * Check if owner is stored.
     */
    boolean hasOwner();
    
    /**
     * Clear stored owner.
     */
    void clearOwner();
    
    /**
     * Save notification preferences.
     */
    void savePreferences(NotificationPreferences prefs);
    
    /**
     * Get notification preferences.
     * @return preferences or defaults if not set
     */
    NotificationPreferences getPreferences();
}
