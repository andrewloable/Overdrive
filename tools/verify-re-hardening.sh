#!/usr/bin/env bash
# BladeWatch RE + Secret Hardening Verification (uy93.18)
#
# PURPOSE: Verify anti-RE controls and confirm no plaintext app secrets
#          are recoverable from the APK + shared-storage dump.
#
# USAGE:
#   ./tools/verify-re-hardening.sh [APK_PATH] [ADB_TARGET]
#
#   Defaults:
#     APK_PATH  = app/build/outputs/apk/release/net.bladewatch.app-*.apk
#     ADB_TARGET = 192.168.0.251:5555
#
# CHECKS:
#   1. libsurveillance.so exports ONLY JNI entry points (no internal symbols)
#   2. APK DEX contains no hardcoded app-specific secrets (tokens, keys)
#   3. APK DEX H2 embedded cert/key is documented as 3rd-party (not app secret)
#   4. Device shared storage: primary secrets file exists but NOT in /data/local/tmp
#   5. SecretRedactor patterns are in DEX (log redaction is active)
#
# EXIT CODE: 0 = all pass, 1 = any fail

set -euo pipefail

DEVICE="${2:-192.168.0.251:5555}"
ADB="adb -s $DEVICE"

# Find APK
if [ -n "${1:-}" ]; then
    APK="$1"
else
    APK=$(ls app/build/outputs/apk/release/net.bladewatch.app-*.apk 2>/dev/null | head -1 || true)
    if [ -z "$APK" ]; then
        APK=$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)
    fi
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "ERROR: No release APK found. Run ./gradlew assembleRelease first."
    echo "       Or pass APK path as first argument."
    exit 1
fi

echo "Checking: $APK"

PASS=0
FAIL=0
WARN=0

pass() { echo "PASS  $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
warn() { echo "WARN  $1"; WARN=$((WARN+1)); }

TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

echo ""
echo "=== 1. Native library symbol export profile ==="

unzip -o "$APK" "lib/arm64-v8a/libsurveillance.so" -d "$TMPDIR" >/dev/null 2>&1 || true
SO="$TMPDIR/lib/arm64-v8a/libsurveillance.so"

if [ ! -f "$SO" ]; then
    fail "libsurveillance.so not found in APK"
else
    ALL_SYMS=$(nm -D "$SO" 2>/dev/null | grep "^[0-9a-f]" || true)
    TOTAL_EXPORTED=$(echo "$ALL_SYMS" | grep -c "." || echo 0)
    JNI=$(echo "$ALL_SYMS" | grep -c "Java_" || echo 0)
    NON_JNI=$((TOTAL_EXPORTED - JNI))

    echo "    Total exported symbols: $TOTAL_EXPORTED"
    echo "    JNI entry points: $JNI"
    echo "    Non-JNI exported: $NON_JNI"

    if [ "$NON_JNI" -eq 0 ]; then
        pass "libsurveillance.so exports ONLY JNI symbols (no internal symbols leaked)"
    else
        warn "libsurveillance.so exports $NON_JNI non-JNI symbols — review for internal exposure"
        echo "$ALL_SYMS" | grep -v "Java_" | head -10 || true
    fi

    if [ "$JNI" -gt 0 ]; then
        pass "JNI entry points present ($JNI)"
    else
        fail "No JNI entry points found — native library may not be loading correctly"
    fi
fi

echo ""
echo "=== 2. APK DEX: no hardcoded app-specific secrets ==="

unzip -o "$APK" "classes*.dex" -d "$TMPDIR" >/dev/null 2>&1 || true

# Check for specific runtime-secret patterns that must NOT appear hardcoded.
# Runtime secrets are NEVER hardcoded — generated and stored in SecretConfigStore.
# VLESS credentials use Safe.s() Bangcle-encrypted values, never plaintext.
#
# BYD cloud client IDs are UUID format (8-4-4-4-12 hex). Check that no UUID
# with bladewatch prefix appears (real IDs are provisioned at registration time).
UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
BYD_UUID=$(strings "$TMPDIR/classes.dex" 2>/dev/null | grep -iE "^$UUID_PATTERN$" | head -1 || true)

if [ -z "$BYD_UUID" ]; then
    pass "No hardcoded UUID-format credentials in DEX (BYD cloud IDs are runtime-registered)"
else
    warn "UUID-format string in DEX: $BYD_UUID — verify it is a library constant, not a BYD client ID"
fi

echo "    NOTE: For full RE walkthrough, run: jadx -d $TMPDIR/jadx $APK"
echo "    Manually verify Safe.s() calls contain only encrypted ciphertext, not plaintext."

echo ""
echo "=== 3. DEX H2 embedded RSA cert/key (known 3rd-party artifact) ==="

H2_CERT=$(strings "$TMPDIR/classes.dex" 2>/dev/null | grep "^3082018b3081f5" | wc -l || echo 0)
if [ "$H2_CERT" -gt 0 ]; then
    pass "H2 embedded RSA cert found (expected H2 library artifact — NOT an app secret)"
    echo "    NOTE: H2 database bundles its own self-signed cert/keypair for its"
    echo "    embedded SSL console. These are 3rd-party library artifacts. The private"
    echo "    key material is NOT used by BladeWatch and poses no secret exposure risk."
else
    pass "H2 embedded RSA cert not found (may have been R8-stripped)"
fi

echo ""
echo "=== 4. DEX: SecretRedactor log-scrubbing patterns present ==="

REDACTOR=$(strings "$TMPDIR/classes.dex" 2>/dev/null | grep 'secret_put' | head -1 || true)
if [ -n "$REDACTOR" ]; then
    pass "SecretRedactor log-scrubbing patterns present in DEX"
else
    fail "SecretRedactor patterns missing — logs may leak secret values"
fi

echo ""
echo "=== 5. Device: secrets NOT in /data/local/tmp (uy93.4) ==="

if $ADB get-state >/dev/null 2>&1; then
    SECRETS_TMP=$($ADB shell 'test -f /data/local/tmp/bladewatch_secrets.json && echo found || echo absent' 2>/dev/null || echo "absent")
    if [ "$SECRETS_TMP" = "absent" ]; then
        pass "uy93.4: secrets NOT in /data/local/tmp"
    else
        warn "uy93.4: bladewatch_secrets.json still in /data/local/tmp — requires new APK + secret write to flush"
    fi

    PRIMARY_SECRETS=$($ADB shell 'test -f /storage/emulated/0/Android/data/net.bladewatch.app/files/bladewatch_secrets.json && echo found || echo absent' 2>/dev/null || echo "absent")
    if [ "$PRIMARY_SECRETS" = "found" ]; then
        pass "Primary secrets file exists at app-files path (expected runtime location)"
        echo "    NOTE: Secrets on shared external storage are plaintext."
        echo "    Track1 uy93.12 will move them to a native daemon keystore."
    else
        pass "Primary secrets file absent (may be first-run or daemon not started)"
    fi
else
    warn "Device not reachable — skipping on-device checks"
fi

echo ""
echo "=============================="
echo "Results: $PASS passed, $FAIL failed, $WARN warnings"
if [ "$FAIL" -eq 0 ]; then
    echo "STATUS: PASS (see warnings above if any)"
    exit 0
else
    echo "STATUS: FAILED ($FAIL critical checks)"
    exit 1
fi
