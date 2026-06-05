package com.overdrive.app.telegram.impl;

import androidx.annotation.Nullable;

import com.overdrive.app.telegram.IOwnerStore;
import com.overdrive.app.telegram.IPairingManager;
import com.overdrive.app.telegram.model.OwnerInfo;
import com.overdrive.app.telegram.model.PinState;

import java.security.SecureRandom;

/**
 * PIN-based pairing manager with single-owner security.
 * 
 * Security model:
 * - Only one owner at a time
 * - PIN expires after 5 minutes
 * - /pair from non-owners is ignored when owner exists
 * - Owner can only be cleared from App UI (not Telegram)
 */
public class PairingManager implements IPairingManager {
    
    private final IOwnerStore ownerStore;
    private final SecureRandom random = new SecureRandom();
    
    @Nullable
    private volatile PinState currentPinState;
    
    public PairingManager(IOwnerStore ownerStore) {
        this.ownerStore = ownerStore;
    }
    
    @Override
    public PinState generatePin() {
        // Generate 6-digit numeric PIN
        int pinNum = random.nextInt(900000) + 100000;  // 100000-999999
        String pin = String.valueOf(pinNum);
        
        currentPinState = new PinState(pin);
        return currentPinState;
    }
    
    @Override
    @Nullable
    public PinState getCurrentPinState() {
        return currentPinState;
    }
    
    @Override
    public boolean validateAndPair(String pin, long chatId, String username, String firstName) {
        // Owner lockout: if owner exists, reject all pairing attempts
        if (ownerStore.hasOwner()) {
            return false;
        }
        
        // Check PIN state
        PinState state = currentPinState;
        if (state == null) {
            return false;
        }
        
        // Check expiry
        if (state.isExpired()) {
            currentPinState = null;
            return false;
        }
        
        // Validate PIN
        if (!state.getPin().equals(pin)) {
            return false;
        }
        
        // PIN valid - pair owner
        OwnerInfo owner = new OwnerInfo(chatId, username, firstName, System.currentTimeMillis());
        ownerStore.saveOwner(owner);
        
        // Clear PIN after successful pairing
        currentPinState = null;
        
        return true;
    }
    
    @Override
    public boolean isOwner(long chatId) {
        OwnerInfo owner = ownerStore.getOwner();
        return owner != null && owner.getChatId() == chatId;
    }
    
    @Override
    public boolean hasOwner() {
        return ownerStore.hasOwner();
    }
    
    @Override
    @Nullable
    public OwnerInfo getOwner() {
        return ownerStore.getOwner();
    }
    
    @Override
    public void clearOwner() {
        ownerStore.clearOwner();
        currentPinState = null;
    }
}
