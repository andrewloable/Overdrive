package com.overdrive.app.notifications.sinks;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;

/**
 * Diagnostic sink — writes every notification to the daemon log so we can
 * see what the bus is carrying without depending on push delivery.
 */
public final class LogSink implements NotificationBus.Sink {

    private static final DaemonLogger logger = DaemonLogger.getInstance("NotificationBus");

    @Override
    public void onNotification(NotificationEvent event) {
        logger.info("notification "
                + event.severity.name() + " "
                + event.category + " "
                + (event.tag == null ? "" : "[" + event.tag + "] ")
                + event.title
                + (event.body.isEmpty() ? "" : " — " + event.body));
    }
}
