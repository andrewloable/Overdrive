package com.overdrive.app.daemon.telegram;

/**
 * Base interface for Telegram command handlers.
 */
public interface TelegramCommandHandler {
    
    /**
     * Check if this handler can process the given command.
     * @param command The command (e.g., "/daemon", "/start")
     * @return true if this handler can process it
     */
    boolean canHandle(String command);
    
    /**
     * Handle the command.
     * @param chatId The chat ID to respond to
     * @param args The command arguments (command itself is args[0])
     * @param context The command context for sending messages and accessing utilities
     */
    void handle(long chatId, String[] args, CommandContext context);
}
