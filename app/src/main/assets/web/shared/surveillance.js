/**
 * BYD Champ - Surveillance Settings Module
 * SOTA: Uses unified config for cross-UID access (app UI + web UI sync)
 * SOTA: Storage limits with auto-cleanup (100MB - 100GB internal/SD card)
 * SOTA: Storage type selection (internal vs SD card)
 */

window.BYD = window.BYD || {};

BYD.surveillance = {
    config: {
        enabled: false,
        distance: 3,
        sensitivity: 3,
        minObjectSize: 0.08,
        flashImmunity: 2,
        detectPerson: true,
        detectCar: true,
        detectBike: true,
        preRecordSeconds: 5,
        postRecordSeconds: 10,
        // Single recording quality tier (replaces legacy recordingBitrate
        // LOW/MEDIUM/HIGH). Server rebuilds the encoder live on change.
        recordingQuality: 'STANDARD',
        recordingCodec: 'H264',
        // Server-supplied for UI (filled by load):
        recordingQualityOptions: {},
        activeRecordingEstimate: null,
        surveillanceLimitMb: 500,
        surveillanceStorageType: 'INTERNAL',
        // V2 Motion Detection
        environmentPreset: 'outdoor',
        sensitivityLevel: 3,
        detectionZone: 'normal',
        loiteringTime: 3,
        shadowFilter: 2,
        cameraFront: true,
        cameraRight: true,
        cameraLeft: true,
        cameraRear: true,
        motionHeatmap: false,
        filterDebugLog: false,
        // Per-quadrant sensitivity / zone overrides. Keys: Q0=front, Q1=right,
        // Q2=rear, Q3=left. Absent key = inherit global. The "Side-cam Boost"
        // UI writes to Q1 + Q3.
        quadrantOverrides: {},
        // Telegram-specific: send a "Recording in progress…" text ping at
        // recording start. OFF by default so users with both PWA + Telegram
        // see one notification per event (PWA has tag-replace; Telegram does
        // not). Telegram-only users may turn this on for low-latency awareness.
        telegramSendStartPing: false,
        // Per-tier Telegram filter — mirrors the push tier toggles. Defaults
        // match: NOTICE off (background noise), ALERT + CRITICAL on.
        telegramNotices: false,
        telegramAlerts: true,
        telegramCritical: true
    },
    storageInfo: {
        sdCardAvailable: false,
        sdCardPath: null,
        sdCardFreeSpace: 0,
        sdCardTotalSpace: 0,
        maxLimitMb: 100000,
        maxLimitMbSdCard: 100000
    },
    cdrInfo: null,
    cdrConfig: {
        enabled: false,
        reservedSpaceMb: 2000,
        protectedHours: 24,
        minFilesKeep: 10
    },
    savedConfig: null,
    hasUnsavedChanges: false,
    lastConfigTimestamp: 0,  // Track config file timestamp for sync

    distanceMap: {
        1: { size: 0.25 },
        2: { size: 0.18 },
        3: { size: 0.12 },
        4: { size: 0.08 },
        5: { size: 0.05 }
    },
    distanceLabel(v) { return BYD.i18n.t('surveillance.distance.' + v + '_label'); },
    distanceHint(v) { return BYD.i18n.t('surveillance.distance.' + v + '_hint'); },

    // Sensitivity controls motion detection thresholds (requiredBlocks + densityThreshold)
    // Block size is LOCKED at 32 - only density and required count vary
    sensitivityMap: {
        1: { required: 4, density: 48 },
        2: { required: 3, density: 40 },
        3: { required: 2, density: 32 },
        4: { required: 2, density: 16 },
        5: { required: 1, density: 12 }
    },
    sensitivityLabel(v) { return BYD.i18n.t('surveillance.sens.' + v + '_label'); },
    sensitivityHint(v) { return BYD.i18n.t('surveillance.sens.' + v + '_hint'); },

    flashImmunityMap: { 0: 'SENSITIVE', 1: 'NORMAL', 2: 'STRICT', 3: 'MAX' },

    // V2 sensitivity level labels — looked up via BYD.i18n
    v2SensLabel(v) { return BYD.i18n.t('surveillance.v2_sens_label.' + v); },
    v2SensHint(v) { return BYD.i18n.t('surveillance.v2_sens_hint.' + v); },

    // V2 environment presets: { sensitivityLevel, detectionZone, loiteringTime, shadowFilter }
    v2Presets: {
        outdoor:  { sensitivityLevel: 3, detectionZone: 'normal', loiteringTime: 3, shadowFilter: 2 },
        garage:   { sensitivityLevel: 4, detectionZone: 'close',  loiteringTime: 2, shadowFilter: 1 },
        street:   { sensitivityLevel: 3, detectionZone: 'normal', loiteringTime: 5, shadowFilter: 3 }
    },

    async init() {
        await this.loadConfig();
        await this.loadStorageStats();
        await this.loadCameraFps();
        this.savedConfig = JSON.parse(JSON.stringify(this.config));
        this.updateUI();
        this.startClock();

        // Telegram pairing state — drives the tier filter availability UI.
        // Re-checked when reloadConfig() fires (visibility change, periodic
        // refresh) so a freshly-paired bot un-greys the toggles live.
        this.refreshTelegramAvailability();
        
        // Load BYD Cloud status
        if (window.BydCloud) {
            BydCloud.loadStatus();
        }
        
        // Show CDR cleanup card if SD card is selected on load
        this.updateCdrCleanupVisibility();
        
        // Auto-start heatmap if enabled in config and video display area exists
        if (this.config.motionHeatmap) {
            this.startHeatmap();
        }
        
        // Reload config when page becomes visible (user switches back to tab)
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && !this.hasUnsavedChanges) {
                this.reloadConfig();
            }
            // Stop heatmap polling when page is hidden
            if (document.visibilityState === 'hidden' && this._heatmapInterval) {
                this.stopHeatmap();
                this._heatmapWasRunning = true;
            }
            // Restart heatmap when page becomes visible again
            if (document.visibilityState === 'visible' && this._heatmapWasRunning) {
                this._heatmapWasRunning = false;
                if (this.config.motionHeatmap) {
                    this.startHeatmap();
                }
            }
        });

        // Re-evaluate Apply enabled-state + unsaved markers whenever the
        // user swaps bottom tabs — markChanged() reads the active tab id
        // each call, so the visible button reflects only the visible tab.
        var self = this;
        document.addEventListener('ot-tabs:active-changed', function () {
            self.markChanged();
        });
    },
    
    async reloadConfig() {
        // Don't reload if user has unsaved changes
        if (this.hasUnsavedChanges) return;
        
        try {
            const resp = await fetch('/api/surveillance/config');
            const data = await resp.json();
            if (data.success && data.config) {
                // Check if config actually changed (via timestamp)
                const newTimestamp = data.config.lastModified || 0;
                if (newTimestamp > this.lastConfigTimestamp) {
                    this.config = { ...this.config, ...data.config };
                    // Use server-provided distance/sensitivity if available
                    if (!data.config.distance) {
                        this.config.distance = this.sizeToDistance(this.config.minObjectSize || 0.08);
                    }
                    if (!data.config.sensitivity) {
                        this.config.sensitivity = 3;  // Default
                    }
                    this.lastConfigTimestamp = newTimestamp;
                    
                    // Load storage settings BEFORE setting savedConfig
                    // so both config and savedConfig include the same storage values
                    await this.loadStorageSettings();
                    
                    this.savedConfig = JSON.parse(JSON.stringify(this.config));
                    this.updateUI();
                }
            }
        } catch (e) {
            console.warn('Failed to reload config:', e);
        }
        // Re-probe Telegram pairing on every reload — newly-paired bots
        // should un-grey the tier toggles without a page refresh.
        this.refreshTelegramAvailability();
    },
    
    async loadStorageSettings() {
        try {
            const resp = await fetch('/api/settings/storage');
            const data = await resp.json();
            if (data.success) {
                this.config.surveillanceLimitMb = data.surveillanceLimitMb || 500;
                this.config.surveillanceStorageType = data.surveillanceStorageType || 'INTERNAL';
                
                // Update storage info
                this.storageInfo.sdCardAvailable = data.sdCardAvailable || false;
                this.storageInfo.sdCardPath = data.sdCardPath || null;
                this.storageInfo.sdCardFreeSpace = data.sdCardFreeSpace || 0;
                this.storageInfo.sdCardTotalSpace = data.sdCardTotalSpace || 0;
                this.storageInfo.maxLimitMb = data.maxLimitMb || 100000;
                this.storageInfo.maxLimitMbSdCard = data.maxLimitMbSdCard || 100000;
                this.storageInfo.surveillancePath = data.surveillancePath || '';
                
                this.updateStorageLimitUI();
                this.updateStorageTypeUI();
            }
        } catch (e) {
            console.warn('Failed to load storage settings:', e);
        }
    },
    
    async loadStorageStats() {
        try {
            const resp = await fetch('/api/recordings/stats');
            const data = await resp.json();
            if (data.success) {
                const usedEl = document.getElementById('survStorageUsed');
                const limitEl = document.getElementById('survStorageLimit');
                const fillEl = document.getElementById('survStorageFill');
                
                if (usedEl) usedEl.textContent = BYD.i18n.t('surveillance.size_used', {size: data.sentrySizeFormatted});

                const limitMb = this.config.surveillanceLimitMb || 500;
                if (limitEl) limitEl.textContent = BYD.i18n.t('surveillance.size_limit_mb', {mb: limitMb});
                
                // Calculate percentage
                const usedBytes = data.sentrySize || 0;
                const limitBytes = limitMb * 1024 * 1024;
                const percent = Math.min(100, Math.round(usedBytes * 100 / limitBytes));
                if (fillEl) fillEl.style.width = percent + '%';
                
                // Update Events Today count
                const eventsTodayEl = document.getElementById('eventsToday');
                if (eventsTodayEl) {
                    const todayCount = data.sentryTodayCount || 0;
                    eventsTodayEl.textContent = todayCount + ' →';
                }
            }
        } catch (e) {
            console.warn('Failed to load storage stats:', e);
        }
    },
    
    async loadCameraFps() {
        try {
            const resp = await fetch('/api/settings/quality');
            const data = await resp.json();
            if (data.success) {
                this.config.cameraFps = data.cameraFps || 15;
                this.config.cameraFpsActual = data.cameraFpsActual || null;
                this.config.cameraFpsClampNote = data.cameraFpsClampNote || null;
                // Pull tier + render data so the picker can show live
                // size estimates without waiting for the next reload.
                if (data.recordingQuality) {
                    this.config.recordingQuality = data.recordingQuality;
                }
                this.config.recordingQualityOptions = data.recordingQualityOptions || {};
                this.config.activeRecordingEstimate = data.activeRecordingEstimate || null;
            }
        } catch (e) {
            console.warn('Failed to load camera FPS:', e);
            this.config.cameraFps = 15;
        }
    },
    
    async setFps(fps) {
        this.config.cameraFps = fps;
        document.querySelectorAll('#survFpsBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === String(fps)));

        // Save immediately via quality API (shared with recording page)
        try {
            await fetch('/api/settings/quality', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ cameraFps: fps })
            });
            if (BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(BYD.i18n.t('surveillance.fps_set', {fps: fps}), 'success');
            }
            // Refetch the per-tier options so qualityEquivalent (which shifts
            // with FPS) and the active-tier subtitle reflect the new rate.
            this.refetchQualityOptions();
        } catch (e) {
            console.error('Failed to save FPS:', e);
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('surveillance.fps_save_failed'), 'error');
        }
    },

    /** Pull a fresh /api/settings/quality and update the local tier table +
     *  active-estimate display. Called after a save that affects per-tier
     *  metadata (codec or fps). */
    async refetchQualityOptions() {
        try {
            const r = await fetch('/api/settings/quality');
            if (!r.ok) return;
            const data = await r.json();
            if (data && data.recordingQualityOptions) {
                this.config.recordingQualityOptions = data.recordingQualityOptions;
                this.renderActiveEstimate();
            }
        } catch (e) { /* best-effort */ }
    },
    
    updateStorageLimitUI() {
        const slider = document.getElementById('survLimitSlider');
        const value = document.getElementById('survLimitValue');
        
        // Update slider max based on storage type
        const maxLimit = this.config.surveillanceStorageType === 'SD_CARD' 
            ? this.storageInfo.maxLimitMbSdCard 
            : this.storageInfo.maxLimitMb;
        
        if (slider) {
            slider.max = maxLimit;
            slider.value = Math.min(this.config.surveillanceLimitMb, maxLimit);
        }
        if (value) {
            const mb = this.config.surveillanceLimitMb;
            value.textContent = mb >= 1000 ? (mb / 1000) + ' GB' : mb + ' MB';
        }
        
        // Update range labels
        const minLabel = document.getElementById('survLimitMin');
        const maxLabel = document.getElementById('survLimitMax');
        if (minLabel) minLabel.textContent = BYD.i18n.t('surveillance.limit_min_default');
        if (maxLabel) maxLabel.textContent = maxLimit >= 1000 ? (maxLimit / 1000) + ' GB' : maxLimit + ' MB';
    },
    
    updateStorageTypeUI() {
        // Update storage type buttons
        document.querySelectorAll('#survStorageTypeBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === this.config.surveillanceStorageType));
        
        // Update SD card button state
        const sdCardBtn = document.getElementById('btnSurvSdCard');
        if (sdCardBtn) {
            sdCardBtn.disabled = !this.storageInfo.sdCardAvailable;
            if (!this.storageInfo.sdCardAvailable) {
                sdCardBtn.title = BYD.i18n.t('recording.sd_card_unavailable');
            } else {
                sdCardBtn.title = '';
            }
        }
        
        // Show/hide SD card status
        const statusEl = document.getElementById('survSdCardStatus');
        if (statusEl) {
            statusEl.style.display = 'block';
            
            const dotEl = document.getElementById('survSdStatusDot');
            const textEl = document.getElementById('survSdStatusText');
            const spaceEl = document.getElementById('survSdSpaceInfo');
            
            if (this.storageInfo.sdCardAvailable) {
                if (dotEl) dotEl.className = 'sd-status-dot online';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.sd_card_available');
                if (spaceEl) {
                    spaceEl.style.display = 'block';
                    document.getElementById('survSdFree').textContent = BYD.i18n.t('recording.size_free', {size: this.formatSize(this.storageInfo.sdCardFreeSpace)});
                    document.getElementById('survSdTotal').textContent = BYD.i18n.t('recording.size_total', {size: this.formatSize(this.storageInfo.sdCardTotalSpace)});
                }
            } else {
                if (dotEl) dotEl.className = 'sd-status-dot offline';
                if (textEl) textEl.textContent = BYD.i18n.t('recording.sd_card_not_detected');
                if (spaceEl) spaceEl.style.display = 'none';
            }
        }

        // Update storage path display
        const pathEl = document.getElementById('survStoragePath');
        if (pathEl && this.storageInfo.surveillancePath) {
            const shortPath = this.storageInfo.surveillancePath.replace('/storage/emulated/0/', '');
            pathEl.textContent = BYD.i18n.t('surveillance.events_saved_to', {path: shortPath});
        }
    },
    
    formatSize(bytes) {
        if (bytes >= 1000000000) return (bytes / 1000000000).toFixed(1) + ' GB';
        if (bytes >= 1000000) return (bytes / 1000000).toFixed(1) + ' MB';
        if (bytes >= 1000) return (bytes / 1000).toFixed(1) + ' KB';
        return bytes + ' B';
    },
    
    setStorageType(type) {
        if (type === 'SD_CARD' && !this.storageInfo.sdCardAvailable) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.sd_card_unavailable'), 'error');
            return;
        }
        
        this.config.surveillanceStorageType = type;
        document.querySelectorAll('#survStorageTypeBtns .btn-toggle').forEach(btn => 
            btn.classList.toggle('active', btn.dataset.value === type));
        
        // Update slider max when storage type changes
        this.updateStorageLimitUI();
        
        // Show/hide CDR cleanup card
        this.updateCdrCleanupVisibility();
        
        this.markChanged();
        var _su = document.getElementById('storageUnsaved'); if (_su) _su.classList.add('show');
    },
    
    // ==================== CDR Cleanup ====================
    
    async loadCdrConfig() {
        try {
            const resp = await fetch('/api/storage/external');
            const data = await resp.json();
            if (data.success) {
                this.cdrConfig.enabled = data.cleanupEnabled || false;
                this.cdrConfig.reservedSpaceMb = data.reservedSpaceMb || 2000;
                this.cdrConfig.protectedHours = data.protectedHours || 24;
                this.cdrConfig.minFilesKeep = data.minFilesKeep || 10;
                
                // Store CDR info
                this.cdrInfo = {
                    cdrPath: data.cdrPath,
                    cdrUsage: data.cdrUsageFormatted,
                    cdrFileCount: data.cdrFileCount,
                    cdrProtected: data.cdrProtectedFormatted,
                    cdrDeletable: data.cdrDeletableFormatted,
                    totalFreed: data.totalBytesFreedFormatted,
                    totalDeleted: data.totalFilesDeleted,
                    monitoringActive: !!data.monitoringActive,
                    lastCleanupTime: data.lastCleanupTime || 0,
                    recommendAutoCleanup: !!data.recommendAutoCleanup
                };

                this.updateCdrUI();
            }
        } catch (e) {
            console.warn('Failed to load CDR config:', e);
        }
    },
    
    updateCdrCleanupVisibility() {
        const card = document.getElementById('cdrCleanupCard');
        if (card) {
            const showCard = this.config.surveillanceStorageType === 'SD_CARD' && this.storageInfo.sdCardAvailable;
            card.style.display = showCard ? 'block' : 'none';
            
            if (showCard) {
                this.loadCdrConfig();
            }
        }
    },
    
    updateCdrUI() {
        // Update toggle
        const toggle = document.getElementById('cdrCleanupEnabled');
        if (toggle) toggle.checked = this.cdrConfig.enabled;
        
        // Update badge
        const badge = document.getElementById('cdrCleanupBadge');
        if (badge) {
            badge.textContent = this.cdrConfig.enabled ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
            badge.className = 'status-badge ' + (this.cdrConfig.enabled ? 'active' : 'inactive');
        }
        
        // Update sliders
        const reservedSlider = document.getElementById('cdrReservedSlider');
        const reservedValue = document.getElementById('cdrReservedValue');
        if (reservedSlider) reservedSlider.value = this.cdrConfig.reservedSpaceMb;
        if (reservedValue) reservedValue.textContent = this.cdrConfig.reservedSpaceMb >= 1000 ? 
            (this.cdrConfig.reservedSpaceMb / 1000) + ' GB' : this.cdrConfig.reservedSpaceMb + ' MB';
        
        const protectedSlider = document.getElementById('cdrProtectedSlider');
        const protectedValue = document.getElementById('cdrProtectedValue');
        if (protectedSlider) protectedSlider.value = this.cdrConfig.protectedHours;
        if (protectedValue) protectedValue.textContent = this.cdrConfig.protectedHours + 'h';
        
        const minKeepSlider = document.getElementById('cdrMinKeepSlider');
        const minKeepValue = document.getElementById('cdrMinKeepValue');
        if (minKeepSlider) minKeepSlider.value = this.cdrConfig.minFilesKeep;
        if (minKeepValue) minKeepValue.textContent = this.cdrConfig.minFilesKeep;
        
        // Update info
        if (this.cdrInfo) {
            const pathEl = document.getElementById('cdrPath');
            if (pathEl) pathEl.textContent = this.cdrInfo.cdrPath || BYD.i18n.t('surveillance.not_found');

            const usageEl = document.getElementById('cdrUsage');
            if (usageEl) usageEl.textContent = this.cdrInfo.cdrUsage || '--';

            const countEl = document.getElementById('cdrFileCount');
            if (countEl) countEl.textContent = this.cdrInfo.cdrFileCount || '0';

            const protEl = document.getElementById('cdrProtected');
            if (protEl) protEl.textContent = this.cdrInfo.cdrProtected || '--';

            const deletableEl = document.getElementById('cdrDeletable');
            if (deletableEl) deletableEl.textContent = this.cdrInfo.cdrDeletable || '--';

            const monEl = document.getElementById('cdrMonitoring');
            if (monEl) {
                if (!this.cdrConfig.enabled) {
                    monEl.textContent = BYD.i18n.t('common.disabled');
                    monEl.style.color = '';
                } else if (this.cdrInfo.monitoringActive) {
                    monEl.textContent = BYD.i18n.t('common.running');
                    monEl.style.color = '#22c55e';
                } else {
                    monEl.textContent = BYD.i18n.t('common.idle');
                    monEl.style.color = '#94a3b8';
                }
            }

            const lastEl = document.getElementById('cdrLastCleanup');
            if (lastEl) lastEl.textContent = this._formatRelativeTime(this.cdrInfo.lastCleanupTime);

            const banner = document.getElementById('cdrRecommendBanner');
            if (banner) banner.style.display = this.cdrInfo.recommendAutoCleanup ? 'block' : 'none';

            const freedEl = document.getElementById('cdrTotalFreed');
            if (freedEl) freedEl.textContent = this.cdrInfo.totalFreed || '0 B';

            const deletedEl = document.getElementById('cdrTotalDeleted');
            if (deletedEl) deletedEl.textContent = this.cdrInfo.totalDeleted || '0';
        }
    },

    _formatRelativeTime(ts) {
        if (!ts || ts <= 0) return BYD.i18n.t('recording.never');
        const diffSec = Math.floor((Date.now() - ts) / 1000);
        if (diffSec < 0) return BYD.i18n.t('recording.just_now');
        if (diffSec < 60) return BYD.i18n.t('recording.seconds_ago', {n: diffSec});
        if (diffSec < 3600) return BYD.i18n.t('recording.minutes_ago', {n: Math.floor(diffSec / 60)});
        if (diffSec < 86400) return BYD.i18n.t('recording.hours_ago', {n: Math.floor(diffSec / 3600)});
        return BYD.i18n.t('recording.days_ago', {n: Math.floor(diffSec / 86400)});
    },
    
    async toggleCdrCleanup() {
        const enabled = document.getElementById('cdrCleanupEnabled').checked;
        try {
            await fetch('/api/storage/external/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled })
            });
            this.cdrConfig.enabled = enabled;
            this.updateCdrUI();
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(enabled ? BYD.i18n.t('recording.cdr_enabled') : BYD.i18n.t('recording.cdr_disabled'), 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_toggle_failed'), 'error');
        }
    },
    
    updateCdrReserved(value) {
        this.cdrConfig.reservedSpaceMb = parseInt(value);
        const el = document.getElementById('cdrReservedValue');
        if (el) el.textContent = value >= 1000 ? (value / 1000) + ' GB' : value + ' MB';
        this.saveCdrConfig();
    },
    
    updateCdrProtected(value) {
        this.cdrConfig.protectedHours = parseInt(value);
        const el = document.getElementById('cdrProtectedValue');
        if (el) el.textContent = value + 'h';
        this.saveCdrConfig();
    },
    
    updateCdrMinKeep(value) {
        this.cdrConfig.minFilesKeep = parseInt(value);
        const el = document.getElementById('cdrMinKeepValue');
        if (el) el.textContent = value;
        this.saveCdrConfig();
    },
    
    async saveCdrConfig() {
        try {
            await fetch('/api/storage/external/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    reservedSpaceMb: this.cdrConfig.reservedSpaceMb,
                    protectedHours: this.cdrConfig.protectedHours,
                    minFilesKeep: this.cdrConfig.minFilesKeep
                })
            });
        } catch (e) {
            console.warn('Failed to save CDR config:', e);
        }
    },
    
    async triggerCdrCleanup() {
        try {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_cleaning'), 'info');
            
            const resp = await fetch('/api/storage/external/cleanup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            
            if (data.success) {
                const msg = data.filesDeleted > 0
                    ? BYD.i18n.t('recording.cdr_freed', {size: data.freedFormatted, files: data.filesDeleted})
                    : BYD.i18n.t('recording.cdr_no_cleanup');
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');
                
                // Refresh CDR info
                this.loadCdrConfig();
            } else {
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(data.error || BYD.i18n.t('recording.cdr_cleanup_failed'), 'error');
            }
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.cdr_trigger_failed'), 'error');
        }
    },
    
    updateSurvLimit(value) {
        this.config.surveillanceLimitMb = parseInt(value);
        const v = parseInt(value);
        document.getElementById('survLimitValue').textContent = v >= 1000 ? (v / 1000) + ' GB' : v + ' MB';
        this.markChanged();
        var _su = document.getElementById('storageUnsaved'); if (_su) _su.classList.add('show');
    },

    startClock() {
        const update = () => {
            const el = document.getElementById('currentTime');
            if (el) {
                el.textContent = new Date().toLocaleTimeString(BYD.i18n.getLang(), {
                    hour: '2-digit',
                    minute: '2-digit',
                    hour12: false
                });
            }
        };
        update();
        setInterval(update, 1000);
        
        // Config refresh (every 10s) to catch external changes (Telegram, IPC)
        setInterval(() => {
            if (!this.hasUnsavedChanges) {
                this.reloadConfig();
            }
            this.loadStorageStats();
            
            // Refresh CDR info if SD card is selected
            if (this.config.surveillanceStorageType === 'SD_CARD' && this.storageInfo.sdCardAvailable) {
                this.loadCdrConfig();
            }
        }, 10000);
    },

    async loadConfig() {
        try {
            const resp = await fetch('/api/surveillance/config');
            const data = await resp.json();
            if (data.success && data.config) {
                this.config = { ...this.config, ...data.config };
                // Use server-provided distance/sensitivity if available, otherwise calculate from minObjectSize
                if (!data.config.distance) {
                    this.config.distance = this.sizeToDistance(this.config.minObjectSize || 0.08);
                }
                if (!data.config.sensitivity) {
                    this.config.sensitivity = 3;  // Default
                }
                this.lastConfigTimestamp = data.config.lastModified || Date.now();
            }
        } catch (e) {
            console.warn('Failed to load config:', e);
        }
        
        // Load storage settings
        await this.loadStorageSettings();
    },

    sizeToDistance(size) {
        if (size >= 0.22) return 1;
        if (size >= 0.15) return 2;
        if (size >= 0.10) return 3;
        if (size >= 0.06) return 4;
        return 5;
    },

    /**
     * Per-tab dirty diff. The Apply button is enabled only when the
     * CURRENTLY VISIBLE tab has uncommitted edits, and the per-card
     * "● unsaved" markers each reflect their own tab's dirty state.
     * Switching tabs re-runs the diff so the bar reflects what the user
     * is looking at, not the page-wide aggregate.
     */
    _tabDirty: function () {
        if (!this.savedConfig) return {};
        var dirty = {};
        var map = this._tabFieldMap || {};
        var tabIds = Object.keys(map);
        for (var t = 0; t < tabIds.length; t++) {
            var tabId = tabIds[t];
            var fields = map[tabId];
            var d = false;
            for (var i = 0; i < fields.length; i++) {
                var k = fields[i];
                if (k === '_storage_endpoint') continue;
                if (JSON.stringify(this.config[k]) !== JSON.stringify(this.savedConfig[k])) {
                    d = true; break;
                }
            }
            dirty[tabId] = d;
        }
        return dirty;
    },

    markChanged() {
        var dirtyByTab = this._tabDirty();
        // Aggregate flag — used by reloadConfig() / visibility refresh to
        // decide whether to skip a server pull. True if ANY tab is dirty.
        this.hasUnsavedChanges = false;
        for (var k in dirtyByTab) {
            if (dirtyByTab[k]) { this.hasUnsavedChanges = true; break; }
        }
        this._dirtyByTab = dirtyByTab;

        // Apply button reflects the active tab only.
        var btn = document.getElementById('btnApply');
        if (btn) {
            var activeTab = this._activeTabId();
            var activeIsDirty = !!dirtyByTab[activeTab];
            btn.disabled = !activeIsDirty;
            btn.classList.toggle('has-changes', activeIsDirty);
        }

        // Each "● unsaved" marker maps 1:1 to a tab — show only when its
        // own tab is dirty so cards on a clean tab don't flash markers
        // because of edits on another tab.
        var markerTabMap = {
            'detectionUnsaved': 'detection',
            'recordingUnsaved': 'recording',
            'storageUnsaved':   'storage'
        };
        for (var elId in markerTabMap) {
            var el = document.getElementById(elId);
            if (el) el.classList.toggle('show', !!dirtyByTab[markerTabMap[elId]]);
        }
    },

    updateUI() {
        document.getElementById('survEnabled').checked = this.config.enabled;
        
        const badge = document.getElementById('survStatusBadge');
        badge.textContent = this.config.enabled ? BYD.i18n.t('surveillance.badge_on') : BYD.i18n.t('surveillance.badge_off');
        badge.className = 'status-badge ' + (this.config.enabled ? 'active' : 'inactive');

        document.getElementById('preRecSlider').value = this.config.preRecordSeconds;
        document.getElementById('preRecValue').textContent = this.config.preRecordSeconds + 's';
        document.getElementById('preLabel').textContent = BYD.i18n.t('surveillance.before_seconds', {n: this.config.preRecordSeconds});
        document.getElementById('timelinePre').style.flex = this.config.preRecordSeconds / 10;

        document.getElementById('postRecSlider').value = this.config.postRecordSeconds;
        document.getElementById('postRecValue').textContent = this.config.postRecordSeconds + 's';
        document.getElementById('postLabel').textContent = BYD.i18n.t('surveillance.after_seconds', {n: this.config.postRecordSeconds});
        document.getElementById('timelinePost').style.flex = this.config.postRecordSeconds / 20;
        
        // New tier picker (replaces #bitrateBtns).
        document.querySelectorAll('#survQualityBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingQuality));
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.recordingCodec));

        // Camera FPS buttons
        document.querySelectorAll('#survFpsBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === String(this.config.cameraFps || 15)));

        // Active estimate (per-tier subtitles removed — info is summarised below).
        this.renderActiveEstimate();

        this.updateStorageLimitUI();
        
        // V2 Motion Detection UI
        this.updateV2UI();
        
        // Deterrent Action UI
        this.updateDeterrentUI();
        
        // Reset Apply button state after UI update (no unsaved changes after load)
        this.hasUnsavedChanges = false;
        document.getElementById('btnApply').disabled = true;
        var _du = document.getElementById('detectionUnsaved'); if (_du) _du.classList.remove('show');
        document.getElementById('recordingUnsaved').classList.remove('show');
        var _su2 = document.getElementById('storageUnsaved'); if (_su2) _su2.classList.remove('show');
    },

    updateCheckboxStyles() {
        ['detectPerson', 'detectCar', 'detectBike'].forEach(id => {
            const cb = document.getElementById(id);
            cb.parentElement.classList.toggle('active', cb.checked);
        });
    },

    async toggleSurveillance() {
        const enabled = document.getElementById('survEnabled').checked;
        try {
            await fetch(enabled ? '/api/surveillance/enable' : '/api/surveillance/disable', { method: 'POST' });
            this.config.enabled = enabled;
            this.savedConfig.enabled = enabled;
            this.updateUI();
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(enabled ? BYD.i18n.t('surveillance.enabled') : BYD.i18n.t('surveillance.disabled'), 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('surveillance.toggle_failed'), 'error');
        }
    },

    updateDistance(value) {
        this.config.distance = parseInt(value);
        this.config.minObjectSize = (this.distanceMap[value] || {}).size || 0.08;
        document.getElementById('distanceValue').textContent = this.distanceLabel(value) || BYD.i18n.t('surveillance.label_default_size');
        document.getElementById('distanceHint').textContent = this.distanceHint(value) || '';
        this.markChanged();
    },

    updateSensitivity(value) {
        this.config.sensitivity = parseInt(value);
        document.getElementById('sensitivityValue').textContent = this.sensitivityLabel(value) || BYD.i18n.t('surveillance.label_default');
        document.getElementById('sensitivityHint').textContent = this.sensitivityHint(value) || '';
        this.markChanged();
    },

    updateFlashImmunity(value) {
        this.config.flashImmunity = parseInt(value);
        document.getElementById('flashImmunityValue').textContent = this.flashImmunityMap[value] || 'MEDIUM';
        this.markChanged();
    },

    updateDetection() {
        this.config.detectPerson = document.getElementById('detectPerson').checked;
        this.config.detectCar = document.getElementById('detectCar').checked;
        this.config.detectBike = document.getElementById('detectBike').checked;
        this.updateCheckboxStyles();
        this.markChanged();
    },

    updatePreRec(value) {
        this.config.preRecordSeconds = parseInt(value);
        document.getElementById('preRecValue').textContent = value + 's';
        document.getElementById('preLabel').textContent = BYD.i18n.t('surveillance.before_seconds', {n: value});
        document.getElementById('timelinePre').style.flex = value / 10;
        this.markChanged();
    },

    updatePostRec(value) {
        this.config.postRecordSeconds = parseInt(value);
        document.getElementById('postRecValue').textContent = value + 's';
        document.getElementById('postLabel').textContent = BYD.i18n.t('surveillance.after_seconds', {n: value});
        document.getElementById('timelinePost').style.flex = value / 20;
        this.markChanged();
    },

    setRecordingQuality(tier) {
        this.config.recordingQuality = tier;
        document.querySelectorAll('#survQualityBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === tier));
        this.renderActiveEstimate();
        this.markChanged();
    },

    setCodec(codec) {
        this.config.recordingCodec = codec;
        document.querySelectorAll('#codecBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === codec));
        this.renderActiveEstimate();
        this.markChanged();
    },

    /** Look up the per-tier estimate computed by the server (codec+fps already
     *  factored in). Returns null if not loaded yet. */
    estimateForTier(tier) {
        const opts = this.config.recordingQualityOptions || {};
        return opts[tier] || null;
    },

    formatEstimate(est) {
        if (!est) return '—';
        const parts = [BYD.i18n.t('recording.unit_mbps', {n: (est.bitrateMbps != null ? est.bitrateMbps : '—')})];
        if (est.mbPer2Min != null) parts.push(BYD.i18n.t('recording.unit_mb_per_2min', {n: est.mbPer2Min}));
        if (est.qualityEquivalent) parts.push(est.qualityEquivalent);
        return parts.join(' · ');
    },

    renderActiveEstimate() {
        const el = document.getElementById('survActiveEstimate');
        if (el) {
            // Compute locally from the user's *current* selection, not the
            // server's stale activeRecordingEstimate. Show "saved → pending"
            // diff when the user has changed the tier without applying yet.
            const currentTier = this.config.recordingQuality;
            const savedTier = this.savedConfig ? this.savedConfig.recordingQuality : currentTier;
            const currentEst = this.estimateForTier(currentTier);
            const savedEst = this.estimateForTier(savedTier);
            if (currentTier === savedTier || !savedEst) {
                el.textContent = this.formatEstimate(currentEst);
            } else {
                el.textContent = this.formatEstimate(savedEst)
                    + BYD.i18n.t('recording.estimate_diff_arrow')
                    + this.formatEstimate(currentEst);
            }
        }
        // Measured-FPS row mirrors recording.html: shown when the HAL is
        // emitting at a different rate than what the user requested.
        const row = document.getElementById('survFpsClampRow');
        const fpsEl = document.getElementById('survFpsActual');
        if (row && fpsEl) {
            const actual = this.config.cameraFpsActual;
            if (actual == null) {
                row.style.display = 'none';
            } else {
                row.style.display = '';
                fpsEl.textContent = this.config.cameraFpsClampNote
                    ? this.config.cameraFpsClampNote
                    : (actual + ' fps');
            }
        }
    },

    // ==================== V2 Motion Detection ====================

    // V2 hint texts — looked up via BYD.i18n
    v2EnvPresetHint(preset) { return BYD.i18n.t('surveillance.env_hint.' + preset); },
    v2DetectionZoneHint(zone) { return BYD.i18n.t('surveillance.zone_hint.' + zone); },

    setEnvironmentPreset(preset) {
        this.config.environmentPreset = preset;
        document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === preset));
        // Hide Custom button when a real preset is selected
        const customBtn = document.getElementById('envPresetCustom');
        if (customBtn) customBtn.classList.remove('active');

        // Update environment preset hint
        var _eh = document.getElementById('envPresetHint');
        if (_eh) {
            _eh.setAttribute('data-i18n', 'surveillance.env_hint.' + preset);
            _eh.textContent = this.v2EnvPresetHint(preset) || '';
        }

        // Apply preset values to other V2 controls (UI only, not sent yet)
        const p = this.v2Presets[preset];
        if (p) {
            this.config.sensitivityLevel = p.sensitivityLevel;
            this.config.detectionZone = p.detectionZone;
            this.config.loiteringTime = p.loiteringTime;

            // Update sensitivity slider + label + hint
            const sensSlider = document.getElementById('v2SensitivitySlider');
            if (sensSlider) sensSlider.value = p.sensitivityLevel;
            const sensValue = document.getElementById('v2SensitivityValue');
            if (sensValue) {
                sensValue.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + p.sensitivityLevel);
                sensValue.textContent = this.v2SensLabel(p.sensitivityLevel) || p.sensitivityLevel;
            }
            var _sh = document.getElementById('v2SensitivityHint');
            if (_sh) {
                _sh.setAttribute('data-i18n', 'surveillance.v2_sens_hint.' + p.sensitivityLevel);
                _sh.textContent = this.v2SensHint(p.sensitivityLevel) || '';
            }

            // Update detection zone buttons + hint
            document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
                btn.classList.toggle('active', btn.dataset.value === p.detectionZone));
            var _dh = document.getElementById('detectionZoneHint');
            if (_dh) {
                _dh.setAttribute('data-i18n', 'surveillance.zone_hint.' + p.detectionZone);
                _dh.textContent = this.v2DetectionZoneHint(p.detectionZone) || '';
            }

            // Update loitering slider + label
            const loiterSlider = document.getElementById('loiteringTimeSlider');
            if (loiterSlider) loiterSlider.value = p.loiteringTime;
            const loiterValue = document.getElementById('loiteringTimeValue');
            if (loiterValue) loiterValue.textContent = p.loiteringTime + 's';
            
            // Update shadow filter select
            if (p.shadowFilter !== undefined) {
                this.config.shadowFilter = p.shadowFilter;
                const shadowSelect = document.getElementById('shadowFilterSelect');
                if (shadowSelect) shadowSelect.value = p.shadowFilter;
            }
        }
        this.markChanged();
    },

    updateV2Sensitivity(value) {
        this.config.sensitivityLevel = parseInt(value);
        const label = document.getElementById('v2SensitivityValue');
        if (label) {
            label.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + value);
            label.textContent = this.v2SensLabel(value) || value;
        }
        var _sh = document.getElementById('v2SensitivityHint');
        if (_sh) {
            _sh.setAttribute('data-i18n', 'surveillance.v2_sens_hint.' + value);
            _sh.textContent = this.v2SensHint(value) || '';
        }
        this._deselectPresetIfCustom();
        this.markChanged();
    },

    setDetectionZone(zone) {
        this.config.detectionZone = zone;
        document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === zone));
        var _dh = document.getElementById('detectionZoneHint');
        if (_dh) {
            _dh.setAttribute('data-i18n', 'surveillance.zone_hint.' + zone);
            _dh.textContent = this.v2DetectionZoneHint(zone) || '';
        }
        this._deselectPresetIfCustom();
        this.markChanged();
    },

    updateLoiteringTime(value) {
        this.config.loiteringTime = parseInt(value);
        const label = document.getElementById('loiteringTimeValue');
        if (label) label.textContent = value + 's';
        this._deselectPresetIfCustom();
        this.markChanged();
    },
    
    updateShadowFilter(value) {
        this.config.shadowFilter = parseInt(value);
        const hint = document.getElementById('shadowFilterHint');
        if (hint) {
            hint.setAttribute('data-i18n', 'surveillance.shadow_hint.' + this.config.shadowFilter);
            hint.textContent = BYD.i18n.t('surveillance.shadow_hint.' + this.config.shadowFilter) || '';
        }
        this.markChanged();
    },
    
    _deselectPresetIfCustom() {
        // Check if current values match ANY preset
        let matchedPreset = null;
        for (const [name, p] of Object.entries(this.v2Presets)) {
            if (this.config.sensitivityLevel == p.sensitivityLevel &&
                this.config.detectionZone === p.detectionZone &&
                this.config.loiteringTime == p.loiteringTime) {
                matchedPreset = name;
                break;
            }
        }
        
        const customBtn = document.getElementById('envPresetCustom');
        if (matchedPreset) {
            // Values match a known preset — highlight it
            this.config.environmentPreset = matchedPreset;
            document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
                btn.classList.toggle('active', btn.dataset.value === matchedPreset));
            if (customBtn) customBtn.classList.remove('active');
            var _eh = document.getElementById('envPresetHint');
            if (_eh) {
                _eh.setAttribute('data-i18n', 'surveillance.env_hint.' + matchedPreset);
                _eh.textContent = this.v2EnvPresetHint(matchedPreset) || '';
            }
        } else {
            // No preset matches — show Custom
            document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
                btn.classList.remove('active'));
            if (customBtn) customBtn.classList.add('active');
            var _eh = document.getElementById('envPresetHint');
            if (_eh) {
                _eh.setAttribute('data-i18n', 'surveillance.custom_config');
                _eh.textContent = BYD.i18n.t('surveillance.custom_config');
            }
        }
    },

    updateV2Cameras() {
        this.config.cameraFront = document.getElementById('v2CameraFront').checked;
        this.config.cameraRight = document.getElementById('v2CameraRight').checked;
        this.config.cameraLeft = document.getElementById('v2CameraLeft').checked;
        this.config.cameraRear = document.getElementById('v2CameraRear').checked;
        this.markChanged();
    },

    /**
     * Side-cam Boost: writes overrides into quadrantOverrides Q1 (right) and
     * Q3 (left). Disabling the boost removes both keys so the side cams
     * inherit the global sensitivity / zone again.
     */
    _writeSideCamOverrides(sens, zone) {
        const ov = Object.assign({}, this.config.quadrantOverrides || {});
        if (sens == null && zone == null) {
            delete ov.Q1;
            delete ov.Q3;
        } else {
            const perQ = {};
            if (sens != null) perQ.sensitivityLevel = parseInt(sens);
            if (zone != null) perQ.detectionZone = zone;
            ov.Q1 = perQ;
            ov.Q3 = Object.assign({}, perQ);
        }
        this.config.quadrantOverrides = ov;
    },

    updateSideCamBoost() {
        const on = document.getElementById('sideCamBoostEnabled').checked;
        const ctl = document.getElementById('sideCamBoostControls');
        if (ctl) ctl.style.display = on ? '' : 'none';
        if (on) {
            const sens = parseInt(document.getElementById('sideCamSensSlider').value);
            const activeZoneBtn = document.querySelector('#sideCamZoneBtns .btn-toggle.active');
            const zone = activeZoneBtn ? activeZoneBtn.dataset.value : 'extended';
            this._writeSideCamOverrides(sens, zone);
        } else {
            this._writeSideCamOverrides(null, null);
        }
        this.markChanged();
    },

    updateSideCamSens(value) {
        const sens = parseInt(value);
        const valEl = document.getElementById('sideCamSensValue');
        if (valEl) {
            valEl.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + sens);
            valEl.textContent = this.v2SensLabel(sens) || sens;
        }
        const activeZoneBtn = document.querySelector('#sideCamZoneBtns .btn-toggle.active');
        const zone = activeZoneBtn ? activeZoneBtn.dataset.value : 'extended';
        this._writeSideCamOverrides(sens, zone);
        this.markChanged();
    },

    setSideCamZone(zone) {
        document.querySelectorAll('#sideCamZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === zone));
        const sens = parseInt(document.getElementById('sideCamSensSlider').value);
        this._writeSideCamOverrides(sens, zone);
        this.markChanged();
    },

    updateTelegramStartPing() {
        const el = document.getElementById('v2TelegramSendStartPing');
        if (!el) return;
        this.config.telegramSendStartPing = el.checked;
        this.markChanged();
    },

    /**
     * Per-tier Telegram filter handler. Wires the three new toggles
     * (Notice / Alert / Critical) into config so the next config save
     * pushes them to the daemon. Same markChanged → Apply button flow as
     * the rest of the surveillance settings.
     */
    updateTelegramTiers() {
        const n = document.getElementById('v2TelegramNotices');
        const a = document.getElementById('v2TelegramAlerts');
        const c = document.getElementById('v2TelegramCritical');
        if (n) this.config.telegramNotices = n.checked;
        if (a) this.config.telegramAlerts = a.checked;
        if (c) this.config.telegramCritical = c.checked;
        this.markChanged();
    },

    /**
     * Reflect the Telegram-bot pairing state in the tier filter UI. The
     * tier toggles + the two-stage start-ping are functionally inert when
     * the bot isn\'t paired (engine still calls TelegramNotifier, daemon
     * just answers "Owner not set" and drops the message), so we visually
     * disable them and show an inline warning so the user understands
     * why their toggles don\'t produce messages. Re-runs on every config
     * refresh (every 10s) so freshly-paired bots un-grey live without a
     * page reload.
     */
    async refreshTelegramAvailability() {
        let paired = false;
        try {
            const r = await fetch('/api/settings/telegram-status');
            if (r.ok) {
                const j = await r.json();
                paired = !!(j && j.enabled);
            }
        } catch (e) {
            // Network blip / endpoint missing on older builds — treat as
            // "unknown" and leave toggles enabled so we don\'t block users
            // on a transient failure.
            paired = true;
        }
        const group = document.getElementById('v2TelegramTierGroup');
        const warning = document.getElementById('v2TelegramTierWarning');
        const startPing = document.getElementById('v2TelegramSendStartPing');
        const tierIds = ['v2TelegramNotices', 'v2TelegramAlerts', 'v2TelegramCritical'];

        const setDisabled = (el, dis) => {
            if (!el) return;
            el.disabled = dis;
            const row = el.closest('.setting-row');
            if (row) {
                row.style.opacity = dis ? '0.55' : '';
                row.style.pointerEvents = dis ? 'none' : '';
            }
        };
        setDisabled(startPing, !paired);
        for (const id of tierIds) setDisabled(document.getElementById(id), !paired);
        if (warning) warning.style.display = paired ? 'none' : 'block';
        if (group) group.dataset.telegramPaired = paired ? '1' : '0';
    },

    updateV2Dev() {
        var heatmapEl = document.getElementById('v2MotionHeatmap');
        var filterEl = document.getElementById('v2FilterDebugLog');
        const heatmapOn = (heatmapEl && heatmapEl.checked) || false;
        const wasOn = this.config.motionHeatmap;
        this.config.motionHeatmap = heatmapOn;
        this.config.filterDebugLog = (filterEl && filterEl.checked) || false;

        // Start/stop heatmap when toggle changes
        if (heatmapOn && !wasOn) {
            this.startHeatmap();
        } else if (!heatmapOn && wasOn) {
            this.stopHeatmap();
        }

        this.markChanged();
    },

    // ==================== Motion Heatmap Overlay ====================

    _heatmapInterval: null,
    _heatmapCanvas: null,
    _heatmapWasRunning: false,

    /**
     * Start the motion heatmap overlay.
     * Creates a canvas over the live stream container and polls at 3 FPS.
     */
    startHeatmap() {
        if (this._heatmapInterval) return; // Already running

        const container = document.getElementById('videoDisplayArea');
        if (!container) {
            console.log('[Heatmap] No video display area found — skipping');
            return;
        }

        // Create canvas overlay if not exists
        if (!this._heatmapCanvas) {
            const canvas = document.createElement('canvas');
            canvas.id = 'heatmapOverlay';
            canvas.width = 1280;
            canvas.height = 960;
            canvas.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:15;';
            container.style.position = 'relative';
            container.appendChild(canvas);
            this._heatmapCanvas = canvas;
        }

        this._heatmapCanvas.style.display = 'block';

        // Poll at 3 FPS (333ms)
        this._heatmapInterval = setInterval(() => this._pollHeatmap(), 333);
        console.log('[Heatmap] Started polling at 3 FPS');
    },

    /**
     * Stop the motion heatmap overlay and clean up.
     */
    stopHeatmap() {
        if (this._heatmapInterval) {
            clearInterval(this._heatmapInterval);
            this._heatmapInterval = null;
        }

        if (this._heatmapCanvas) {
            this._heatmapCanvas.remove();
            this._heatmapCanvas = null;
        }

        console.log('[Heatmap] Stopped');
    },

    /**
     * Fetch heatmap data and draw on canvas.
     */
    async _pollHeatmap() {
        if (!this._heatmapCanvas) return;

        try {
            const resp = await fetch('/api/surveillance/heatmap');
            const data = await resp.json();
            if (data && data.quadrants) {
                // Override viewMode with client-side stream selection if available.
                // BYD.stream tracks which camera the user selected — use that
                // so the heatmap matches what's visible on screen.
                if (typeof BYD !== 'undefined' && BYD.stream && BYD.stream.currentViewMode >= 0) {
                    data.viewMode = BYD.stream.currentViewMode;
                }
                this._drawHeatmap(this._heatmapCanvas, data);
            }
        } catch (e) {
            // Surveillance not running or API error — clear the canvas
            const ctx = this._heatmapCanvas.getContext('2d');
            if (ctx) ctx.clearRect(0, 0, this._heatmapCanvas.width, this._heatmapCanvas.height);
        }
    },

    /**
     * Draw the heatmap overlay on the canvas.
     * 
     * In mosaic mode (viewMode=0), draws a 2x2 grid:
     *   [0: FRONT]  [1: RIGHT]
     *   [2: LEFT ]  [3: REAR ]
     *
     * In single-camera mode (viewMode=1-4), draws only the active quadrant
     * filling the full canvas. viewMode mapping: 1=Front, 2=Right, 3=Rear, 4=Left.
     *
     * Each quadrant has gridCols x gridRows blocks with confidence values.
     * Blocks are color-coded: green (low) → yellow (medium) → red (high).
     */
    _drawHeatmap(canvas, data) {
        var ctx = canvas.getContext('2d');
        if (!ctx) return;

        var cw = canvas.width;
        var ch = canvas.height;
        ctx.clearRect(0, 0, cw, ch);

        var gridCols = data.gridCols || 10;
        var gridRows = data.gridRows || 7;

        // viewMode: 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left
        var viewMode = data.viewMode || 0;
        
        // Map viewMode to quadrant ID: 1→0(front), 2→1(right), 3→3(rear), 4→2(left)
        var viewModeToQuadrant = { 1: 0, 2: 1, 3: 3, 4: 2 };
        var singleQuadrant = (viewMode > 0) ? viewModeToQuadrant[viewMode] : -1;
        
        // In single-camera mode, the heatmap fills the full canvas
        // In mosaic mode, each quadrant takes a quarter
        var isSingle = singleQuadrant >= 0;
        var quadW = isSingle ? cw : cw / 2;
        var quadH = isSingle ? ch : ch / 2;
        var blockW = quadW / gridCols;
        var blockH = quadH / gridRows;

        var quadPositions = [
            [0, 0],  // 0: front  — top-left
            [1, 0],  // 1: right  — top-right
            [1, 1],  // 2: left   — bottom-right
            [0, 1]   // 3: rear   — bottom-left
        ];
        var quadLabels = [
            BYD.i18n.t('surveillance.camera_front'),
            BYD.i18n.t('surveillance.camera_right'),
            BYD.i18n.t('surveillance.camera_left'),
            BYD.i18n.t('surveillance.camera_rear')
        ];
        var threatLabels = [
            '',
            BYD.i18n.t('surveillance.heatmap_threat_low'),
            BYD.i18n.t('surveillance.heatmap_threat_medium'),
            BYD.i18n.t('surveillance.heatmap_threat_high'),
            BYD.i18n.t('surveillance.heatmap_threat_critical')
        ];

        for (var qi = 0; qi < data.quadrants.length; qi++) {
            var q = data.quadrants[qi];
            
            // In single-camera mode, only draw the active quadrant
            if (isSingle && q.id !== singleQuadrant) continue;
            
            var pos = quadPositions[q.id];
            if (!pos) continue;

            // In single mode, always draw at (0,0) filling full canvas
            var qx = isSingle ? 0 : pos[0] * quadW;
            var qy = isSingle ? 0 : pos[1] * quadH;

            // Quadrant border (thin white line) — only in mosaic mode
            if (!isSingle) {
                ctx.strokeStyle = 'rgba(255, 255, 255, 0.15)';
                ctx.lineWidth = 1;
                ctx.strokeRect(qx, qy, quadW, quadH);
            }

            // Camera label (top-left of each quadrant)
            ctx.font = 'bold 14px Inter, sans-serif';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';

            // Disabled quadrant
            if (!q.enabled) {
                ctx.fillStyle = 'rgba(128, 128, 128, 0.25)';
                ctx.fillRect(qx, qy, quadW, quadH);
                ctx.fillStyle = 'rgba(255, 255, 255, 0.5)';
                ctx.fillText(BYD.i18n.t('surveillance.heatmap_camera_off', {camera: quadLabels[q.id]}), qx + 8, qy + 6);
                continue;
            }

            // Suppressed quadrant (brightness shift detected)
            if (q.suppressed) {
                ctx.fillStyle = 'rgba(59, 130, 246, 0.20)';
                ctx.fillRect(qx, qy, quadW, quadH);
                ctx.fillStyle = 'rgba(147, 197, 253, 0.8)';
                ctx.fillText(BYD.i18n.t('surveillance.heatmap_camera_light_shift', {camera: quadLabels[q.id]}), qx + 8, qy + 6);
                continue;
            }

            // Draw confidence blocks with smooth gradient
            var activeCount = 0;
            if (q.confidence && q.confidence.length > 0) {
                for (var i = 0; i < q.confidence.length; i++) {
                    var c = q.confidence[i];
                    if (c <= 0) continue;

                    activeCount++;
                    var col = i % gridCols;
                    var row = Math.floor(i / gridCols);
                    var bx = qx + col * blockW;
                    var by = qy + row * blockH;

                    // Smooth color gradient: green → yellow → red
                    var r, g, b, a;
                    if (c < 0.40) {
                        var t = c / 0.40;
                        r = Math.round(34 + (234 - 34) * t);
                        g = Math.round(197 + (179 - 197) * t);
                        b = Math.round(94 + (8 - 94) * t);
                        a = 0.25 + 0.15 * t;
                    } else {
                        var t = (c - 0.40) / 0.60;
                        r = Math.round(234 + (239 - 234) * t);
                        g = Math.round(179 - 179 * t);
                        b = Math.round(8 + (68 - 8) * t);
                        a = 0.40 + 0.25 * t;
                    }
                    ctx.fillStyle = 'rgba(' + r + ',' + g + ',' + b + ',' + a.toFixed(2) + ')';
                    ctx.fillRect(bx + 1, by + 1, blockW - 2, blockH - 2);
                }
            }

            // Camera label with active block count
            var threat = q.threatLevel || 0;
            var labelColor = threat >= 3 ? 'rgba(239, 68, 68, 0.9)' :
                             threat >= 2 ? 'rgba(234, 179, 8, 0.9)' :
                             activeCount > 0 ? 'rgba(34, 197, 94, 0.9)' :
                             'rgba(255, 255, 255, 0.5)';
            ctx.fillStyle = labelColor;
            var label = quadLabels[q.id];
            if (activeCount > 0) {
                label += '  ' + BYD.i18n.t('surveillance.heatmap_blocks', {n: activeCount});
                if (threat > 0 && threat < threatLabels.length) {
                    label += ' · ' + threatLabels[threat];
                }
            }
            var labelWidth = ctx.measureText(label).width + 12;
            ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
            ctx.fillRect(qx + 4, qy + 3, labelWidth, 20);
            ctx.fillStyle = labelColor;
            ctx.fillText(label, qx + 8, qy + 6);
        }

        // Legend (bottom-center)
        var legendY = ch - 24;
        var legendX = cw / 2 - 120;
        ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
        ctx.fillRect(legendX - 8, legendY - 4, 256, 22);
        ctx.font = '11px Inter, sans-serif';
        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';
        // Green
        ctx.fillStyle = 'rgba(34, 197, 94, 0.7)';
        ctx.fillRect(legendX, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText(BYD.i18n.t('surveillance.heatmap_legend_low'), legendX + 16, legendY + 6);
        // Yellow
        ctx.fillStyle = 'rgba(234, 179, 8, 0.7)';
        ctx.fillRect(legendX + 55, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText(BYD.i18n.t('surveillance.heatmap_legend_medium'), legendX + 71, legendY + 6);
        // Red
        ctx.fillStyle = 'rgba(239, 68, 68, 0.7)';
        ctx.fillRect(legendX + 130, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText(BYD.i18n.t('surveillance.heatmap_legend_high'), legendX + 146, legendY + 6);
        // Blue
        ctx.fillStyle = 'rgba(59, 130, 246, 0.7)';
        ctx.fillRect(legendX + 190, legendY, 12, 12);
        ctx.fillStyle = 'rgba(255,255,255,0.7)';
        ctx.fillText(BYD.i18n.t('surveillance.heatmap_legend_suppressed'), legendX + 206, legendY + 6);
    },

    updateV2UI() {
        // Environment preset — check if current values match the saved preset
        // If user customized sliders after selecting a preset, don't highlight any preset
        const savedPreset = this.config.environmentPreset;
        const presetValues = this.v2Presets[savedPreset];
        let presetMatches = false;
        if (presetValues) {
            presetMatches = (
                this.config.sensitivityLevel == presetValues.sensitivityLevel &&
                this.config.detectionZone === presetValues.detectionZone &&
                this.config.loiteringTime == presetValues.loiteringTime
            );
        }
        
        document.querySelectorAll('#envPresetBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', presetMatches && btn.dataset.value === savedPreset));
        const customBtn = document.getElementById('envPresetCustom');
        if (!presetMatches) {
            if (customBtn) customBtn.classList.add('active');
        } else {
            if (customBtn) customBtn.classList.remove('active');
        }
        var _eh = document.getElementById('envPresetHint');
        if (_eh) {
            if (presetMatches) {
                _eh.setAttribute('data-i18n', 'surveillance.env_hint.' + savedPreset);
                _eh.textContent = this.v2EnvPresetHint(savedPreset) || '';
            } else {
                _eh.setAttribute('data-i18n', 'surveillance.custom_config');
                _eh.textContent = BYD.i18n.t('surveillance.custom_config');
            }
        }

        // Sensitivity
        const sensSlider = document.getElementById('v2SensitivitySlider');
        if (sensSlider) sensSlider.value = this.config.sensitivityLevel;
        const sensValue = document.getElementById('v2SensitivityValue');
        if (sensValue) {
            sensValue.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + this.config.sensitivityLevel);
            sensValue.textContent = this.v2SensLabel(this.config.sensitivityLevel) || this.config.sensitivityLevel;
        }
        var _sh = document.getElementById('v2SensitivityHint');
        if (_sh) {
            _sh.setAttribute('data-i18n', 'surveillance.v2_sens_hint.' + this.config.sensitivityLevel);
            _sh.textContent = this.v2SensHint(this.config.sensitivityLevel) || '';
        }

        // Detection zone
        document.querySelectorAll('#detectionZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === this.config.detectionZone));
        var _dh = document.getElementById('detectionZoneHint');
        if (_dh) {
            _dh.setAttribute('data-i18n', 'surveillance.zone_hint.' + this.config.detectionZone);
            _dh.textContent = this.v2DetectionZoneHint(this.config.detectionZone) || '';
        }

        // Loitering time
        const loiterSlider = document.getElementById('loiteringTimeSlider');
        if (loiterSlider) loiterSlider.value = this.config.loiteringTime;
        const loiterValue = document.getElementById('loiteringTimeValue');
        if (loiterValue) loiterValue.textContent = this.config.loiteringTime + 's';

        // Shadow filter
        const shadowSelect = document.getElementById('shadowFilterSelect');
        if (shadowSelect && this.config.shadowFilter !== undefined) {
            shadowSelect.value = this.config.shadowFilter;
        }
        const shadowHint = document.getElementById('shadowFilterHint');
        if (shadowHint && this.config.shadowFilter !== undefined) {
            shadowHint.setAttribute('data-i18n', 'surveillance.shadow_hint.' + this.config.shadowFilter);
            shadowHint.textContent = BYD.i18n.t('surveillance.shadow_hint.' + this.config.shadowFilter) || '';
        }

        // Camera toggles
        const cf = document.getElementById('v2CameraFront');
        if (cf) cf.checked = this.config.cameraFront;
        const cr = document.getElementById('v2CameraRight');
        if (cr) cr.checked = this.config.cameraRight;
        const cl = document.getElementById('v2CameraLeft');
        if (cl) cl.checked = this.config.cameraLeft;
        const cb = document.getElementById('v2CameraRear');
        if (cb) cb.checked = this.config.cameraRear;

        // Side-cam Boost — derived from quadrantOverrides[Q1] / [Q3]. We
        // treat "boost enabled" as: both side quadrants share an override.
        // If the user customized just one side via API, fall back to that
        // quadrant's value to stay consistent.
        const ov = this.config.quadrantOverrides || {};
        const q1 = ov.Q1 || {};
        const q3 = ov.Q3 || {};
        const boostOn = !!(q1.sensitivityLevel || q3.sensitivityLevel || q1.detectionZone || q3.detectionZone);
        const boostBox = document.getElementById('sideCamBoostEnabled');
        const boostCtl = document.getElementById('sideCamBoostControls');
        if (boostBox) boostBox.checked = boostOn;
        if (boostCtl) boostCtl.style.display = boostOn ? '' : 'none';
        const sideSens = (q1.sensitivityLevel || q3.sensitivityLevel || 4);
        const sideZone = (q1.detectionZone || q3.detectionZone || 'extended');
        const sSlider = document.getElementById('sideCamSensSlider');
        if (sSlider) sSlider.value = sideSens;
        const sValue = document.getElementById('sideCamSensValue');
        if (sValue) {
            sValue.setAttribute('data-i18n', 'surveillance.v2_sens_label.' + sideSens);
            sValue.textContent = this.v2SensLabel(sideSens) || sideSens;
        }
        document.querySelectorAll('#sideCamZoneBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === sideZone));

        // Developer toggles
        const hm = document.getElementById('v2MotionHeatmap');
        if (hm) hm.checked = this.config.motionHeatmap;
        const fd = document.getElementById('v2FilterDebugLog');
        if (fd) fd.checked = this.config.filterDebugLog;

        // Telegram start-ping opt-in
        const tg = document.getElementById('v2TelegramSendStartPing');
        if (tg) tg.checked = !!this.config.telegramSendStartPing;

        // Per-tier Telegram filter — null-coalesce to the documented defaults
        // so configs saved before these fields existed render correctly
        // (notices off, alerts on, critical on).
        const tn = document.getElementById('v2TelegramNotices');
        if (tn) tn.checked = this.config.telegramNotices === true;
        const ta = document.getElementById('v2TelegramAlerts');
        if (ta) ta.checked = this.config.telegramAlerts !== false;
        const tc = document.getElementById('v2TelegramCritical');
        if (tc) tc.checked = this.config.telegramCritical !== false;
        
        // Object detection checkboxes
        const dp = document.getElementById('detectPerson');
        if (dp) dp.checked = this.config.detectPerson;
        const dc = document.getElementById('detectCar');
        if (dc) dc.checked = this.config.detectCar;
        const db = document.getElementById('detectBike');
        if (db) db.checked = this.config.detectBike;
        this.updateCheckboxStyles();
    },

    /**
     * Per-tab field map — which `this.config` keys belong to which tab.
     * Apply uses this to build a PARTIAL body that only writes fields the
     * user could have edited on the active tab. Avoids clobbering settings
     * the user hasn't seen on this page-load.
     *
     * The keys here MUST match exactly what SurveillanceApiHandler reads on
     * POST (configJson.has(...) calls). Audited 2026-05-20 against the
     * Java handler. The "_storage_endpoint" sentinel signals a separate POST
     * to /api/settings/storage instead of the surveillance config endpoint.
     *
     * Side-cam boost (sideCamBoost*) is intentionally absent — those
     * controls write into `quadrantOverrides` rather than dedicated keys,
     * so the partial body only needs to ship `quadrantOverrides`.
     *
     * Safe Locations and ROI editor have their OWN endpoints
     * (/api/surveillance/safe-locations, save() on roi-schedule.js posts
     * directly with its own partial body) and are NOT routed through this
     * Apply flow. Listing them here would be a no-op since `this.config`
     * doesn't carry those keys.
     */
    _tabFieldMap: {
        general: [
            'enabled',
            'scheduleEnabled', 'scheduleRules',
            'deterrentAction', 'deterrentCooldownSeconds'
        ],
        detection: [
            'environmentPreset',
            'sensitivityLevel', 'detectionZone',
            'loiteringTime', 'shadowFilter',
            'cameraFront', 'cameraRight', 'cameraLeft', 'cameraRear',
            'quadrantOverrides',
            'detectPerson', 'detectCar', 'detectBike',
            'aiConfidence', 'minObjectSize',
            'flashImmunity'
        ],
        recording: [
            // Backend reads these EXACT names — preRecordSeconds (no "ing"),
            // recordingQuality / recordingCodec (with the "recording" prefix).
            // recordingQuality is the consolidated tier (ECONOMY..MAX) that
            // replaces the legacy recordingBitrate (LOW/MEDIUM/HIGH).
            // cameraFps does NOT live here — it's owned by the Quality
            // Settings handler (/api/settings/quality).
            'preRecordSeconds', 'postRecordSeconds',
            'recordingQuality', 'recordingCodec'
        ],
        storage: [
            // Sentinel — branches the Apply flow to /api/settings/storage.
            '_storage_endpoint'
        ],
        advanced: [
            'motionHeatmap', 'filterDebugLog'
        ]
    },

    /**
     * Look up the tab the user is currently viewing. The bottom-tabs script
     * (shared/app-tabs.js) persists the active tab to localStorage; we read
     * that so a partial save targets the visible tab even after the user
     * switches across tabs without reloading.
     */
    _activeTabId: function () {
        try {
            var path = window.location.pathname || '';
            var idx = path.lastIndexOf('/');
            var page = idx >= 0 ? path.substring(idx + 1) : (path || 'index');
            var stored = window.localStorage.getItem('ot-active-tab-' + page);
            if (stored) return stored;
        } catch (e) {}
        // Fall back to the visible bottom-tab pill.
        var visible = document.querySelector('.bottom-tab.is-active');
        if (visible) return visible.getAttribute('data-tab-target') || 'general';
        return 'general';
    },

    async applySettings() {
        const btn = document.getElementById('btnApply');
        const origText = btn.innerHTML;
        btn.innerHTML = BYD.i18n.t('surveillance.saving');
        btn.disabled = true;

        try {
            const activeTab = this._activeTabId();
            const fields = this._tabFieldMap[activeTab] || [];
            const isStorageTab = fields.indexOf('_storage_endpoint') !== -1;

            let storageData = {};

            if (isStorageTab) {
                // Storage tab — only write storage fields via /api/settings/storage,
                // mirroring the legacy carve-out. Don't touch /api/surveillance/config
                // so detection sensitivity / schedule etc. stay untouched.
                const storageResp = await fetch('/api/settings/storage', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        surveillanceLimitMb: this.config.surveillanceLimitMb,
                        surveillanceStorageType: this.config.surveillanceStorageType
                    })
                });
                if (!storageResp.ok) throw new Error('Storage save failed: ' + storageResp.status);
                storageData = await storageResp.json();
                // CDR cleanup card on this tab self-saves — toggleCdrCleanup()
                // and the slider onchange handlers POST to
                // /api/storage/external/config directly on each change. So
                // pressing Apply on the Storage tab only needs to write the
                // surveillance storage limit + storage-type pair above; the
                // CDR retention sliders are already persisted live.
            } else {
                // Build a partial body containing only the active tab's fields.
                // The daemon's surveillance config endpoint is a partial-merge
                // writer, so omitted keys retain their prior values.
                const partial = {};
                for (let i = 0; i < fields.length; i++) {
                    const key = fields[i];
                    if (this.config[key] !== undefined) partial[key] = this.config[key];
                }
                const configResp = await fetch('/api/surveillance/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(partial)
                });
                if (!configResp.ok) throw new Error('Config save failed: ' + configResp.status);
            }

            this.savedConfig = JSON.parse(JSON.stringify(this.config));
            this.hasUnsavedChanges = false;
            this.markChanged();
            // Re-render so the "saved → pending" arrow disappears now that
            // savedConfig caught up to config.
            this.renderActiveEstimate();

            // Refresh storage stats after save (cleanup may have run)
            setTimeout(() => this.loadStorageStats(), 1000);

            let msg = BYD.i18n.t('surveillance.settings_applied');
            if (storageData.cleanup && storageData.cleanup.surveillanceToDelete) {
                msg = BYD.i18n.t('surveillance.settings_applied_deleting', {files: storageData.cleanup.surveillanceFilesEstimate, size: storageData.cleanup.surveillanceToDelete});
            }

            btn.innerHTML = BYD.i18n.t('surveillance.saved_check');
            setTimeout(() => { btn.innerHTML = origText; }, 1500);

            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, 'success');
        } catch (e) {
            console.error('applySettings error:', e);
            btn.innerHTML = origText;
            btn.disabled = false;
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('surveillance.save_failed', {error: e.message || BYD.i18n.t('errors.generic')}), 'error');
        }
    },

    /**
     * Lightweight init for pages that only mount the Telegram delivery
     * controls (notifications.html). Loads the surveillance config, paints
     * the Telegram checkboxes via updateUI(), and refreshes the bot-paired
     * availability state. Every DOM read inside updateUI() is null-guarded
     * so missing controls (sensitivity slider, env preset, ROI editor, …)
     * are skipped without warnings.
     */
    async initTelegramOnly() {
        await this.loadConfig();
        this.updateUI();
        this.refreshTelegramAvailability();
        // Re-poll the pairing state when the user comes back to this tab —
        // a fresh bot pair done in the Telegram daemon page should un-grey
        // the toggles without a manual reload.
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') {
                this.refreshTelegramAvailability();
            }
        });
    },

    /**
     * Lightweight init for pages that only need the heatmap overlay (e.g., live view).
     * Loads config and starts heatmap if enabled, without touching surveillance UI elements.
     */
    async initHeatmapOnly() {
        await this.loadConfig();

        // Auto-start heatmap if enabled in config and video display area exists
        if (this.config.motionHeatmap) {
            this.startHeatmap();
        }

        // Handle page visibility for heatmap
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'hidden' && this._heatmapInterval) {
                this.stopHeatmap();
                this._heatmapWasRunning = true;
            }
            if (document.visibilityState === 'visible' && this._heatmapWasRunning) {
                this._heatmapWasRunning = false;
                if (this.config.motionHeatmap) {
                    this.startHeatmap();
                }
            }
        });

        // Periodically check if heatmap config changed (e.g., toggled from surveillance page)
        setInterval(async () => {
            try {
                const resp = await fetch('/api/surveillance/config');
                const data = await resp.json();
                if (data.success && data.config) {
                    const newHeatmap = data.config.motionHeatmap || false;
                    if (newHeatmap && !this._heatmapInterval) {
                        this.config.motionHeatmap = true;
                        this.startHeatmap();
                    } else if (!newHeatmap && this._heatmapInterval) {
                        this.config.motionHeatmap = false;
                        this.stopHeatmap();
                    }
                }
            } catch (e) { /* ignore */ }
        }, 5000);
    },

    /**
     * Update surveillance-specific UI from status (called by core.js)
     */
    updateFromStatus(status) {
        const survState = document.getElementById('survState');
        if (survState) {
            if (status.safeZoneSuppressed || status.inSafeZone) {
                survState.textContent = status.safeZoneName
                    ? BYD.i18n.t('surveillance.state_safe_zone_named', {name: status.safeZoneName})
                    : BYD.i18n.t('surveillance.state_safe_zone');
                survState.style.color = 'var(--brand-secondary)';
            } else {
                survState.textContent = status.gpuSurveillance
                    ? BYD.i18n.t('surveillance.state_active')
                    : BYD.i18n.t('common.idle');
                survState.style.color = '';
            }
        }
        
        // Don't touch the enabled toggle from status — gpuSurveillance is runtime state,
        // not the user's preference. Surveillance can be enabled (preference=true) but not
        // active (gpuSurveillance=false) when ACC is ON. The toggle reflects the preference,
        // which is loaded from the config API, not from status.
    },

    // ── Deterrent Action ────────────────────────────────────────────────

    updateDeterrent(value) {
        this.config.deterrentAction = value;
        // Save immediately (deterrent is independent of the Apply button)
        fetch('/api/surveillance/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ deterrentAction: value })
        }).then(() => {
            this.updateDeterrentUI();
        }).catch(e => console.warn('Failed to save deterrent:', e));
    },

    updateDeterrentUI() {
        const action = this.config.deterrentAction || 'silent';
        const select = document.getElementById('deterrentAction');
        if (select) select.value = action;

        const badge = document.getElementById('deterrentBadge');
        if (badge) {
            const labels = {
                silent: BYD.i18n.t('surveillance.deterrent_silent_badge'),
                flash_lights: BYD.i18n.t('surveillance.deterrent_flash_badge'),
                find_car: BYD.i18n.t('surveillance.deterrent_horn_badge')
            };
            badge.textContent = labels[action] || BYD.i18n.t('surveillance.deterrent_silent_badge');
            badge.className = 'status-badge ' + (action === 'silent' ? 'inactive' : 'active');
        }

        // Show warning if cloud action selected but not configured
        const warning = document.getElementById('deterrentWarning');
        if (warning) {
            const needsCloud = action !== 'silent';
            const configured = this.config.bydCloudEnabled;
            warning.style.display = (needsCloud && !configured) ? 'block' : 'none';
        }
    }
};

