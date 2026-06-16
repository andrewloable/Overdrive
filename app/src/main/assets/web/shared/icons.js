/**
 * BYD Champ - SVG Icons
 * Professional automotive-style icon set
 */

window.Icons = {
    // === TOP-DOWN CAR CAMERA VIEWS (Tesla-style) ===
    
    // Base car outline (top-down view)
    carTopBase: `<svg viewBox="0 0 32 32" fill="none">
        <path d="M10 6h12v2l2 2v12l-2 2v2H10v-2l-2-2V10l2-2V6z" stroke="currentColor" stroke-width="1.2" fill="none"/>
        <path d="M12 8h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M12 20h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
    </svg>`,
    
    // All cameras (filled car)
    camAll: `<svg viewBox="0 0 32 32" fill="none">
        <path d="M10 6h12v2l2 2v12l-2 2v2H10v-2l-2-2V10l2-2V6z" stroke="var(--brand-primary)" stroke-width="1.2" fill="rgba(0,212, 255,0.15)"/>
        <path d="M12 8h8v4H12z" stroke="var(--brand-primary)" stroke-width="0.8" opacity="0.6"/>
        <path d="M12 20h8v4H12z" stroke="var(--brand-primary)" stroke-width="0.8" opacity="0.6"/>
    </svg>`,
    
    // Front camera (top edge highlighted)
    camFront: `<svg viewBox="0 0 32 32" fill="none">
        <path d="M10 6h12v2l2 2v12l-2 2v2H10v-2l-2-2V10l2-2V6z" stroke="currentColor" stroke-width="1.2" fill="none"/>
        <path d="M12 8h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M12 20h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M10 6h12" stroke="var(--brand-primary)" stroke-width="3" stroke-linecap="round"/>
    </svg>`,
    
    // Rear camera (bottom edge highlighted)
    camRear: `<svg viewBox="0 0 32 32" fill="none">
        <path d="M10 6h12v2l2 2v12l-2 2v2H10v-2l-2-2V10l2-2V6z" stroke="currentColor" stroke-width="1.2" fill="none"/>
        <path d="M12 8h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M12 20h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M10 26h12" stroke="var(--brand-primary)" stroke-width="3" stroke-linecap="round"/>
    </svg>`,
    
    // Left camera (left edge highlighted)
    camLeft: `<svg viewBox="0 0 32 32" fill="none">
        <path d="M10 6h12v2l2 2v12l-2 2v2H10v-2l-2-2V10l2-2V6z" stroke="currentColor" stroke-width="1.2" fill="none"/>
        <path d="M12 8h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M12 20h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M8 10v12" stroke="var(--brand-primary)" stroke-width="3" stroke-linecap="round"/>
    </svg>`,
    
    // Right camera (right edge highlighted)
    camRight: `<svg viewBox="0 0 32 32" fill="none">
        <path d="M10 6h12v2l2 2v12l-2 2v2H10v-2l-2-2V10l2-2V6z" stroke="currentColor" stroke-width="1.2" fill="none"/>
        <path d="M12 8h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M12 20h8v4H12z" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
        <path d="M24 10v12" stroke="var(--brand-primary)" stroke-width="3" stroke-linecap="round"/>
    </svg>`,

    // === NAVIGATION ===
    live: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/>
        <path d="M2 12a9 9 0 0 0 8 8"/>
        <circle cx="2" cy="12" r="2"/>
    </svg>`,
    
    shield: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
    </svg>`,
    
    video: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="m22 8-6 4 6 4V8Z"/>
        <rect width="14" height="12" x="2" y="6" rx="2" ry="2"/>
    </svg>`,
    
    camera: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/>
        <circle cx="12" cy="13" r="3"/>
    </svg>`,

    // === DIRECTIONAL ===
    arrowUp: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m18 15-6-6-6 6"/></svg>`,
    arrowDown: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m6 9 6 6 6-6"/></svg>`,
    arrowLeft: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>`,
    arrowRight: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>`,
    
    grid: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <rect width="7" height="7" x="3" y="3" rx="1"/>
        <rect width="7" height="7" x="14" y="3" rx="1"/>
        <rect width="7" height="7" x="14" y="14" rx="1"/>
        <rect width="7" height="7" x="3" y="14" rx="1"/>
    </svg>`,

    // === STATUS ===
    battery: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <rect width="16" height="10" x="2" y="7" rx="2"/>
        <line x1="22" x2="22" y1="11" y2="13"/>
    </svg>`,
    
    zap: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
    </svg>`,
    
    clock: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="10"/>
        <polyline points="12 6 12 12 16 14"/>
    </svg>`,

    // === ACTIONS ===
    play: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>`,
    pause: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="4" height="16" x="6" y="4"/><rect width="4" height="16" x="14" y="4"/></svg>`,
    record: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="4" fill="currentColor"/></svg>`,
    stop: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="14" height="14" x="5" y="5" rx="2"/></svg>`,
    
    maximize: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/>
        <path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/>
    </svg>`,
    
    minimize: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M8 3v3a2 2 0 0 1-2 2H3"/><path d="M21 8h-3a2 2 0 0 1-2-2V3"/>
        <path d="M3 16h3a2 2 0 0 1 2 2v3"/><path d="M16 21v-3a2 2 0 0 1 2-2h3"/>
    </svg>`,
    
    x: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>`,
    minus: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14"/></svg>`,
    menu: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="4" x2="20" y1="12" y2="12"/><line x1="4" x2="20" y1="6" y2="6"/><line x1="4" x2="20" y1="18" y2="18"/></svg>`,
    check: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>`,

    // === DETECTION ===
    target: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/>
    </svg>`,
    
    user: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/>
        <circle cx="12" cy="7" r="4"/>
    </svg>`,
    
    car: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2"/>
        <circle cx="7" cy="17" r="2"/><circle cx="17" cy="17" r="2"/>
    </svg>`,
    
    bike: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="18.5" cy="17.5" r="3.5"/><circle cx="5.5" cy="17.5" r="3.5"/>
        <circle cx="15" cy="5" r="1"/><path d="M12 17.5V14l-3-3 4-3 2 3h2"/>
    </svg>`,

    // === STORAGE ===
    hardDrive: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <line x1="22" x2="2" y1="12" y2="12"/>
        <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>
        <line x1="6" x2="6.01" y1="16" y2="16"/><line x1="10" x2="10.01" y1="16" y2="16"/>
    </svg>`
};

// Helper to get icon with optional class
Icons.get = function(name, className = '') {
    const svg = this[name] || '';
    if (className && svg) {
        return svg.replace('<svg', `<svg class="${className}"`);
    }
    return svg;
};
