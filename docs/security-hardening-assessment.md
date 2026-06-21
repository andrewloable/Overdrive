# Security Hardening Assessment (uy93.6)

Manual assessment of the standard 16-control app-hardening framework against the
BladeWatch threat model. The `app-hardening` CLI was not available; this assessment
was performed manually against the same control inventory.

**Date:** 2026-06-21  
**APK under review:** `net.bladewatch.app-v1.0.1.0.apk`  
**Branch:** `flutter-refactor`

---

## Threat Model Recap

| Threat | Severity | In scope |
|---|---|---|
| Co-resident malicious app on BYD head unit reads world-readable IPC token then drives privileged IPC | **HIGH** | Yes — primary threat |
| Attacker on car LAN issues vehicle POST commands without a second factor | **HIGH** | Yes — mitigated by uy93.5 |
| Compromised BYD cloud relaying malicious MQTT commands | **MEDIUM** | Yes |
| APK reverse engineering to understand logic / steal secrets | **LOW** | **No** — app is open-source (GitHub) |
| Play Integrity / attestation bypass | **N/A** | No — not distributed on Play Store |
| Frida hooking by researcher | **LOW** | Low value: source is public |

**Key constraint:** BladeWatch is **open-source**. RE-cost controls (obfuscation, symbol
stripping, anti-debug) have near-zero threat-reduction value here — the logic is
already public. Treat them as **inventory items** (some may have secondary value),
not as primary security measures.

---

## 16-Control Assessment

### C1 · Code Obfuscation (R8/ProGuard)

| Item | Status | Notes |
|---|---|---|
| R8 enabled for release | ✅ Done | `minifyEnabled true` in release config |
| Log stripping (security-critical) | ✅ Done | `proguard-rules-strip-logs.pro` strips log calls in release; `DaemonLogConfig.java` controls verbosity |
| General identifier obfuscation | ⚠️ Partial | Default R8; no custom `-keep` rules for obfuscation coverage |

**Classification:** Identifier obfuscation = **theater** (open source). Log stripping = **real** (prevents live secret leakage via logcat, which is world-readable on Android 10 w/o logcat read permission… actually requires READ_LOGS or root. Still worth keeping).

**Recommendation:** No additional ProGuard work needed. Log stripping is sufficient.

---

### C2 · Native Symbol Stripping

| Item | Status | Notes |
|---|---|---|
| Release .so exported symbols | ✅ 27 JNI exports, 0 internal | Verified by `tools/verify-re-hardening.sh` |
| `-fvisibility=hidden` in CMakeLists | ❓ Not verified | May not be set explicitly; default CMake exports everything unless hidden |

**Classification:** Symbol strip = **theater** for open-source (JNI function names are derived from the class/method names which are in source). Still useful to reduce attack surface area for future closed-source modules.

**Gap:** Add `-fvisibility=hidden` to `CMakeLists.txt` and `__attribute__((visibility("default")))` only on JNI exports. Low priority — Track1 uy93.8.

---

### C3 · String Obfuscation

| Item | Status | Notes |
|---|---|---|
| Hardcoded secrets | ✅ None | Verified: no UUID credentials, no runtime tokens |
| `Safe.s()` for VLESS credentials | ✅ Present | Bangcle-encrypted values in DEX |
| Sensitive config keys as strings | ⚠️ Present | e.g., `"deviceSecret"`, `"zrokToken"` — field names, not values |

**Classification:** **Theater** for open-source. Field names in DEX are expected. No action needed.

---

### C4 · Certificate / SPKI Pinning

| Item | Status | Notes |
|---|---|---|
| BYD cloud TLS pinning | ❌ Not implemented | BYD MQTT + HTTPS endpoints — currently no pinning |
| Tunnel endpoints | ❌ Not pinned (by design) | Zrok/Cloudflare rotate certs; pinning would break reconnect |
| Local HTTP (127.0.0.1:8080) | N/A | Loopback only, no TLS needed |

**Classification:** BYD cloud pinning = **real** (prevents MITM on vehicle control commands). Track1 uy93.11.

