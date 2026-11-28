# IntruderSnap — Milestone 2: Reliable Monitoring Core

**Branch:** `milestone-2-monitoring-core` (built on `milestone-1-feasibility-spike`). **Not merged into `main`.**
**Status:** implemented and CI-verified. **Physical-device verification: NOT TESTED.**

---

## 1. Build results (CI, GitHub Actions run 46037178283, commit 11a4bb8)

| Check | Result |
|---|---|
| Compile (AGP 8.5.2, Kotlin 2.0.21, compileSdk/targetSdk 34, minSdk 30) | PASS |
| JVM unit tests: 38 (`IncidentTrackerTest` 14, `MonitoringHealthTest` 13, `ThresholdPolicyTest` 6, `RetentionAndSessionTest` 5) | PASS |
| Instrumented tests on an Android 14 (API 34) **emulator**: 11 (`CryptoBoxTest` 5, `EventRepositoryTest` 6) | PASS |
| APK permission check: no `android.permission.INTERNET` | PASS |
| Debug APK | `debug-build-9` on the Releases page |

The instrumented tests caught a real defect in the first CI run. `SQLiteDatabase.execSQL("PRAGMA secure_delete = ON")` throws on Android, which would have made every record write fail silently. It was fixed in commit 11a4bb8, and storage failures are now shown on the Health screen.

**Emulator results verify code paths only. They are not device results.**

## 2. What changed (by requirement)

### 2.1 Reliable native monitoring
- The proven `DeviceAdminReceiver` and its watch-login-only policy are unchanged. Callbacks still use the API 26+ 3-argument signatures, run in `goAsync()` on `Dispatchers.IO`, and are directBootAware.
- New `IncidentTracker` (pure, unit-tested) measures each incident against Android's counter.
- **Duplicate or delayed callback:** the same system count as the previous failure in the open incident. It is recorded with classification `DUPLICATE_SUSPECT` and is not counted again.
- **Missed callbacks:** the system count jumps by more than one. The difference is reconciled into the incident and can trigger the threshold.
- **Counter reset without a success callback:** a new incident starts.
- **Idle gap of 15 minutes or more starts a new incident.** This fixes a Milestone 1 gap: after a fingerprint/face unlock Android sends no success callback and does not reset its counter, so under the old logic the alert could never fire again.
- **Reconciliation** (`MonitoringChecks.reconcile`) compares the stored state with `getCurrentFailedPasswordAttempts()` on app open, at boot, in the periodic job and on demand. Missed failures are recorded as `RECONCILIATION` events and can raise an intrusion alert.
- Every record carries its **timestamp, source** (for example `DeviceAdminReceiver.onPasswordFailed`, `Reconciler(periodic job)`, `BootReceiver`, `Owner`), classification and incident count.
- When monitoring cannot be verified, the Health screen says so (§2.3).

### 2.2 Xiaomi / HyperOS guided setup (Setup tab)
Eight steps, each with an honest status (Done · Action needed · Confirmed by you · Check manually · Not tested · Verified):
1. Screen lock
2. Device administrator
3. Notifications
4. Battery restrictions — shows Android's readable state plus shortcuts
5. Autostart
6. Background app restrictions (Recents lock)
7. Self-test: one wrong PIN
8. Long-idle closed-app delivery test

HyperOS Autostart and HyperOS's own battery saver **cannot be read by apps**, so the owner confirms them manually and the app labels them "Confirmed by you". The screen states that the settings do not guarantee delivery.

### 2.3 Monitoring health (Health tab)
It shows:
- device admin active or inactive, and whether watch-login is granted;
- whether a secure screen lock is set;
- last callback received, and the number of callbacks;
- last successful reconciliation, with its result;
- Android's current failed-attempt counter;
- the open incident;
- standby bucket, background-restricted state and battery-optimization state;
- notification permission;
- background delivery test status;
- last reboot and boot count;
- last admin-status check.

**Confidence states** (`MonitoringHealth`, unit-tested):

| State | Condition |
|---|---|
| Permission required | No secure lock, admin inactive, or watch-login not granted |
| Restricted | Android reports background-restricted / RESTRICTED bucket, missed failures detected since the last verification, or storage failure |
| Not tested | Admin active but no callback ever received |
| Active but delivery unverified | Callbacks seen only while the app was running, or the closed-app evidence is older than 30 days |
| Verified | A callback reached IntruderSnap while it was closed (newer builds record this directly; Milestone 1 Direct-Boot callbacks also count) within 30 days, with no later restriction |

