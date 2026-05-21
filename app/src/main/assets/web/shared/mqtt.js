/**
 * OverDrive - MQTT Connections Module
 * Manages multiple MQTT broker connections, configuration, and live status display.
 */

const MQTT = {
    connections: [],
    maxConnections: 5,
    refreshInterval: null,
    expandedId: null,
    editingId: null,

    init() {
        // Render once up front so the Connections tab shows the empty state +
        // CTA on first paint, even before the API call lands. Otherwise the
        // user sees a blank panel until /api/mqtt/connections resolves.
        this.render();
        this.loadConnections();
        this.loadTelemetry();
        this.startAutoRefresh();
    },

    // ==================== DATA LOADING ====================

    async loadConnections() {
        try {
            const resp = await fetch('/api/mqtt/connections');
            const data = await resp.json();
            if (data && data.success) {
                this.connections = data.connections || [];
                this.maxConnections = data.maxConnections || 5;
            } else {
                this.connections = [];
            }
        } catch (e) {
            console.warn('[MQTT] Failed to load connections:', e);
            this.connections = [];
        }
        // Always render — even on failure — so the empty state appears
        // instead of a blank panel that looks broken.
        this.render();
    },

    async loadStatus() {
        try {
            const resp = await fetch('/api/mqtt/status');
            const data = await resp.json();
            if (data && data.success && data.connections) {
                this.connections = data.connections;
                this.render();
            }
        } catch (e) {
            console.warn('[MQTT] Failed to load status:', e);
        }
    },

    async loadTelemetry() {
        try {
            const resp = await fetch('/api/mqtt/telemetry');
            const data = await resp.json();
            if (data.success && data.telemetry) {
                this.updateTelemetryTable(data.telemetry);
            }
        } catch (e) {
            console.warn('[MQTT] Failed to load telemetry:', e);
        }
    },

    startAutoRefresh() {
        this.refreshInterval = setInterval(() => {
            this.loadStatus();
            this.loadTelemetry();
        }, 5000);
    },

    // ==================== RENDERING ====================

    render() {
        const list = document.getElementById('connectionList');
        const empty = document.getElementById('emptyState');
        if (!list || !empty) return;

        if (this.connections.length === 0) {
            list.innerHTML = '';
            empty.style.display = 'block';
            return;
        }

        empty.style.display = 'none';
        list.innerHTML = this.connections.map(conn => this.renderConnection(conn)).join('');

        // Disable the empty-state CTA's "Add" button if the user already has
        // the max number of connections — they can still hit it from this
        // page only via the Add tab, where showAddForm() will toast instead.
        // (No persistent Add button anywhere else now.)
    },

    renderConnection(conn) {
        const s = conn.status || {};
        const isConnected = s.connected || false;
        const isRunning = s.running || false;
        const isExpanded = this.expandedId === conn.id;

        let dotClass = 'stopped';
        let statusText = BYD.i18n.t('mqtt.status_stopped');
        if (conn.enabled && isConnected) {
            dotClass = 'connected';
            statusText = BYD.i18n.t('mqtt.status_connected');
        } else if (conn.enabled && isRunning && !isConnected) {
            dotClass = 'reconnecting';
            statusText = BYD.i18n.t('mqtt.status_reconnecting');
        } else if (conn.enabled && !isRunning) {
            dotClass = 'disconnected';
            statusText = BYD.i18n.t('mqtt.status_disconnected');
        }

        const totalPub = s.totalPublishes || 0;
        const failedPub = s.failedPublishes || 0;
        const lastPub = s.lastPublishTime && s.lastPublishTime > 0
            ? new Date(s.lastPublishTime).toLocaleTimeString(BYD.i18n.getLang()) : BYD.i18n.t('mqtt.never');
        const lastErr = s.lastError || '';

        const editIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
        const deleteIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
        const enabledHtml = conn.enabled
            ? '<span class="status-dot connected"></span><span>' + BYD.i18n.t('common.enabled') + '</span>'
            : '<span class="status-dot off"></span><span>' + BYD.i18n.t('common.disabled') + '</span>';
        return `
        <div class="card conn-card" style="${!conn.enabled ? 'opacity:0.6;' : ''}">
            <div class="conn-header" onclick="MQTT.toggleExpand('${conn.id}')">
                <span class="conn-dot ${dotClass}"></span>
                <div class="conn-info">
                    <div class="conn-name">${this.esc(conn.name || BYD.i18n.t('mqtt.unnamed'))}</div>
                    <div class="conn-broker">${this.esc(conn.brokerUrl || '')}:${conn.port} → ${this.esc(conn.topic || '')}</div>
                </div>
                <div class="conn-actions" onclick="event.stopPropagation()">
                    <button class="icon-btn" onclick="MQTT.editConnection('${conn.id}')" title="${BYD.i18n.t('common.edit')}" aria-label="${BYD.i18n.t('common.edit')}">${editIcon}</button>
                    <button class="icon-btn danger" onclick="MQTT.deleteConnection('${conn.id}')" title="${BYD.i18n.t('common.delete')}" aria-label="${BYD.i18n.t('common.delete')}">${deleteIcon}</button>
                </div>
            </div>
            <div style="display:flex;align-items:center;justify-content:space-between;padding:0 16px 12px;gap:8px;" onclick="event.stopPropagation()">
                <div style="display:inline-flex;align-items:center;gap:8px;font-size:13px;color:var(--text-secondary);">${enabledHtml}</div>
                <label class="toggle-switch">
                    <input type="checkbox" ${conn.enabled ? 'checked' : ''} onchange="MQTT.toggleEnabled('${conn.id}', this.checked)">
                    <span class="toggle-slider"></span>
                </label>
            </div>
            <div class="conn-detail ${isExpanded ? 'open' : ''}" id="detail-${conn.id}">
                <div class="conn-stats">
                    <div class="conn-stat"><div class="label">${BYD.i18n.t('mqtt.label_status')}</div><div class="value">${statusText}</div></div>
                    <div class="conn-stat"><div class="label">${BYD.i18n.t('mqtt.label_last_publish')}</div><div class="value">${lastPub}</div></div>
                    <div class="conn-stat"><div class="label">${BYD.i18n.t('mqtt.label_published')}</div><div class="value">${totalPub}</div></div>
                    <div class="conn-stat"><div class="label">${BYD.i18n.t('mqtt.label_failed')}</div><div class="value" style="${failedPub > 0 ? 'color:var(--danger)' : ''}">${failedPub}</div></div>
                </div>
                ${lastErr ? `<div style="font-size:12px;color:var(--danger);padding:8px 0;">${this.esc(lastErr)}</div>` : ''}
                <div style="font-size:12px;color:var(--text-muted);display:flex;gap:16px;flex-wrap:wrap;">
                    <span>${BYD.i18n.t('mqtt.label_qos')}: ${conn.qos}</span>
                    <span>${BYD.i18n.t('mqtt.label_interval')}: ${conn.publishIntervalSeconds}s${conn.adaptiveInterval ? ' (' + BYD.i18n.t('mqtt.adaptive') + ')' : ''}</span>
                    <span>${BYD.i18n.t('mqtt.label_retain')}: ${conn.retainMessages ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')}</span>
                    <span>${BYD.i18n.t('mqtt.label_proxy')}: ${s.proxyActive ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')}</span>
                </div>
            </div>
        </div>`;
    },

    toggleExpand(id) {
        this.expandedId = this.expandedId === id ? null : id;
        this.render();
    },

    // ==================== FORM ====================

    // The form is rendered on its own tab now (data-tab="add"), so showing /
    // hiding maps onto a tab switch instead of an inline display toggle.
    _switchTab(id) {
        if (typeof window.OT_setActiveTab === 'function') window.OT_setActiveTab(id);
    },

    showAddForm() {
        if (this.connections.length >= this.maxConnections) {
            this.toast(BYD.i18n.t('mqtt.max_reached', {n: this.maxConnections}), 'error');
            return;
        }
        this.editingId = null;
        var titleEl = document.getElementById('formTitle');
        if (titleEl) titleEl.textContent = BYD.i18n.t('mqtt.add_connection');
        document.getElementById('formId').value = '';
        document.getElementById('formName').value = '';
        document.getElementById('formBrokerUrl').value = '';
        document.getElementById('formPort').value = '1883';
        document.getElementById('formTopic').value = 'overdrive/vehicle/telemetry';
        document.getElementById('formUsername').value = '';
        document.getElementById('formPassword').value = '';
        document.getElementById('formClientId').value = '';
        document.getElementById('formQos').value = '0';
        document.getElementById('formInterval').value = '5';
        document.getElementById('formAdaptive').checked = true;
        document.getElementById('formRetain').checked = false;
        document.getElementById('formEnabled').checked = true;
        this._switchTab('add');
        var nameEl = document.getElementById('formName');
        if (nameEl) nameEl.focus();
    },

    editConnection(id) {
        const conn = this.connections.find(c => c.id === id);
        if (!conn) return;

        this.editingId = id;
        var titleEl = document.getElementById('formTitle');
        if (titleEl) titleEl.textContent = BYD.i18n.t('mqtt.edit_connection');
        document.getElementById('formId').value = conn.id;
        document.getElementById('formName').value = conn.name || '';
        document.getElementById('formBrokerUrl').value = conn.brokerUrl || '';
        document.getElementById('formPort').value = conn.port || 1883;
        document.getElementById('formTopic').value = conn.topic || '';
        document.getElementById('formUsername').value = conn.username || '';
        document.getElementById('formPassword').value = '';  // Don't prefill password
        document.getElementById('formClientId').value = conn.clientId || '';
        document.getElementById('formQos').value = conn.qos || 0;
        document.getElementById('formInterval').value = conn.publishIntervalSeconds || 5;
        document.getElementById('formAdaptive').checked = conn.adaptiveInterval !== false;
        document.getElementById('formRetain').checked = conn.retainMessages || false;
        document.getElementById('formEnabled').checked = conn.enabled || false;
        this._switchTab('add');
        var nameEl = document.getElementById('formName');
        if (nameEl) nameEl.focus();
    },

    hideForm() {
        // Cancel returns to the Connections list. editingId is reset so a
        // subsequent tap on the empty-state CTA opens a fresh form.
        this.editingId = null;
        this._switchTab('connections');
    },

    async saveForm() {
        const data = {
            name: document.getElementById('formName').value.trim(),
            brokerUrl: document.getElementById('formBrokerUrl').value.trim(),
            port: parseInt(document.getElementById('formPort').value) || 1883,
            topic: document.getElementById('formTopic').value.trim(),
            username: document.getElementById('formUsername').value.trim(),
            password: document.getElementById('formPassword').value,
            clientId: document.getElementById('formClientId').value.trim(),
            qos: parseInt(document.getElementById('formQos').value) || 0,
            publishIntervalSeconds: parseInt(document.getElementById('formInterval').value) || 5,
            adaptiveInterval: document.getElementById('formAdaptive').checked,
            retainMessages: document.getElementById('formRetain').checked,
            enabled: document.getElementById('formEnabled').checked
        };

        if (!data.name) { this.toast(BYD.i18n.t('mqtt.err_name_required'), 'error'); return; }
        if (!data.brokerUrl) { this.toast(BYD.i18n.t('mqtt.err_broker_required'), 'error'); return; }
        if (!data.topic) { this.toast(BYD.i18n.t('mqtt.err_topic_required'), 'error'); return; }

        try {
            let resp;
            if (this.editingId) {
                resp = await fetch('/api/mqtt/connections/' + this.editingId, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
            } else {
                resp = await fetch('/api/mqtt/connections', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
            }

            const result = await resp.json();
            if (result.success) {
                this.toast(this.editingId ? BYD.i18n.t('mqtt.toast_updated') : BYD.i18n.t('mqtt.toast_added'), 'success');
                this.editingId = null;
                this.loadConnections();
                // Return to the Connections tab so the user sees the new entry
                // immediately. The list re-renders via loadConnections().
                this._switchTab('connections');
            } else {
                this.toast(result.error || BYD.i18n.t('errors.save_failed'), 'error');
            }
        } catch (e) {
            this.toast(BYD.i18n.t('mqtt.network_error', {message: e.message}), 'error');
        }
    },

    // ==================== ACTIONS ====================

    async toggleEnabled(id, enabled) {
        try {
            await fetch('/api/mqtt/connections/' + id, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled })
            });
            // Refresh after a short delay to show updated status
            setTimeout(() => this.loadStatus(), 1000);
        } catch (e) {
            this.toast(BYD.i18n.t('mqtt.toggle_failed'), 'error');
        }
    },

    async deleteConnection(id) {
        const conn = this.connections.find(c => c.id === id);
        const name = conn ? conn.name : id;
        if (!confirm(BYD.i18n.t('mqtt.confirm_delete', {name: name}))) return;

        try {
            const resp = await fetch('/api/mqtt/connections/' + id, { method: 'DELETE' });
            const result = await resp.json();
            if (result.success) {
                this.toast(BYD.i18n.t('mqtt.toast_deleted'), 'success');
                this.loadConnections();
            } else {
                this.toast(result.error || BYD.i18n.t('errors.delete_failed'), 'error');
            }
        } catch (e) {
            this.toast(BYD.i18n.t('mqtt.network_error', {message: e.message}), 'error');
        }
    },

    // ==================== TELEMETRY ====================

    updateTelemetryTable(t) {
        const fields = {
            tlm_utc:         t.utc != null ? new Date(t.utc * 1000).toLocaleTimeString(BYD.i18n.getLang()) : '--',
            tlm_soc:         t.soc != null ? t.soc.toFixed(1) + '%' : '--%',
            tlm_power:       t.power != null ? t.power.toFixed(1) + ' kW' : '-- kW',
            tlm_speed:       t.speed != null ? BYD.units.speed(t.speed) : '-- ' + BYD.units.speedLabel(),
            tlm_lat:         t.lat != null ? t.lat.toFixed(6) : '--',
            tlm_lon:         t.lon != null ? t.lon.toFixed(6) : '--',
            tlm_is_charging: t.is_charging != null ? (t.is_charging ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')) : '--',
            tlm_is_parked:   t.is_parked != null ? (t.is_parked ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')) : '--',
            tlm_elevation:   t.elevation != null ? t.elevation.toFixed(1) + ' m' : '-- m',
            tlm_gear:        t.gear || '--',
            tlm_ext_temp:    t.ext_temp != null ? t.ext_temp.toFixed(1) + ' °C' : '-- °C',
            tlm_batt_temp:   t.batt_temp != null ? t.batt_temp.toFixed(1) + ' °C' : '-- °C',
            tlm_odometer:    t.odometer != null ? BYD.units.dist(t.odometer, 1) : '-- ' + BYD.units.distLabel(),
            tlm_soh:         t.soh != null ? t.soh.toFixed(1) + '%' : '--%'
        };

        for (const [id, value] of Object.entries(fields)) {
            const el = document.getElementById(id);
            if (el) el.textContent = value;
        }
    },

    // ==================== UTILITIES ====================

    esc(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    },

    toast(message, type) {
        if (BYD.utils && BYD.utils.toast) {
            BYD.utils.toast(message, type === 'error' ? 'error' : 'success');
        } else {
            console.log('[MQTT] ' + type + ': ' + message);
        }
    }
};
