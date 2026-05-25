/**
 * BYD Champ - Client-side Auth Helper.
 *
 * Session state lives in HttpOnly cookies. JavaScript only keeps a
 * non-secret "authenticated" hint so synchronous UI guards still work.
 */

const BYDAuth = {
    COOKIE_NAME: 'byd_auth',
    LEGACY_STORAGE_KEY: 'byd_jwt',

    isAuthenticated: function() {
        return this._getCookieValue(this.COOKIE_NAME) === '1';
    },

    getToken: function() {
        return null;
    },

    setToken: function() {
        this._setHintCookie(365);
        try {
            localStorage.removeItem(this.LEGACY_STORAGE_KEY);
        } catch (e) {
            // Ignore
        }
    },

    clearToken: function() {
        this._setHintCookie(0);
        try {
            localStorage.removeItem(this.LEGACY_STORAGE_KEY);
        } catch (e) {
            // Ignore
        }
    },

    getAuthHeaders: function() {
        return {};
    },

    fetch: async function(url, options = {}) {
        const response = await fetch(url, {
            ...options,
            credentials: options.credentials || 'same-origin'
        });

        if (response.status === 401) {
            const currentPath = window.location.pathname + window.location.search;
            window.location.href = '/login.html?redirect=' + encodeURIComponent(currentPath);
            throw new Error('Unauthorized');
        }

        return response;
    },

    checkStatus: async function() {
        try {
            const response = await fetch('/auth/status', { credentials: 'same-origin' });
            return await response.json();
        } catch (e) {
            console.error('Auth status check failed:', e);
            return { deviceId: null };
        }
    },

    logout: async function() {
        try {
            await fetch('/auth/logout', {
                method: 'POST',
                credentials: 'same-origin'
            });
        } catch (e) {
            // Ignore errors
        }
        this.clearToken();
        window.location.href = '/login.html';
    },

    requireAuth: function() {
        if (!this.isAuthenticated()) {
            const currentPath = window.location.pathname + window.location.search;
            window.location.href = '/login.html?redirect=' + encodeURIComponent(currentPath);
            return false;
        }
        return true;
    },

    _setHintCookie: function(maxAgeDays) {
        const maxAge = maxAgeDays * 24 * 60 * 60;
        const isSecure = window.location.protocol === 'https:';
        const sameSite = isSecure ? 'Lax; Secure' : 'Lax';
        document.cookie = `${this.COOKIE_NAME}=1; path=/; max-age=${maxAge}; SameSite=${sameSite}`;
    },

    _getCookieValue: function(name) {
        const cookies = document.cookie ? document.cookie.split(';') : [];
        for (let cookie of cookies) {
            const [cookieName, ...rest] = cookie.trim().split('=');
            if (cookieName === name) {
                return rest.join('=');
            }
        }
        return null;
    }
};

(function() {
    try {
        localStorage.removeItem(BYDAuth.LEGACY_STORAGE_KEY);
    } catch (e) {
        // Ignore
    }
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = BYDAuth;
}
