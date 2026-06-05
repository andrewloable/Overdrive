package com.overdrive.app.telegram.impl;

import android.content.Context;

import androidx.annotation.Nullable;

import com.overdrive.app.telegram.IOwnerStore;
import com.overdrive.app.telegram.config.UnifiedTelegramConfig;
import com.overdrive.app.telegram.model.NotificationPreferences;
import com.overdrive.app.telegram.model.OwnerInfo;

/**
 * Owner data + notification preferences backed by {@link UnifiedTelegramConfig}.
 *
 * Both the app and the daemon read from the same JSON section, so a /pair
 * command processed in the daemon process is immediately visible to the app
 * UI (and vice-versa) without any cross-store reconciliation.
 *
 * The {@link Context} parameter is kept for API stability but is no longer
 * used; the unified config is process-global.
 */
public class OwnerStore implements IOwnerStore {

    @SuppressWarnings("unused")
    public OwnerStore(Context context) {
    }

    @Override
    public void saveOwner(OwnerInfo owner) {
        UnifiedTelegramConfig.setOwner(
                owner.getChatId(),
                owner.getUsername(),
                owner.getFirstName(),
                owner.getPairedAt()
        );
    }

    @Override
    @Nullable
    public OwnerInfo getOwner() {
        long chatId = UnifiedTelegramConfig.getOwnerChatId();
        if (chatId <= 0) return null;
        return new OwnerInfo(
                chatId,
                UnifiedTelegramConfig.getOwnerUsername(),
                UnifiedTelegramConfig.getOwnerFirstName(),
                UnifiedTelegramConfig.getOwnerPairedAt()
        );
    }

    @Override
    public boolean hasOwner() {
        return UnifiedTelegramConfig.hasOwner();
    }

    @Override
    public void clearOwner() {
        UnifiedTelegramConfig.clearOwner();
    }

    @Override
    public void savePreferences(NotificationPreferences p) {
        UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_CRITICAL_ALERTS,
                p.isCriticalAlerts());
        UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_CONNECTIVITY,
                p.isConnectivityUpdates());
        UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_MOTION_TEXT,
                p.isMotionText());
        UnifiedTelegramConfig.setBoolean(UnifiedTelegramConfig.K_VIDEO_UPLOADS,
                p.isVideoUploads());
    }

    @Override
    public NotificationPreferences getPreferences() {
        return new NotificationPreferences(
                UnifiedTelegramConfig.isCriticalAlerts(),
                UnifiedTelegramConfig.isConnectivity(),
                UnifiedTelegramConfig.isMotionText(),
                UnifiedTelegramConfig.isVideoUploads()
        );
    }
}
