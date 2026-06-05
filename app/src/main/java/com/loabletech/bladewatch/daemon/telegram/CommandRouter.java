package com.overdrive.app.daemon.telegram;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes commands to appropriate handlers.
 */
public class CommandRouter {
    
    private final List<TelegramCommandHandler> handlers = new ArrayList<>();
    private final CommandContext context;
    
    public CommandRouter(CommandContext context) {
        this.context = context;
        
        // Register handlers
        handlers.add(new DaemonCommandHandler());
        handlers.add(new SurveillanceCommandHandler());
        handlers.add(new SystemCommandHandler());
        handlers.add(new EventCommandHandler());
        handlers.add(new UpdateCommandHandler());
    }
    
    /**
     * Route a command to the appropriate handler.
     * @param chatId The chat ID
     * @param text The full command text (e.g., "/daemon acc stop")
     * @return true if command was handled
     */
    public boolean route(long chatId, String text) {
        if (text == null || text.isEmpty() || !text.startsWith("/")) {
            return false;
        }
        
        String[] args = text.split("\\s+");
        String command = args[0].toLowerCase();
        
        for (TelegramCommandHandler handler : handlers) {
            if (handler.canHandle(command)) {
                handler.handle(chatId, args, context);
                return true;
            }
        }
        
        // Unknown command
        context.sendMessage(chatId, "Unknown command. Use /help for available commands.");
        return true;
    }
}
