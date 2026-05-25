# Security Smoke Test Checklist

Use this checklist after a security fix build is installed on a BYD head unit or a close emulator/test device.

Assumptions:
- The app package name is `com.overdrive.app`.
- The device is on the same Wi-Fi network as your workstation for LAN checks.
- `adb` is connected to the head unit.
- Replace `<CAR_IP>` and `<TUNNEL_URL>` with the values from your environment.
- Replace `<FULL_DEVICE_TOKEN>` with the full token shown in the app's Dashboard token card and login screen.

## 1. Confirm the app is reachable through a secure tunnel

Command:
```bash
open <TUNNEL_URL>
```

Expected:
The site loads over `https://`, shows the login screen when signed out, and shows the app after sign-in. Camera views and vehicle controls should work only after authentication.

Failure interpretation:
If the tunnel URL is plain `http://`, if the page fails to load, or if the app is visible without auth, the remote-access model is broken.

## 2. Verify unauthenticated API access is blocked on loopback and LAN

Command:
```bash
adb shell "curl -i http://127.0.0.1:8080/api/vehicle/state"
curl -i http://<CAR_IP>:8080/api/vehicle/state
```

Expected:
The loopback request must return `401` or `403` when unauthenticated. The LAN request must either fail because LAN HTTP is disabled by default or return `401`/`403` if LAN HTTP was explicitly enabled for this test.

Failure interpretation:
Any `200 OK`, any vehicle data in the body, or any response that exposes diagnostics without auth means the auth boundary is broken.

## 3. Verify login, JWT issuance, expiry, and logout

Command:
```bash
curl -i -c /tmp/overdrive.cookies -H 'Content-Type: application/json' -d '{"token":"<FULL_DEVICE_TOKEN>"}' http://127.0.0.1:8080/auth/token
curl -i -b /tmp/overdrive.cookies http://127.0.0.1:8080/auth/status
curl -i -X POST -b /tmp/overdrive.cookies -c /tmp/overdrive.cookies http://127.0.0.1:8080/auth/logout
curl -i -b /tmp/overdrive.cookies http://127.0.0.1:8080/auth/status
```

Expected:
The token exchange returns `200 OK`, `success:true`, `expiresIn:86400`, and a `Set-Cookie: byd_session=...; HttpOnly` header. `GET /auth/status` should succeed while the cookie is valid. After logout, the same cookie must no longer authenticate and `GET /auth/status` should return `401` or `403`.

Failure interpretation:
If login succeeds without a cookie, if the cookie is not `HttpOnly`, if logout does not invalidate the session, or if the session remains valid after logout, the JWT/session model is broken.

## 4. Verify the CORS policy is not wildcard-open

Command:
```bash
curl -i -X OPTIONS -H 'Origin: https://example.com' -H 'Access-Control-Request-Method: GET' http://127.0.0.1:8080/api/vehicle/state
```

Expected:
The response must not contain `Access-Control-Allow-Origin: *`. Privileged APIs must not be exposed to arbitrary browser origins.

Failure interpretation:
Any wildcard CORS header, or a permissive origin echo on privileged endpoints, means browser-based cross-origin access is too broad.

## 5. Verify secrets are not stored in the public config or logs

Command:
```bash
adb shell ls -l /data/local/tmp/overdrive_secrets.json
adb shell cat /data/local/tmp/overdrive_config.json
adb shell "cat /data/local/tmp/overdrive_config.json | grep -E 'deviceSecret|botToken|loginKey|signPassword|commandPwd|rawPassword|user_token|api_key|enableToken|reservedToken|mqttPassword|password' || true"
adb logcat -d | grep -E 'New token|Using reserved token|byd_jwt|byd_session'
```

Expected:
The secret store file should be owner-only (`rw-------` or equivalent). The public config must not contain secret values or secret key names. The log search must return no raw tokens, session cookies, or secret material.

Failure interpretation:
If secrets appear in `overdrive_config.json`, if the secret store is world-readable, or if logs contain token material, the redaction and secret-splitting work failed.

## 6. Verify the location bootstrap component cannot be launched externally

Command:
```bash
adb shell am start -n com.overdrive.app/com.overdrive.app.ui.LocationStarterActivity
```

Expected:
The shell should receive a permission or activity-start failure such as `Permission Denial` or `Activity not started`.

Failure interpretation:
If the activity starts successfully from an external shell, the exported-component hardening is incomplete.

## 7. Verify Android backup is disabled or excludes all secret state

Command:
```bash
adb shell dumpsys package com.overdrive.app | grep -E 'allowBackup|fullBackupContent|dataExtractionRules'
```

