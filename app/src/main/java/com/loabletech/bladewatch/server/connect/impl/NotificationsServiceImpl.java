package net.bladewatch.app.server.connect.impl;

import net.bladewatch.app.server.NotificationApiHandler;
import net.bladewatch.app.server.connect.ConnectDispatcher;
import net.bladewatch.app.server.connect.ConnectException;
import net.bladewatch.app.server.connect.ConnectHandlerUtil;

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

    private String handleGetCategories(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("GET", "/api/notifications/categories", null, out));
    }

    private String handleSubscribe(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("POST", "/api/push/subscribe", req, out));
    }

    private String handleUnsubscribe(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("POST", "/api/push/unsubscribe", req, out));
    }

    private String handleListSubscriptions(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("GET", "/api/push/subscriptions", null, out));
    }

    private String handleUpdatePreferences(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("POST", "/api/push/preferences", req, out));
    }

    private String handleSendTest(String req) throws ConnectException {
        return ConnectHandlerUtil.captureString(out ->
                NotificationApiHandler.handle("POST", "/api/push/test", req, out));
    }
}