### 2.4 Protected security history
- Owner authentication uses the platform `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`, so either a fingerprint or the phone's own PIN/pattern/password works. IntruderSnap never sees the credential.
- **Protected:** History, Settings, Labs, acknowledging the intrusion banner, report export (Labs), and deactivation from inside the app.
- The session lasts at most 5 minutes and **ends whenever IntruderSnap leaves the screen**. It is never persisted.
- **Clear history** needs an authentication within the last 60 seconds (`OwnerSession.freshProof()`). `EventRepository.clearAll` requires an `OwnerProof` parameter and always writes a `HISTORY_CLEARED` record.
- Every settings change is written to the history as `SETTINGS_CHANGED`. This includes threshold, retention, experimental tools and deactivation, so an intruder cannot silently raise the threshold or shorten retention.

### 2.5 Device administrator status
- `onDisableRequested` and `onDisabled` are recorded, and `onDisabled` immediately posts a "monitoring stopped" notification when Android allows it.
- `MonitoringChecks.checkAdminStatus` compares the remembered state with the current state at boot, in the periodic job (every ~6 h via `JobScheduler`) and on app open. If the admin became inactive it records `ADMIN_LOST` and notifies the owner.
- No immediate notification is promised: Android/HyperOS decide when IntruderSnap can run.

### 2.6 Hardened local storage
- **Key:** AES-256-GCM key in the **Android Keystore** (non-exportable; tested). It is not bound to user authentication, so the receiver can write before the first unlock.
- **Records:** each record's content is ciphertext bound to its timestamp (AAD), so modified or moved records are detected and shown as "could not be decrypted" (tested).
- **Database:** framework SQLite (no extra library) in device-protected storage, with bound parameters only, `secure_delete=ON`, and WAL disabled (tested). Only the timestamp is unencrypted, for retention.
- **Retention:** 30 / 90 (default) / 180 / 365 days. It is applied on app open and in the periodic job, and each purge is recorded.
- **Migration:** Milestone 1's plaintext JSON-lines history is moved into the encrypted store, then overwritten and deleted.
- **Keystore fallback:** if the Keystore is unavailable at write time, the record is stored flagged as unencrypted and re-encrypted at the next opportunity, with a `STORAGE_NOTICE`. It is never silent.
- **No credentials** are stored. **No network permission:** CI fails the build if one appears. Nothing is uploaded.

### 2.7 Camera status
- The background camera remains **experimental**. The Milestone 1 tools moved to a **Labs** tab, which is hidden until an authenticated owner enables "experimental tools" and is itself protected.
- Test E can only arm when experimental tools are on, and it disarms after one use.
- There is no production camera feature. Nothing hides indicators or bypasses platform privacy controls.
- Production enablement stays blocked until the five conditions in the approval are met: Privacy Dashboard, lock-screen notification, indicator behaviour, privacy/legal review, and re-testing after HyperOS updates.

### 2.8 Remaining Milestone 1 test
The long-idle HyperOS delivery test is Setup step 8 and TEST_PLAN M2-6. Build 9 records whether each callback arrived while the app was closed.

## 3. Exact file paths

