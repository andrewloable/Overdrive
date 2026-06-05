package com.overdrive.app.telegram.model;

import androidx.annotation.Nullable;

/**
 * Result of bot token validation.
 */
public class ValidationResult {
    private final boolean valid;
    @Nullable private final BotInfo botInfo;
    @Nullable private final String errorMessage;
    
    private ValidationResult(boolean valid, @Nullable BotInfo botInfo, @Nullable String errorMessage) {
        this.valid = valid;
        this.botInfo = botInfo;
        this.errorMessage = errorMessage;
    }
    
    public static ValidationResult success(BotInfo botInfo) {
        return new ValidationResult(true, botInfo, null);
    }
    
    public static ValidationResult failure(String errorMessage) {
        return new ValidationResult(false, null, errorMessage);
    }
    
    public boolean isValid() { return valid; }
    @Nullable public BotInfo getBotInfo() { return botInfo; }
    @Nullable public String getErrorMessage() { return errorMessage; }
}
