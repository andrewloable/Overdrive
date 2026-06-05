package com.overdrive.app.telegram;

import com.overdrive.app.telegram.model.OwnerInfo;
import com.overdrive.app.telegram.model.PinState;

/**
 * PIN-based pairing manager for single-owner security.
 */
public interface IPairingManager {
    
    /**
     * Generate a new 6-digit PIN for pairing.
     * @return PinState with PIN and expiry info
     */
    PinState generatePin();
    
    /**
     * Get current PIN state (may be expired).
     * @return current PinState or null if none generated
     */
    PinState getCurrentPinState();
    
    /**
     * Validate PIN and pair owner if correct.
     * @param pin the PIN to validate
     * @param chatId Telegram chat ID of the user
     * @param username Telegram username
     * @param firstName Telegram first name
     * @return true if PIN valid and owner paired
     */
    boolean validateAndPair(String pin, long chatId, String username, String firstName);
    
    /**
     * Check if a chat ID is the paired owner.
     */
    boolean isOwner(long chatId);
    
    /**
     * Check if any owner is paired.
     */
    boolean hasOwner();
    
    /**
     * Get current owner info.
     * @return OwnerInfo or null if no owner
     */
    OwnerInfo getOwner();
    
    /**
     * Clear owner (unpair). Called from App UI only.
     */
    void clearOwner();
}
