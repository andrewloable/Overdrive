package com.overdrive.app.notifications.sinks;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.CategoryRegistry;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.notifications.push.PushPayloadEncoder;
import com.overdrive.app.notifications.push.PushSubscription;
import com.overdrive.app.notifications.push.PushTransport;
import com.overdrive.app.notifications.push.SubscriptionStore;
import com.overdrive.app.notifications.push.VapidKeyStore;
import com.overdrive.app.notifications.push.VapidSigner;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The push sink. For each notification:
 *   - filters subscriptions by per-device preferences
 *   - signs a VAPID JWT scoped to the endpoint origin
 *   - encrypts the payload with aes128gcm
 *   - POSTs to the push service
 *   - removes the subscription on 404/410 (Gone)
 *
 * <p>Sends are dispatched to a small executor so a slow push service
 * doesn't back up the {@link NotificationBus} executor.
 */
public final class PushSink implements NotificationBus.Sink {

    private static final DaemonLogger logger = DaemonLogger.getInstance("PushSink");
    private static final int TTL_SECONDS = 86_400; // 24h

    private final SubscriptionStore subs;
    private final CategoryRegistry registry;
    private final VapidKeyStore keyStore;
    private final VapidSigner signer;

    // Lazy: the executor (and its 2 worker threads) only spin up when we
    // actually need to send a push. A car with no registered phones never
    // pays this cost — the early-return below short-circuits before this
    // is touched.
    private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>();

    public PushSink(SubscriptionStore subs, CategoryRegistry registry,
                    VapidKeyStore keyStore, VapidSigner signer) {
        this.subs = subs;
        this.registry = registry;
        this.keyStore = keyStore;
        this.signer = signer;
    }

    @Override
    public void onNotification(NotificationEvent event) {
        // Cheapest possible early-out: zero phones registered means no push
        // work, ever. Don't touch the registry, don't allocate, don't spin
        // up the executor.
        List<PushSubscription> all = subs.all();
        if (all.isEmpty()) return;

        CategoryRegistry.Entry meta = registry.get(event.category);
        if (meta == null) {
            logger.warn("dropping unregistered category: " + event.category);
            return;
        }
        // Resolve the click URL: event override > registry default. We only
        // allocate a wrapped event when an override is actually needed.
        final NotificationEvent enriched = event.clickUrl != null
                ? event
                : new NotificationEvent(event.category, event.severity, event.title, event.body,
                        event.tag, meta.defaultClickUrl, event.data);

        long now = System.currentTimeMillis();
        ExecutorService exec = null; // lazy
        // Categories may opt out of the quiet-hours block at the registry level
        // (e.g. charging complete — the whole point is to wake the user so they
        // unplug). CRITICAL severity also bypasses, as before.
        boolean categoryBypassesQuiet = meta.bypassQuietHours;
        for (PushSubscription sub : all) {
            if (sub.isMuted(event.category)) continue;
            if (event.severity.ordinal() < sub.minSeverity.ordinal()) continue;
            if (sub.inQuietHours(now)
                    && event.severity != NotificationEvent.Severity.CRITICAL
                    && !categoryBypassesQuiet) {
                continue;
            }
            if (exec == null) exec = executor();
            final PushSubscription target = sub;
            exec.execute(() -> sendOne(target, enriched));
        }
    }

    /**
     * Lazy executor: created on first eligible send. Kept around once
     * created — the same 2 threads service all subsequent pushes.
     */
    private ExecutorService executor() {
        ExecutorService e = executorRef.get();
        if (e != null) return e;
        ExecutorService created = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "PushSink");
            t.setDaemon(true);
            return t;
        });
        if (executorRef.compareAndSet(null, created)) return created;
        // lost the race — shut ours down
        created.shutdown();
        return executorRef.get();
    }

    private void sendOne(PushSubscription sub, NotificationEvent event) {
        try {
            JSONObject payload = event.toPayloadJson();
            byte[] plaintext = payload.toString().getBytes("UTF-8");

            PushPayloadEncoder.Encoded encoded =
                    PushPayloadEncoder.encrypt(plaintext, sub.p256dh, sub.auth);
            String jwt = signer.signFor(sub.endpoint);
            String pubKey = keyStore.publicKeyB64Url();

            // One retry for transient failures (5xx / 408 / 429). FCM occasionally
            // sheds load during real intrusion bursts — losing a single
            // notification at the moment the user cares most is the worst-case
            // failure mode, so we make a single bounded retry with the
            // server-suggested Retry-After when present.
            PushTransport.Result result = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                result = PushTransport.send(sub.endpoint, jwt, pubKey, encoded.body, TTL_SECONDS);

                if (result.expired()) {
                    logger.info("subscription expired (" + result.status + "), removing: " + sub.id);
                    subs.remove(sub.id);
                    return;
                }
                if (result.ok()) break;
                if (!result.transientFailure() || attempt == 1) break;

                long sleepMs = result.retryAfterSeconds > 0
                        ? Math.min(result.retryAfterSeconds * 1000L, 30_000L)
                        : 1500L;  // sensible default for unhinted 5xx
                try { Thread.sleep(sleepMs); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (result != null && !result.ok()) {
                logger.warn("push failed " + result.status + " for " + sub.id
                        + ": " + result.body);
                return;
            }
            sub.lastSeenAt = System.currentTimeMillis();
        } catch (Exception e) {
            logger.error("send failed for " + sub.id + ": " + e.getMessage());
        }
    }
}
