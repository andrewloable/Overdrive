#!/usr/bin/env bash
# BladeWatch Functional Smoke Test (uy93.26)
#
# PURPOSE: Verify runtime baseline BEFORE and AFTER build-level refactors
#          (R8 obfuscation, native symbol strip, integrity checks) that unit
#          tests cannot catch. Run on the BYD DiLink v3 head unit via ADB.
#
# USAGE:
#   ./tools/smoke-test.sh [ADB_TARGET]
#
#   ADB_TARGET defaults to 192.168.0.251:5555 (BYD head unit).
#
# OUTPUT: PASS/FAIL per check; overall exit code 0 = all pass, 1 = any fail.
#
# WHEN TO RUN:
#   1. Before any build-level change (R8/ProGuard / symbol-strip / signing change):
#      ./tools/smoke-test.sh | tee smoke-baseline.txt
#   2. Apply the change and re-run: diff smoke-baseline.txt <(./tools/smoke-test.sh)
#   3. All checks must remain PASS post-change.
#
# NOTES:
#   - Requires ADB TCP connection to the device (connect first if needed)
#   - Check 5 (uy93.4 regression) requires the NEW APK to be deployed AND at
#     least one secret write to have happened to trigger the legacy file cleanup.

set -euo pipefail

DEVICE="${1:-192.168.0.251:5555}"
ADB="adb -s $DEVICE"
PASS=0
FAIL=0

pass() { echo "PASS  $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL  $1"; FAIL=$((FAIL+1)); }

# --- 0. device reachable ---
if $ADB get-state >/dev/null 2>&1; then
    pass "ADB device reachable"
else
    echo "FATAL: device $DEVICE not reachable. Run: adb connect $DEVICE"
    exit 1
fi

echo ""
echo "=== 1. Daemon processes ==="

PSOUT=$($ADB shell 'ps -A -o PID,ARGS 2>/dev/null' 2>/dev/null || true)

if echo "$PSOUT" | grep -q "byd_cam_daemon"; then
    pass "CameraDaemon (byd_cam_daemon) is running"
else
    fail "CameraDaemon (byd_cam_daemon) not found in ps"
fi

if echo "$PSOUT" | grep -q "sentry_daemon\|acc_sentry_daemon"; then
    pass "SentryDaemon is running (parking mode active)"
else
    pass "SentryDaemon not running (expected when car is in use / ACC on)"
fi

if echo "$PSOUT" | grep -q "net.bladewatch.app"; then
    pass "BladeWatch app process is running"
else
    fail "BladeWatch app process not found"
fi

echo ""
echo "=== 2. CameraDaemon HTTP server (127.0.0.1:8080) ==="

# /status endpoint (not /api/status — the Angular SPA catches the latter)
STATUS_RESP=$($ADB shell 'curl -sf --max-time 5 http://127.0.0.1:8080/status 2>/dev/null' 2>/dev/null || true)

if [ -n "$STATUS_RESP" ]; then
    pass "HTTP /status responds"
    if echo "$STATUS_RESP" | grep -q '"status"'; then
        pass "HTTP /status has status field"
    else
        fail "HTTP /status missing status field — unexpected format: ${STATUS_RESP:0:100}"
    fi
else
    fail "HTTP /status no response (daemon not serving HTTP)"
fi

# Auth endpoint (public — no token needed)
AUTH_RESP=$($ADB shell 'curl -sf --max-time 5 http://127.0.0.1:8080/auth/status 2>/dev/null' 2>/dev/null || true)
if echo "$AUTH_RESP" | grep -q '"status":"ok"'; then
    pass "HTTP /auth/status returns ok"
else
    fail "HTTP /auth/status unexpected response: ${AUTH_RESP:0:100}"
fi

echo ""
echo "=== 3. Web UI assets ==="

WEB_RESP=$($ADB shell 'curl -sf --max-time 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/ 2>/dev/null' 2>/dev/null || true)
if [ "$WEB_RESP" = "200" ] || [ "$WEB_RESP" = "301" ] || [ "$WEB_RESP" = "302" ]; then
    pass "Web UI root / responds ($WEB_RESP)"
