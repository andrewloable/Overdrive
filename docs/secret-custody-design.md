# At-Rest Secret Custody Design (uy93.12)

Design + spike for securing BladeWatch secrets at rest on the BYD DiLink v3 head unit.

**Date:** 2026-06-21  
**Branch:** `flutter-refactor`  
**Status:** Decision recorded — see [Decision](#decision) below.

---

## Current State

Secrets (device token, zrok enable token, BYD cloud creds) are stored as plaintext JSON at:
```
/storage/emulated/0/Android/data/net.bladewatch.app/files/bladewatch_secrets.json
```

| Property | Value |
|---|---|
| Owner / mode | `shell` / `rw-------` (600) |
| Who can read | shell UID (2000) only — i.e., any process running as shell |
| Accessed by | CameraDaemon directly (shell UID 2000) |
| App access | Via IPC `secret_get` (UID gate + token auth) |

The legacy mirror at `/data/local/tmp/bladewatch_secrets.json` was removed by uy93.4.

---

## Threat Model

**Attacker capability assumption:** The attacker has shell UID (2000) on the head unit —
either via ADB (physical access, car unlocked) or a vulnerable setuid binary.

At shell UID, the attacker can read any `rw-------` (600) file owned by shell. No
pure-software key derivation helps: if the attacker has shell UID, they can run
the same key derivation. The only meaningful protection beyond current mode-600
isolation is a **hardware security module** (HSM/StrongBox/TEE) that enforces
access control below the OS level.

**Sub-threat (lower severity):** A malicious app with only app UID can read secrets
indirectly via IPC — blocked by the UID gate (Track0 uy93.1) and token auth.

---

## Options Evaluated

### Option A: Native-held key (NDK, shell-UID daemon)

Derive a master key in C/C++, encrypt secrets, never write the key to disk.

**Key derivation candidates:**

| Source | Stable? | UID-exclusive? | Notes |
|---|---|---|---|
| `ro.serialno` via `__system_property_get` | Yes | No | Readable by any process |
| `/proc/sys/kernel/random/boot_id` | No (per-boot) | — | Secrets lost each reboot |
| `ro.build.fingerprint` | Yes | No | Readable by any process |
| Android Keystore / Keymaster hwbinder | Yes | **App UID only** | Not accessible to shell UID 2000 |
| StrongBox | Yes | **App UID only** | Not accessible to shell UID 2000 |

**Conclusion:** No hardware-backed key is accessible to shell UID. All available
stable sources (`ro.*` properties) are also readable by the attacker who has shell UID.
A native-derived key from these properties provides **obfuscation, not security**.

**app_process stability:** A prior native secrets library was removed from the codebase
(see `CMakeLists.txt` line 6-8: `"encryption now uses pure Java (Safe.java) — 100%
stability for app_process daemons"`). Re-introducing NDK in the `app_process` launch
context risks the same `dlopen`/`System.loadLibrary` failures that caused the original
removal. Spike result: **too fragile for incremental gain**.

---

### Option B: App-decrypts-over-IPC

Android Keystore (app UID) holds an AES key. The app decrypts the encrypted secrets file
and sends plaintext values to the daemon over localhost IPC (`secret_get` on 19876).

**Ceiling analysis:**
- The IPC now has a UID gate (Track0 uy93.1) and token auth (uy93.3)
- The plaintext is on the loopback socket during transit (in-process→socket→daemon)
- The daemon still holds plaintext in memory after the IPC read
- If an attacker has shell UID, they can connect to port 19876 with the IPC token
  (readable because it's `644`) and issue `secret_get` — same as today, but now
  gated by UID. The attacker *is* shell UID, so the gate doesn't help them.

**Additional exposure:** The Keystore key is app-UID-bound; the IPC path decrypts
and re-exposes the value on loopback. For shell-UID attackers this is worse than the
current model (one-step file read vs two-step IPC drain).

**Conclusion:** App-decrypts-over-IPC provides **no security improvement** against
the defined threat (shell UID attacker) and adds latency and complexity.

---

### Option C: Encrypted file with property-derived key (pure Java)

Derive a symmetric key from stable device properties (`ro.serialno` + `ro.build.fingerprint`
+ a random device-specific salt persisted separately), encrypt the secrets JSON.

**Security gain:** An attacker who copies the secrets *file* but does not have shell
access to query properties cannot decrypt it. This protects against file-only leaks
(e.g., USB backup, sdcard dump). It does NOT protect against an attacker who already
has shell UID (they can run the same derivation).

**Risk:** Property values can change on OTA firmware update → secrets become unreadable →
user must re-register. Mitigation: include a version field and re-derive on mismatch.

**Conclusion:** Provides **marginal protection against file-copy attacks** but **not
against the primary threat (shell UID)**. Worth considering as a future enhancement
after Track0 is fully deployed.

---

### Option D: Accept current model + audit (chosen)

**Rationale:**

1. **The primary threat is shell UID access.** No pure-software key derivation protects
   against an attacker who already has the same UID as the key derivation process.

2. **Hardware-backed keys are not accessible to shell UID** on this platform
   (Android Keystore is app-UID-bound; StrongBox requires app context).

3. **Track0 IPC lockdown (uy93.1–uy93.5) is the primary defense.** The UID gate on
   TcpCommandServer and SurveillanceIpcServer means a co-resident app cannot drain
   secrets via IPC. Direct file access requires shell UID, which is a harder
   prerequisite than exploiting a loopback API.

4. **uy93.4 (mirrorLegacy removal)** eliminated the second copy at `/data/local/tmp`.
   Secrets now exist in exactly one place (app-external files dir), owned by shell.

5. **Complexity cost** of the alternatives is not justified by the security gain
   against a shell-UID attacker. Option A risks app_process instability (documented
   prior incident). Option B re-exposes plaintext on IPC. Option C adds OTA fragility.

---

## Decision

**Chosen: Option D — Accept current model with the following constraints:**

| Constraint | Status |
|---|---|
| Secrets at `rw-------` (600), shell-owned | ✅ Current |
| No legacy copy in `/data/local/tmp` | ✅ Done (uy93.4) |
| IPC gate prevents co-resident app drain | ✅ Done (uy93.1) |
| Single-location, mode-600 | ✅ Done |
| No plaintext in logs (SecretRedactor) | ✅ Done |
| No plaintext hardcoded in APK | ✅ Verified (uy93.18) |

**Future:** If the threat model expands to include file-copy attacks (without shell
access), Option C (property-derived key) is the next step. Track it as a separate
issue when it becomes a real concern.

**"No plaintext secret persisted to shared storage" — satisfaction note:**
The secrets file IS on shared external storage (`/storage/emulated/0/Android/data/...`)
and IS plaintext. This is documented as the accepted risk given the ceiling analysis.
The full at-rest encryption goal requires hardware support not available on this
platform for shell-UID processes. The nearest achievable goal (Option C) is deferred
pending a concrete file-copy threat scenario.