**Recommendation:** Pin SPKI of BYD cloud root CA only. Exclude tunnel domains — they rotate and are not in the threat model.

---

### C5 · Root Detection

| Item | Status | Notes |
|---|---|---|
| Root detection | ❌ None | |

**Classification:** **Not applicable.** BladeWatch runs on a custom BYD head unit. The daemon itself runs as `shell` UID (elevated) and the environment is inherently rooted/unlocked. Root detection would trip on the normal runtime environment.

**Recommendation:** Skip entirely. Root detection is counterproductive here.

---

### C6 · Debugger Detection

| Item | Status | Notes |
|---|---|---|
| Debugger detection | ❌ None | `debuggable false` in release build |
| `debuggable false` | ✅ Done | Release manifest |

**Classification:** **Theater** for open-source. The daemon's `app_process` process cannot be debugged by an attacker without shell access (which already implies compromise).

**Recommendation:** No action. `debuggable false` is sufficient.

---

### C7 · Emulator / VM Detection

**Classification:** **Not applicable.** BladeWatch only runs on BYD DiLink v3 hardware. Emulator detection serves no purpose.

---

### C8 · Anti-Tampering / Self-Integrity

| Item | Status | Notes |
|---|---|---|
| APK signature check at runtime | ❌ None | |
| Play Integrity | ❌ N/A | Not on Play Store |

**Classification:** **Partial theater**, partial real. Checking our own APK signature at startup would detect repackaging (attacker bundles BladeWatch with a payload). But on this custom head unit, side-loading is normal; the threat is a co-resident *separate* app, not a repackaged BladeWatch. Track1 uy93.10 documents this nuance — likely drop.

**Recommendation:** Skip or implement as a warning-only "was I repackaged?" check if desired. No blocking action needed.

---

### C9 · At-Rest Secret Encryption

| Item | Status | Notes |
|---|---|---|
| Secrets location | Primary: `/storage/emulated/0/Android/data/net.bladewatch.app/files/bladewatch_secrets.json` | |
| Encryption | ❌ Plaintext JSON | |
| Legacy mirror in `/data/local/tmp` | ✅ Removed by uy93.4 | |
| Access control | File is `rw-------` (shell-owned) | Only shell UID daemon can read it |

**Classification:** **Real threat.** If an attacker gains shell access, they get all secrets. The current model relies on file-level isolation (shell UID). Moving secrets to a Keystore-backed native key would require compromise of the Keystore (harder than reading a file).

**Recommendation:** Track1 uy93.12 — design native daemon keystore. This is the highest-value Track1 item.

---

### C10 · Transport Encryption

| Item | Status | Notes |
|---|---|---|
| BYD cloud HTTPS | ✅ TLS | Standard HTTPS |
| BYD cloud MQTT | ✅ TLS | Port 8883 |
| Zrok tunnel | ✅ TLS | Zrok provides end-to-end |
| Local HTTP (127.0.0.1:8080) | ✅ Loopback-only | TLS unnecessary on loopback |
| IPC (19876/19877) | ✅ Loopback-only | |

**Classification:** Transport layer is adequate. The gap is certificate pinning (C4), not encryption.

---

### C11 · Secure IPC

| Item | Status | Notes |
|---|---|---|
| Caller-UID gate on TCP IPC servers | ✅ Done (uy93.1) | `PeerCredentials.isTrusted()` |
| IPC token constant-time comparison | ✅ Done (uy93.3) | `MessageDigest.isEqual()` |
| Shell command removed | ✅ Done (uy93.2) | |
| Vehicle POST second factor (non-loopback) | ✅ Done (uy93.5) | `VehicleActionToken` |

**Classification:** **Real** — this is the primary threat. Track0 is complete.

---

### C12 · Log Sanitization

| Item | Status | Notes |
|---|---|---|
| `SecretRedactor` in release DEX | ✅ Present | Confirmed by `verify-re-hardening.sh` |
| Log stripping in R8 release | ✅ Done | `DaemonLogConfig` flags off in release |