New:
```
app/src/main/java/com/intrudersnap/prototype/auth/OwnerAuth.kt
app/src/main/java/com/intrudersnap/prototype/auth/OwnerSession.kt
app/src/main/java/com/intrudersnap/prototype/data/db/SecurityStore.kt
app/src/main/java/com/intrudersnap/prototype/data/security/CryptoBox.kt
app/src/main/java/com/intrudersnap/prototype/domain/IncidentTracker.kt
app/src/main/java/com/intrudersnap/prototype/domain/MonitoringHealth.kt
app/src/main/java/com/intrudersnap/prototype/domain/RetentionPolicy.kt
app/src/main/java/com/intrudersnap/prototype/health/HealthCheckJobService.kt
app/src/main/java/com/intrudersnap/prototype/health/MonitoringChecks.kt
app/src/main/java/com/intrudersnap/prototype/ui/components/OwnerGate.kt
app/src/main/java/com/intrudersnap/prototype/ui/health/HealthScreen.kt
app/src/main/java/com/intrudersnap/prototype/ui/health/HealthViewModel.kt
app/src/main/java/com/intrudersnap/prototype/ui/setup/SetupScreen.kt
app/src/main/java/com/intrudersnap/prototype/ui/setup/SetupViewModel.kt
app/src/main/java/com/intrudersnap/prototype/ui/labs/LabsScreen.kt
app/src/test/java/com/intrudersnap/prototype/domain/IncidentTrackerTest.kt
app/src/test/java/com/intrudersnap/prototype/domain/MonitoringHealthTest.kt
app/src/test/java/com/intrudersnap/prototype/domain/RetentionAndSessionTest.kt
app/src/androidTest/java/com/intrudersnap/prototype/data/CryptoBoxTest.kt
app/src/androidTest/java/com/intrudersnap/prototype/data/EventRepositoryTest.kt
docs/MILESTONE_2.md
```
Changed:
```
.github/workflows/android-debug-apk.yml        (permission check, emulator test job)
app/build.gradle.kts                           (minSdk 30, version 0.2.0-m2, test deps)
app/src/main/AndroidManifest.xml               (USE_BIOMETRIC, HealthCheckJobService)
app/src/main/java/com/intrudersnap/prototype/IntruderSnapApp.kt
app/src/main/java/com/intrudersnap/prototype/admin/IntruderSnapAdminReceiver.kt
app/src/main/java/com/intrudersnap/prototype/admin/LoginEventProcessor.kt
app/src/main/java/com/intrudersnap/prototype/boot/BootReceiver.kt
app/src/main/java/com/intrudersnap/prototype/data/EventRepository.kt
app/src/main/java/com/intrudersnap/prototype/data/RuntimeEnvironment.kt
app/src/main/java/com/intrudersnap/prototype/data/SecurityEvent.kt
app/src/main/java/com/intrudersnap/prototype/data/SettingsRepository.kt
app/src/main/java/com/intrudersnap/prototype/notify/Notifier.kt
app/src/main/java/com/intrudersnap/prototype/ui/MainActivity.kt
app/src/main/java/com/intrudersnap/prototype/ui/history/HistoryScreen.kt
app/src/main/java/com/intrudersnap/prototype/ui/history/HistoryViewModel.kt
app/src/main/java/com/intrudersnap/prototype/ui/settings/SettingsScreen.kt
app/src/main/java/com/intrudersnap/prototype/ui/settings/SettingsViewModel.kt
docs/TEST_PLAN.md, docs/COMPATIBILITY_RESULTS.md, README.md
```
Removed: `app/src/main/java/com/intrudersnap/prototype/ui/dashboard/` (replaced by Health).

## 4. Known limitations

1. **Delivery is not guaranteed.** Android and HyperOS decide whether and when IntruderSnap runs. Battery restrictions, Autostart, memory cleaners or "Clear all" can delay or stop delivery. Reconciliation detects missed failures afterwards; it cannot recover their exact times.
2. **Biometric unlocks** produce no success callback and do not reset Android's counter. Incidents are then closed by the 15-minute gap rule or by reconciliation, not instantly.
3. **Device-admin deactivation through Android Settings cannot be blocked.** It is recorded and notified when IntruderSnap next runs.
4. **No protection against** factory reset, root, ADB with privileged access, or other privileged system access. Encrypted records stay on the device and are lost with a factory reset.
5. **Keystore in Direct Boot:** expected to work, because the key is not auth-bound, but not yet verified on the Redmi. If it fails, records are stored flagged and re-encrypted later.
6. **Viewing protection is app-level.** The encryption key is not auth-bound, because records must be written while the phone is locked. Encryption protects records at rest against file-level access, not against code running as IntruderSnap.
7. **Not readable by apps:** HyperOS Autostart and HyperOS's own battery-saver setting, so these are owner-confirmed only.
8. **Duplicate detection** relies on Android's counter. When the counter is unavailable, every callback is counted.
9. **minSdk is 30 (Android 11).** Older Android versions are no longer supported.
10. **Background camera** remains experimental and is not a feature (see §2.7).
11. **Deactivation through HyperOS Settings (observed on the Redmi 12C):** `onDisableRequested`/`onDisabled` were not received. The loss was detected only when IntruderSnap next ran (app open), not immediately. The periodic check (~6 h) is the only background fallback.
12. **Android's activation wording:** the system's standard description of the watch-login policy reads "…and lock the phone or erase all the phone's data if too many incorrect passwords are typed." That text is fixed by Android for this policy. IntruderSnap does **not** hold the force-lock or wipe-data policies and cannot do either; the Setup screen and IntruderSnap's own explanation say so.
