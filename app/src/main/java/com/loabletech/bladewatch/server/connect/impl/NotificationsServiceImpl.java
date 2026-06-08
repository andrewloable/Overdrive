package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.NotificationApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;
import net.bladewatch.app.server.connect.ConnectResponse;

/**
 * Connect protocol handler for bladewatch.v1.NotificationsService.
 *
 * Routes:
 *   /bladewatch.v1.NotificationsService/GetCategories      → GET  /api/notifications/categories
 *   /bladewatch.v1.NotificationsService/Subscribe          → POST /api/push/subscribe
 *   /bladewatch.v1.NotificationsService/Unsubscribe        → POST /api/push/unsubscribe
 *   /bladewatch.v1.NotificationsService/ListSubscriptions  → GET  /api/push/subscriptions
 *   /bladewatch.v1.NotificationsService/UpdatePreferences  → POST /api/push/preferences
 *   /bladewatch.v1.NotificationsService/SendTest           → POST /api/push/test
 */
public class NotificationsServiceImpl {

    public void register(ConnectDispatcher dispatcher) {
        dispatcher.register("bladewatch.v1.NotificationsService", "GetCategories",
                this::handleGetCategories);
        dispatcher.register("bladewatch.v1.NotificationsService", "Subscribe",
                this::handleSubscribe);
        dispatcher.register("bladewatch.v1.NotificationsService", "Unsubscribe",
                this::handleUnsubscribe);
        dispatcher.register("bladewatch.v1.NotificationsService", "ListSubscriptions",
                this::handleListSubscriptions);
        dispatcher.register("bladewatch.v1.NotificationsService", "UpdatePreferences",
                this::handleUpdatePreferences);
        dispatcher.register("bladewatch.v1.NotificationsService", "SendTest",
                this::handleSendTest);
    }

    private ConnectResponse handleGetCategories(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("GET", "/api/notifications/categories", null, out));
    }

    private ConnectResponse handleSubscribe(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("POST", "/api/push/subscribe", req, out));
    }

    private ConnectResponse handleUnsubscribe(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("POST", "/api/push/unsubscribe", req, out));
    }

    private ConnectResponse handleListSubscriptions(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("GET", "/api/push/subscriptions", null, out));
    }

    private ConnectResponse handleUpdatePreferences(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("POST", "/api/push/preferences", req, out));
    }

    private ConnectResponse handleSendTest(String req, String clientIdentity) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("POST", "/api/push/test", req, out));
    }
}