**Classification:** **Real** (logcat can leak secrets in debug builds). Complete.

---

### C13 · WebView Hardening

| Item | Status | Notes |
|---|---|---|
| JavaScript enabled | Yes (required for app) | |
| File access disabled | ✅ | WebView configured with file access restrictions |
| JS bridge injection | ✅ Present | Auth cookies injected for mutating calls |
| Origin checking | ❓ Not confirmed | WebView only loads from `127.0.0.1:8080` |

**Classification:** WebView is loopback-only; the main risk is XSS from compromised web assets. File access restrictions are important here.

---

### C14 · RASP (Runtime Application Self-Protection)

| Item | Status | Notes |
|---|---|---|
| Frida detection | ❌ None | |
| Process monitoring | ❌ None | |

**Classification:** **Theater** for open-source. Frida hooks can be defeated even with detection if the source is available. The UID gate on IPC servers is a better RASP substitute.

**Recommendation:** Skip for now. Track1 uy93.9 notes "report-only" mode — this is acceptable if implemented, but not high-priority.

---

### C15 · Play Integrity / Attestation

**Status:** Investigated and **DROPPED** (uy93.10).

**Reason:** BYD DiLink v3 is not a certified Android device and does not have Google
Play Services (`com.google.android.gms`). The Play Integrity API requires Play
Services at runtime; without it every API call throws `IntegrityServiceConnectionException`.
This was confirmed by checking the device's installed packages — no GMS present.

**Mitigation:** APK signing-certificate self-check (implemented in uy93.10) provides
equivalent anti-repackaging protection without requiring Play Services.

---

### C16 · Secure Storage Key Management

| Item | Status | Notes |
|---|---|---|
| Android Keystore | ❌ Not used | |
| Native daemon key | ❌ Not implemented | |
| Current model | File-based, shell UID | |

**Classification:** **Real** — this is uy93.12 (at-rest secret custody). The Android Keystore is not useful here because the daemon runs as `shell` (not the app UID), so Keystore keys tied to the app UID are inaccessible to the daemon. A daemon-local key derivation from a device-unique value is the right approach.

---

## Tier Selection

### Tier Definitions

| Tier | Focus | Effort |
|---|---|---|
| Tier 0 | Log stripping, R8 enabled | Done |
| Tier 1 | IPC lockdown, caller-UID gate, constant-time auth | Done (Track0) |
| Tier 2 | TLS pinning on critical endpoints, second factor for vehicle commands, at-rest secret design | **Target for Track1** |
| Tier 3 | Anti-RE controls, RASP, integrity attestation | Theater for open-source; skip |

### Decision: **Target Tier 2**

**Included in Tier 2 (Track1 backlog):**

| Priority | Control | Task |
|---|---|---|
| HIGH | At-rest secret custody (native daemon key) | uy93.12 |
| HIGH | TLS/SPKI pinning on BYD cloud endpoints | uy93.11 |
| MEDIUM | R8 obfuscation tuning (log-adjacent, not logic) | uy93.7 |
| MEDIUM | `-fvisibility=hidden` in CMakeLists | uy93.8 |
| LOW | RASP report-only (Frida/debug detection) | uy93.9 |

**Skipped (theater for open-source):**

| Control | Reason |
|---|---|
| Identifier obfuscation tuning | Source on GitHub — provides no meaningful protection |
| Self-integrity / signature check | Co-resident threat is separate app, not repackaged BladeWatch |
| Root detection | Head unit is normally rooted/unlocked |
| Emulator detection | Hardware-only deployment |
| Play Integrity | Not on Play Store |

---

## Summary

The most impactful remaining work is **uy93.12** (move secrets from a shell-owned
plaintext file to a native daemon key derivation) and **uy93.11** (SPKI pin BYD
cloud endpoints). These address the real threats. Everything in Tier 3 (anti-RE,
RASP, integrity) is noise for an open-source project and should be deferred
indefinitely unless the threat model changes (e.g., closed-source fork).
