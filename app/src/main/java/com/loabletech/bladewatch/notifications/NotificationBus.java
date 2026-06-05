package com.overdrive.app.notifications;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-process pub/sub for {@link NotificationEvent}s. Mirrors the shape of
 * {@code TelegramEventBus} so the patterns line up.
 *
 * <p>v1 is in-process only (publishers and sinks both live in CameraDaemon).
 * If a future emit source lives in another process, an
 * {@code NotificationIpcServer} can sit in front of this and forward.
 */
public final class NotificationBus {

    public interface Sink {
        void onNotification(NotificationEvent event);
    }

    private static final NotificationBus INSTANCE = new NotificationBus();

    private final CopyOnWriteArrayList<Sink> sinks = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NotificationBus");
        t.setDaemon(true);
        return t;
    });

    private NotificationBus() {}

    public static NotificationBus get() { return INSTANCE; }

    public void subscribe(Sink sink) {
        if (sink != null && !sinks.contains(sink)) sinks.add(sink);
    }

    public void unsubscribe(Sink sink) {
        sinks.remove(sink);
    }

    public void publish(NotificationEvent event) {
        if (event == null) return;
        // Fast path: zero sinks means no work would happen anyway. Skip the
        // executor hop so emit-side callers (surveillance, tyre) don't pay
        // JSON / dispatch cost when notifications aren't even initialized.
        if (sinks.isEmpty()) return;
        try {
            executor.execute(() -> {
                for (Sink s : sinks) {
                    try {
                        s.onNotification(event);
                    } catch (Throwable t) {
                        // never let one sink kill the others
                        System.err.println("NotificationBus: sink error: " + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            // RejectedExecutionException at shutdown — same defensive style as TelegramEventBus
            System.err.println("NotificationBus: publish failed: " + t.getMessage());
        }
    }
}
