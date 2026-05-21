package com.overdrive.app.telegram;

import com.overdrive.app.telegram.model.BotInfo;
import com.overdrive.app.telegram.model.ValidationResult;

/**
 * Bot token configuration and validation interface.
 */
public interface IBotTokenConfig {
    
    /**
     * Save bot token to encrypted storage.
     */
    void saveToken(String token);
    
    /**
     * Get stored bot token.
     * @return token or null if not set
     */
    String getToken();
    
    /**
     * Check if token is configured.
     */
    boolean hasToken();
    
    /**
     * Clear stored token.
     */
    void clearToken();
    
    /**
     * Validate token against Telegram API (getMe).
     * @return ValidationResult with BotInfo on success
     */
    ValidationResult validateToken(String token);
    
    /**
     * Get cached bot info (from last successful validation).
     */
    BotInfo getCachedBotInfo();
    
    /**
     * Save bot info after successful validation.
     */
    void saveBotInfo(BotInfo botInfo);

    /**
     * Persist token and bot identity in a single underlying write. Prefer
     * this over the saveToken/saveBotInfo pair when both are known together
     * (the validate-and-save flow): one call avoids both the
     * "configured-but-unknown-bot" race window between two writes and the
     * extra disk I/O of two full-config rewrites.
     */
    void saveTokenAndBotInfo(String token, BotInfo botInfo);
}
