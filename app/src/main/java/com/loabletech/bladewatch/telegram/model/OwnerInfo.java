package com.overdrive.app.telegram.model;

/**
 * Paired owner information.
 */
public class OwnerInfo {
    private final long chatId;
    private final String username;
    private final String firstName;
    private final long pairedAt;
    
    public OwnerInfo(long chatId, String username, String firstName, long pairedAt) {
        this.chatId = chatId;
        this.username = username;
        this.firstName = firstName;
        this.pairedAt = pairedAt;
    }
    
    public long getChatId() { return chatId; }
    public String getUsername() { return username; }
    public String getFirstName() { return firstName; }
    public long getPairedAt() { return pairedAt; }
    
    @Override
    public String toString() {
        return "OwnerInfo{chatId=" + chatId + ", username=@" + username + "}";
    }
}
