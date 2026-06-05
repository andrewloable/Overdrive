package com.overdrive.app.byd;

import org.json.JSONObject;

/**
 * Callback interface for broadcasting BYD events
 */
public interface EventCallback {
    void onEvent(JSONObject event);
}