Expected:
`allowBackup` must be `false`, or the backup rules must clearly exclude all sensitive config, secret-store, auth, and telemetry state.

Failure interpretation:
If backups are enabled without full exclusions, Android backup can leak sensitive device data off the head unit.

## 8. Verify MQTT secrets are hidden and insecure TLS is not the default

Command:
```bash
adb shell "cat /data/local/tmp/overdrive_config.json | grep -E 'mqtt|trustAllCerts|tcp://' || true"
```

Expected:
The public config should not print an MQTT password. `trustAllCerts` should be `false` unless you intentionally enabled it for a trusted test broker. `tcp://` should only appear if you explicitly chose plain MQTT for a trusted local network.

Failure interpretation:
If a password is visible in the public config, or if insecure TLS is enabled by default, the MQTT hardening has regressed.

## 9. Verify OTA update packages with a bad checksum are rejected

Command:
```bash
adb shell am start -n com.overdrive.app/com.overdrive.app.ui.MainActivity
```
Then open the app's update flow and attempt to install a release whose `overdrive-update.json` checksum does not match the APK.

Expected:
The updater must stop with an error similar to `APK checksum mismatch` and refuse to install the package.

Failure interpretation:
If the install continues after a checksum mismatch, the OTA integrity check is not working.

## 10. Verify OTA downgrades are rejected by default

Command:
```bash
adb shell am start -n com.overdrive.app/com.overdrive.app.ui.MainActivity
```
Then open the update flow and attempt to install a release whose `versionCode` is lower than or equal to the installed build.

Expected:
The updater must skip the release or report that the installed version is newer. It must not downgrade the app.

Failure interpretation:
If the updater accepts a lower or equal `versionCode`, downgrade protection is broken.

## 11. Verify the smoke-test evidence is captured

Command:
```bash
adb shell ls -l /data/local/tmp/overdrive_update_timestamp
adb shell ls -l /data/local/tmp/overdrive_update_progress.json
```

Expected:
After a successful security run, the update sentinels should reflect the current build state and the logs should show the expected validation failures for negative OTA tests.

Failure interpretation:
If the update state files are missing when the updater says an install happened, the regression run is incomplete and should be repeated.

## Source References

- Manifest security posture and exported components: [AndroidManifest.xml:207](../app/src/main/AndroidManifest.xml#L207), [AndroidManifest.xml:209](../app/src/main/AndroidManifest.xml#L209), [AndroidManifest.xml:244](../app/src/main/AndroidManifest.xml#L244), [AndroidManifest.xml:306](../app/src/main/AndroidManifest.xml#L306), [AndroidManifest.xml:312](../app/src/main/AndroidManifest.xml#L312).
- Auth token issuance, JWT validation, and thumb tokens: [AuthManager.java:50](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L50), [AuthManager.java:349](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L349), [AuthManager.java:392](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L392), [AuthManager.java:463](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L463).
- Login and auth API rate limiting: [AuthApiHandler.java:26](../app/src/main/java/com/overdrive/app/server/AuthApiHandler.java#L26), [AuthApiHandler.java:30](../app/src/main/java/com/overdrive/app/server/AuthApiHandler.java#L30), [AuthApiHandler.java:155](../app/src/main/java/com/overdrive/app/server/AuthApiHandler.java#L155).
- HTTP auth enforcement and protected routes: [AuthMiddleware.java:133](../app/src/main/java/com/overdrive/app/server/AuthMiddleware.java#L133), [HttpServer.java:49](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L49), [HttpServer.java:650](../app/src/main/java/com/overdrive/app/server/HttpServer.java#L650).
- LAN binding and loopback defaults: [CameraDaemon.java:53](../app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java#L53), [UnifiedConfigManager.kt:559](../app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L559), [UnifiedConfigManager.kt:567](../app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt#L567).
- Secret storage and redaction: [SecretConfigStore.kt:22](../app/src/main/java/com/overdrive/app/config/SecretConfigStore.kt#L22), [AuthManager.java:530](../app/src/main/java/com/overdrive/app/auth/AuthManager.java#L530), [SecretRedactor.java:14](../app/src/main/java/com/overdrive/app/logging/SecretRedactor.java#L14).
- MQTT, update checksum, and OTA behavior: [MqttApiHandler.java:25](../app/src/main/java/com/overdrive/app/server/MqttApiHandler.java#L25), [build.gradle.kts:8](../app/build.gradle.kts#L8), [UpdateApiHandler.java:43](../app/src/main/java/com/overdrive/app/server/UpdateApiHandler.java#L43).
