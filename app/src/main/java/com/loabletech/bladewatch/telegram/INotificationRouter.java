package com.overdrive.app.telegram;

import com.overdrive.app.telegram.event.SystemEvent;

/**
 * Routes system events to Telegram notifications based on owner preferences.
 */
public interface INotificationRouter {
    
    /**
     * Handle incoming system event.
     * Routes to appropriate notification method based on event type and preferences.
     */
    void onEvent(SystemEvent event);
    
    /**
     * Send text message to owner.
     * @param message text to send
     * @return true if sent successfully
     */
    boolean sendTextMessage(String message);
    
    /**
     * Send video to owner with caption.
     * @param videoPath path to video file
     * @param caption optional caption
     * @return true if sent successfully
     */
    boolean sendVideo(String videoPath, String caption);
}
