package com.overdrive.app.telegram.model;

/**
 * Validated bot information from Telegram API getMe() response.
 */
public class BotInfo {
    private final long botId;
    private final String username;
    private final String firstName;
    
    public BotInfo(long botId, String username, String firstName) {
        this.botId = botId;
        this.username = username;
        this.firstName = firstName;
    }
    
    public long getBotId() { return botId; }
    public String getUsername() { return username; }
    public String getFirstName() { return firstName; }
    
    @Override
    public String toString() {
        return "BotInfo{id=" + botId + ", username=@" + username + "}";
    }
}