// Alias for backward compatibility
window.SurvSettings = BYD.surveillance;

// ── BYD Cloud Account Setup ─────────────────────────────────────────────

window.BydCloud = {
    isConfigured: false,

    async loadStatus() {
        try {
            const resp = await fetch('/api/bydcloud/status');
            const data = await resp.json();
            if (data.success && data.status) {
                this.isConfigured = data.status.configured;
                this.updateStatusUI(data.status);
            }
        } catch (e) {
            console.warn('Failed to load BYD Cloud status:', e);
        }
    },

    updateStatusUI(status) {
        const badge = document.getElementById('bydCloudBadge');
        const info = document.getElementById('bydCloudInfo');
        const clearSection = document.getElementById('bydClearSection');
        const testBtn = document.getElementById('bydTestBtn');
        const saveBtn = document.getElementById('bydSaveBtn');
        const emailInput = document.getElementById('bydEmail');
        const pwdHint = document.getElementById('bydPasswordHint');
        const pinHint = document.getElementById('bydPinHint');
        const pwdInput = document.getElementById('bydPassword');
        const pinInput = document.getElementById('bydPin');

        if (status.verified) {
            this.isConfigured = true;
            if (badge) { badge.textContent = BYD.i18n.t('surveillance.byd_badge_connected'); badge.className = 'status-badge active'; }
            if (info) {
                info.style.display = 'block';
                document.getElementById('bydVinDisplay').textContent = status.vin || '\u2014';
                document.getElementById('bydAccountDisplay').textContent = status.username || '\u2014';
            }
            if (clearSection) clearSection.style.display = 'block';
            if (testBtn) { testBtn.disabled = false; testBtn.style.color = 'var(--text-primary)'; testBtn.style.borderColor = 'var(--brand-primary)'; }
            if (emailInput) emailInput.value = status.username || '';
            var regionSelect = document.getElementById('bydRegion');
            if (regionSelect && status.region) regionSelect.value = status.region;
            if (pwdInput) pwdInput.placeholder = BYD.i18n.t('surveillance.byd_password_placeholder_keep');
            if (pinInput) pinInput.placeholder = BYD.i18n.t('surveillance.byd_pin_placeholder_keep');
            if (pwdHint) pwdHint.textContent = BYD.i18n.t('surveillance.byd_pwd_keep');
            if (pinHint) pinHint.textContent = BYD.i18n.t('surveillance.byd_pin_keep');
            if (saveBtn) saveBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> ' + BYD.i18n.t('surveillance.update_credentials');
        } else {
            this.isConfigured = status.configured || false;
            if (status.configured && !status.verified) {
                if (badge) { badge.textContent = BYD.i18n.t('surveillance.byd_badge_saved'); badge.className = 'status-badge inactive'; }
            } else {
                if (badge) { badge.textContent = BYD.i18n.t('surveillance.byd_badge_not_set'); badge.className = 'status-badge inactive'; }
            }
            if (info) info.style.display = 'none';
            if (clearSection) clearSection.style.display = 'none';
            if (testBtn) { testBtn.disabled = true; testBtn.style.color = 'var(--text-muted)'; testBtn.style.borderColor = 'var(--border-default)'; }
            if (pwdInput) pwdInput.placeholder = BYD.i18n.t('surveillance.byd_pwd_placeholder');
            if (pinInput) pinInput.placeholder = '123456';
            if (pwdHint) pwdHint.textContent = BYD.i18n.t('surveillance.byd_pwd_hint');
            if (pinHint) pinHint.textContent = BYD.i18n.t('surveillance.byd_pin_hint');
            if (saveBtn) saveBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> ' + BYD.i18n.t('surveillance.save_credentials');
        }

        BYD.surveillance.config.bydCloudEnabled = status.verified || false;
        BYD.surveillance.updateDeterrentUI();

        // Cloud push status
        var pushSection = document.getElementById('bydCloudPushSection');
        var mergeSection = document.getElementById('bydCloudMergeSection');
        if (status.verified && status.cloudPush) {
            var cp = status.cloudPush;
            if (pushSection) pushSection.style.display = 'block';
            if (mergeSection) mergeSection.style.display = 'block';

            var pushBadge = document.getElementById('bydPushBadge');
            var pushAge = document.getElementById('bydPushAge');
            var pushLock = document.getElementById('bydPushLock');
            var pushSoc = document.getElementById('bydPushSoc');
            var pushCharging = document.getElementById('bydPushCharging');

            if (pushBadge) {
                if (cp.connected && cp.lastMessageAge >= 0 && cp.lastMessageAge < 120) {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_live');
                    pushBadge.className = 'status-badge active';
                } else if (cp.connected && cp.lastMessageAge >= 0 && cp.lastMessageAge < 600) {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_ok');
                    pushBadge.className = 'status-badge active';
                } else if (cp.connected && cp.lastMessageAge >= 600) {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_stale');
                    pushBadge.className = 'status-badge inactive';
                } else if (cp.connected) {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_waiting');
                    pushBadge.className = 'status-badge inactive';
                } else {
                    pushBadge.textContent = BYD.i18n.t('surveillance.byd_badge_offline');
                    pushBadge.className = 'status-badge inactive';
                }
            }

            if (pushAge) {
                if (cp.lastMessageAge >= 0) {
                    var age = cp.lastMessageAge;
                    pushAge.textContent = age < 60 ? BYD.i18n.t('surveillance.byd_age_seconds', {n: age}) : BYD.i18n.t('surveillance.byd_age_minutes', {n: Math.floor(age / 60)});
                } else {
                    pushAge.textContent = cp.connected ? BYD.i18n.t('surveillance.byd_waiting_data') : '';
                }
            }

            if (pushLock) {
                if (cp.lockState && cp.lockState !== 'unknown') {
                    var lockIcon = cp.lockState === 'locked' ? '\uD83D\uDD12' : '\uD83D\uDD13';
                    pushLock.textContent = lockIcon + ' ' + cp.lockState;
                } else if (cp.connected && cp.lastMessageAge < 0) {
                    pushLock.textContent = BYD.i18n.t('surveillance.byd_waiting_tbox');
                } else {
                    pushLock.textContent = '';
                }
            }
            if (pushSoc && cp.socPercent != null) {
                pushSoc.textContent = '\uD83D\uDD0B ' + cp.socPercent + '%';
            } else if (pushSoc) {
                pushSoc.textContent = '';
            }
            if (pushCharging) {
                if (cp.chargingState && cp.chargingState !== 'unknown') {
                    var chgLabel = { 'not_charging': BYD.i18n.t('surveillance.byd_charge_not_charging'), 'charging': BYD.i18n.t('surveillance.byd_charge_charging') };
                    pushCharging.textContent = '\u26A1 ' + (chgLabel[cp.chargingState] || cp.chargingState);
                } else {
                    pushCharging.textContent = '';
                }
            }

            // Merge toggle
            var mergeToggle = document.getElementById('bydCloudMergeToggle');
            if (mergeToggle) mergeToggle.checked = cp.cloudDataMerge || false;
        } else {
            if (pushSection) pushSection.style.display = 'none';
            if (mergeSection) mergeSection.style.display = 'none';
        }
    },

    async saveCredentials() {
        const email = document.getElementById('bydEmail').value.trim();
        const password = document.getElementById('bydPassword').value.trim();
        const pin = document.getElementById('bydPin').value.trim();
        const region = document.getElementById('bydRegion').value;
        const saveBtn = document.getElementById('bydSaveBtn');
        // Preserve the icon + label so the finally block can restore it cleanly
        // after the request resolves; assigning .textContent strips the SVG.
        const saveBtnHtml = saveBtn ? saveBtn.innerHTML : null;

        if (!email) { this.showStatus(BYD.i18n.t('surveillance.byd_email_required'), 'error'); return; }
        if (!this.isConfigured && (!password || !pin)) {
            this.showStatus(BYD.i18n.t('surveillance.byd_pwd_pin_required'), 'error');
            return;
        }

        saveBtn.disabled = true;
        saveBtn.textContent = BYD.i18n.t('surveillance.byd_saving');
        this.showStatus(BYD.i18n.t('surveillance.byd_save_progress'), 'info');

        try {
            const body = { username: email, region: region };
            if (password) body.password = password;
            if (pin) body.controlPin = pin;

            const controller = new AbortController();
            const timeoutId = setTimeout(function() { controller.abort(); }, 30000);

            const resp = await fetch('/api/bydcloud/setup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
                signal: controller.signal
            });
            clearTimeout(timeoutId);
            const data = await resp.json();

            if (data.success) {
                this.showStatus(BYD.i18n.t('surveillance.byd_save_verified', {vin: data.vin}), 'success');
                document.getElementById('bydPassword').value = '';
                document.getElementById('bydPin').value = '';
            } else {
                this.showStatus('\u2717 ' + (data.error || BYD.i18n.t('surveillance.byd_save_failed')), 'error');
            }
        } catch (e) {
            if (e.name === 'AbortError') {
                this.showStatus(BYD.i18n.t('surveillance.byd_request_long'), 'info');
                // The server might still be processing — wait and check
                await new Promise(function(r) { setTimeout(r, 5000); });
            } else {
                this.showStatus('\u2717 ' + BYD.i18n.t('vehicle.network_error_msg', {message: e.message}), 'error');
            }
        } finally {
            saveBtn.disabled = false;
            if (saveBtnHtml != null) saveBtn.innerHTML = saveBtnHtml;
            await this.loadStatus();
        }
    },

    async testConnection() {
        const testBtn = document.getElementById('bydTestBtn');
        testBtn.disabled = true;
        testBtn.textContent = BYD.i18n.t('surveillance.byd_test_testing');
        this.showStatus(BYD.i18n.t('surveillance.byd_test_progress'), 'info');

        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(function() { controller.abort(); }, 30000);

            const resp = await fetch('/api/bydcloud/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action: 'flash_lights' }),
                signal: controller.signal
            });
            clearTimeout(timeoutId);
            const data = await resp.json();
            if (data.success) {
                this.showStatus(BYD.i18n.t('surveillance.byd_test_sent'), 'success');
            } else {
                this.showStatus('\u2717 ' + (data.error || BYD.i18n.t('surveillance.byd_test_failed')), 'error');
            }
        } catch (e) {
            if (e.name === 'AbortError') {
                this.showStatus(BYD.i18n.t('surveillance.byd_test_check_car'), 'info');
            } else {
                this.showStatus('\u2717 ' + e.message, 'error');
            }
        } finally {
            testBtn.disabled = false;
            testBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg> ' + BYD.i18n.t('surveillance.test_connection');
        }
    },

    async clearCredentials() {
        if (!confirm(BYD.i18n.t('surveillance.confirm_clear_byd_creds'))) return;

        try {
            await fetch('/api/bydcloud/clear', { method: 'POST' });
            this.showStatus(BYD.i18n.t('surveillance.byd_creds_cleared'), 'info');
            document.getElementById('bydEmail').value = '';
            document.getElementById('bydPassword').value = '';
            document.getElementById('bydPin').value = '';
            const deterrentSelect = document.getElementById('deterrentAction');
            if (deterrentSelect && deterrentSelect.value !== 'silent') {
                deterrentSelect.value = 'silent';
                BYD.surveillance.updateDeterrent('silent');
            }
            await this.loadStatus();
        } catch (e) {
            this.showStatus('\u2717 ' + e.message, 'error');
        }
    },

    showStatus(message, type) {
        const div = document.getElementById('bydCloudStatus');
        if (!div) return;
        div.style.display = 'block';
        div.textContent = message;
        const colors = {
            success: { bg: 'rgba(34,197,94,0.1)', border: '#22c55e', color: '#16a34a' },
            error:   { bg: 'rgba(239,68,68,0.1)', border: '#ef4444', color: '#dc2626' },
            info:    { bg: 'rgba(59,130,246,0.1)', border: '#3b82f6', color: '#2563eb' }
        };
        const c = colors[type] || colors.info;
        div.style.background = c.bg;
        div.style.borderLeft = '3px solid ' + c.border;
        div.style.color = c.color;
        if (type !== 'error') {
            setTimeout(function() { if (div.textContent === message) div.style.display = 'none'; }, 8000);
        }
    },

    togglePasswordVisibility(inputId, btn) {
        const input = document.getElementById(inputId);
        if (!input) return;
        if (input.type === 'password') {
            input.type = 'text';
            btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';
        } else {
            input.type = 'password';
            btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
        }
    },

    async toggleCloudDataMerge(enabled) {
        try {
            await fetch('/api/bydcloud/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ cloudDataMerge: enabled })
            });
        } catch (e) {
            console.warn('Failed to update cloud data merge:', e);
        }
    }
};
