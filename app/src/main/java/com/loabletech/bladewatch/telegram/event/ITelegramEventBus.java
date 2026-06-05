package com.overdrive.app.telegram.event;

/**
 * Event bus for decoupled communication between system components and Telegram bot.
 */
public interface ITelegramEventBus {
    
    /**
     * Event listener interface.
     */
    interface EventListener {
        void onEvent(SystemEvent event);
    }
    
    /**
     * Subscribe to all events.
     */
    void subscribe(EventListener listener);
    
    /**
     * Subscribe to specific event type.
     */
    void subscribe(SystemEvent.EventType type, EventListener listener);
    
    /**
     * Unsubscribe listener from all events.
     */
    void unsubscribe(EventListener listener);
    
    /**
     * Publish event to all subscribers.
     */
    void publish(SystemEvent event);
}