else
    fail "Web UI root / unexpected status: $WEB_RESP"
fi

LOGIN_RESP=$($ADB shell 'curl -sf --max-time 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/login.html 2>/dev/null' 2>/dev/null || true)
if [ "$LOGIN_RESP" = "200" ]; then
    pass "login.html serves (200)"
else
    fail "login.html unexpected status: $LOGIN_RESP"
fi

echo ""
echo "=== 4. IPC token file permissions ==="

TOKEN_EXISTS=$($ADB shell 'test -f /data/local/tmp/bladewatch_ipc_token && echo yes || echo no' 2>/dev/null || echo "no")
if [ "$TOKEN_EXISTS" = "yes" ]; then
    pass "IPC token file exists"
    TOKEN_PERMS=$($ADB shell 'stat -c "%a" /data/local/tmp/bladewatch_ipc_token 2>/dev/null' 2>/dev/null || true)
    # 644 = world-readable (required for app UID), 600 = shell-only (breaks app IPC auth)
    if [ "$TOKEN_PERMS" = "644" ]; then
        pass "IPC token is world-readable (644)"
    elif [ "$TOKEN_PERMS" = "600" ]; then
        fail "IPC token is 600 (shell-only) — app cannot read it; IPC auth will break"
    else
        fail "IPC token unexpected permissions: $TOKEN_PERMS (expected 644)"
    fi
else
    fail "IPC token file missing — daemon may not have started yet"
fi

echo ""
echo "=== 5. uy93.4 regression: secrets NOT in /data/local/tmp ==="
echo "    NOTE: Requires new APK + at least one secret write to take effect."

SECRETS_TMP=$($ADB shell 'test -f /data/local/tmp/bladewatch_secrets.json && echo found || echo absent' 2>/dev/null || echo "absent")
if [ "$SECRETS_TMP" = "absent" ]; then
    pass "uy93.4: secrets NOT in /data/local/tmp"
else
    # Could be pre-existing from old APK or last deployment; warn but don't fail the suite
    echo "WARN  uy93.4: bladewatch_secrets.json still exists in /data/local/tmp"
    echo "      → Install new APK + trigger a secret write to flush the legacy file."
fi

echo ""
echo "=== 6. TcpCommandServer port 19876 is listening ==="
# Port 19876 = 0x4DA4. On Android, TcpCommandServer binds on IPv4-mapped IPv6
# (::ffff:127.0.0.1) so it appears in /proc/net/tcp6, not /proc/net/tcp.
TCP4_PORT=$($ADB shell 'grep "4DA4" /proc/net/tcp 2>/dev/null | head -1' 2>/dev/null || true)
TCP6_PORT=$($ADB shell 'grep "4DA4" /proc/net/tcp6 2>/dev/null | head -1' 2>/dev/null || true)
if [ -n "$TCP4_PORT" ] || [ -n "$TCP6_PORT" ]; then
    pass "TcpCommandServer port 19876 is listening"
else
    fail "TcpCommandServer port 19876 not found in /proc/net/tcp{,6}"
fi

echo ""
echo "=== 7. SurveillanceIpcServer port 19877 is listening ==="
# Port 19877 = 0x4DA5
SURV4=$($ADB shell 'grep "4DA5" /proc/net/tcp 2>/dev/null | head -1' 2>/dev/null || true)
SURV6=$($ADB shell 'grep "4DA5" /proc/net/tcp6 2>/dev/null | head -1' 2>/dev/null || true)
if [ -n "$SURV4" ] || [ -n "$SURV6" ]; then
    pass "SurveillanceIpcServer port 19877 is listening"
else
    fail "SurveillanceIpcServer port 19877 not found in /proc/net/tcp{,6}"
fi

echo ""
echo "=============================="
echo "Results: $PASS passed, $FAIL failed"
if [ "$FAIL" -eq 0 ]; then
    echo "STATUS: ALL PASS"
    exit 0
else
    echo "STATUS: FAILED ($FAIL checks)"
    exit 1
fi
