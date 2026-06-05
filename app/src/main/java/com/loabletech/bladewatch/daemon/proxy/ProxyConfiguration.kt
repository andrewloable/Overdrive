package com.overdrive.app.daemon.proxy

/**
 * Proxy configuration for VLESS Reality proxy.
 * 
 * Sensitive credentials are stored with AES-256-CBC encryption
 * using stack-based key reconstruction (SOTA Java obfuscation).
 * 
 * To add/update proxy credentials:
 * 1. Add values to secrets.json under "proxy" section
 * 2. Run: python generate_safe_enc.py
 * 3. Use Enc.PROXY_SERVER_IP etc. here
 * 
 * @see Safe
 * @see Enc
 */
object ProxyConfiguration {
    
    // Server configuration - add these to secrets.json and regenerate Enc.java
    // For now using placeholders - replace with Enc.PROXY_* after adding to secrets.json
    val SERVER_IP: String get() = Enc.PROXY_SERVER_IP
    val SERVER_PORT: Int get() = Enc.PROXY_SERVER_PORT.toIntOrNull() ?: 443
    val UUID: String get() = Enc.PROXY_UUID
    val SHORT_ID: String get() = Enc.PROXY_SHORT_ID
    val PUBLIC_KEY: String get() = Enc.PROXY_PUBLIC_KEY
    private val SNI: String get() = Enc.PROXY_SNI
    
    // Proxy configuration
    const val PROXY_PORT = 8119
    val PROXY_EXCLUSIONS: String get() = Enc.PROXY_EXCLUSIONS
    
    // Paths - use encrypted constants
    val CONFIG_PATH: String get() = Enc.SINGBOX_CONFIG
    val LOG_PATH: String get() = Enc.SINGBOX_LOG
    val SINGBOX_PATH: String get() = Enc.SINGBOX_BIN
    
    /**
     * Generate sing-box configuration JSON for proxy mode.
     */
    fun createConfig(): String {
        val serverIp = SERVER_IP
        val serverPort = SERVER_PORT
        val uuid = UUID
        val publicKey = PUBLIC_KEY
        val shortId = SHORT_ID
        val sni = SNI
        val logPath = LOG_PATH
        
        return """
{
  "log": { "level": "error", "timestamp": true, "output": "$logPath" },
  "dns": {
    "servers": [
      { "tag": "google", "address": "8.8.8.8", "detour": "proxy" }
    ],
    "final": "google",
    "strategy": "ipv4_only"
  },
  "inbounds": [
    {
      "type": "mixed",
      "tag": "mixed-in",
      "listen": "${Enc.LOCALHOST}",
      "listen_port": $PROXY_PORT,
      "sniff": true
    }
  ],
  "outbounds": [
    {
      "type": "${Enc.PROTO_VLESS}",
      "tag": "${Enc.OUTBOUND_PROXY}",
      "server": "$serverIp",
      "server_port": $serverPort,
      "uuid": "$uuid",
      "flow": "${Enc.FLOW_XTLS}",
      "domain_strategy": "ipv4_only",
      "tcp_fast_open": false,
      "connect_timeout": "3s",
      "handshake_timeout": "3s",
      "multiplex": {
        "enabled": true,
        "protocol": "h2mux",
        "max_connections": 1, 
        "min_streams": 2,
        "padding": true
      },
      "tls": {
        "enabled": true,
        "server_name": "$sni",
        "utls": { "enabled": true, "fingerprint": "${Enc.FINGERPRINT_CHROME}" },
        "reality": {
          "enabled": true,
          "public_key": "$publicKey",
          "short_id": "$shortId"
        }
      }
    },
    { "type": "${Enc.OUTBOUND_DIRECT}", "tag": "${Enc.OUTBOUND_DIRECT}" }
  ],
  "route": {
    "auto_detect_interface": true,
    "override_android_vpn": true,
    "rules": [
      { "protocol": "dns", "outbound": "${Enc.OUTBOUND_PROXY}" }
    ]
  }
}
    """.trimIndent()
    }
}
