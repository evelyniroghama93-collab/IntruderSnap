# IntruderSnap — Milestone 1 Technical Feasibility Report

**Scope:** whether a third-party app can detect incorrect credentials entered on the native Android 14 lock screen of a Xiaomi / Redmi phone, and whether evidence capture with the camera is technically possible.

**Status of this report:** this is the *analysis and method*. Results are recorded in `COMPATIBILITY_RESULTS.md` after the physical-device test plan has been run. **No result is claimed before then.**

---

## 1. Native failed-password monitoring

### 1.1 Mechanism

Android's Device Administration API lets an app registered as a device administrator with the **`watch-login`** policy (`DeviceAdminInfo.USES_POLICY_WATCH_LOGIN`):

- receive `DeviceAdminReceiver.ACTION_PASSWORD_FAILED`, delivered to `onPasswordFailed`, when the system's lock-screen credential check fails;
- receive `ACTION_PASSWORD_SUCCEEDED`, delivered to `onPasswordSucceeded`, when a correct credential follows one or more failures;
- call `DevicePolicyManager.getCurrentFailedPasswordAttempts()`. This throws `SecurityException` unless the caller is an active admin holding watch-login.

The broadcasts come from the system's own credential-verification path, not from the lock-screen UI. In principle this makes them independent of OEM lock-screen skins. **Whether MIUI/HyperOS keeps this path intact is exactly what Test 6 must establish.**

### 1.2 Callback signatures

| Signature | Status on API 34 | Used |
|---|---|---|
| `onPasswordFailed(Context, Intent, UserHandle)` | Current (added API 26) | ✅ overridden |
| `onPasswordSucceeded(Context, Intent, UserHandle)` | Current (added API 26) | ✅ overridden |
| `onPasswordFailed(Context, Intent)` | Deprecated in API 26 | ❌ not overridden |
| `onPasswordSucceeded(Context, Intent)` | Deprecated in API 26 | ❌ not overridden |

The framework's default 3-argument implementations call the deprecated 2-argument ones. Overriding only the 3-argument versions therefore receives each event exactly once.

### 1.3 Event handling and incident logic

1. `onPasswordFailed` → `goAsync()` → background coroutine. The coroutine reads the system counter, increments the prototype counter, writes a `PASSWORD_FAILED` record (fsync) and evaluates the threshold.
2. **Effective count** = the system count when it can be read, otherwise the prototype count.
3. When the effective count first reaches the threshold (default 3), the app records **one** `INTRUSION_THRESHOLD_REACHED` event, posts a notification and sets the in-app alert. Further failures in the same incident do not create more intrusion events.
4. `onPasswordSucceeded` writes `PASSWORD_SUCCEEDED`. Its *previous failed count* is the last system count stored on failure, because the system counter is already reset when this callback arrives. The incident is then reset (prototype count 0, trigger flag cleared).
5. The app never calls any API that affects Android's lockout, password or wipe behaviour. It holds no policy that would allow it to.

### 1.4 Known limitations

- **Biometric failures** (fingerprint, face) are not credential failures. They are not reported to admins and are not counted.
- **`onPasswordSucceeded` is not an "every unlock" signal.** It is only sent after failures. If the owner finally unlocks with a fingerprint after failed PIN attempts, it may not be sent, and the prototype incident stays open until the next credential success.
- **Multi-user / second space:** callbacks carry a `UserHandle`. The history notes whether a callback was for the app's own user. Xiaomi "Second space" was not studied.

## 2. Persistence, process death and reboot

- **Storage:** history (JSON lines) and settings (SharedPreferences) live in **device-protected (DE) storage** via `createDeviceProtectedStorageContext()`. Both receivers and the `Application` are Direct-Boot-safe; they never touch credential-encrypted storage.
- **Why DE:** after a reboot, CE storage stays locked until the first correct unlock. A failed attempt on the first lock screen would otherwise be lost. Records contain no credentials, so the lower protection level of DE is acceptable for them.
- **Process death:** each record is written with `fsync` inside `goAsync()` before the broadcast is finished. A new process reloads from disk.
- **Verification is automatic:** each event stores the app process ID, `Settings.Global.BOOT_COUNT` and whether the user was unlocked. The Results screen marks *Event persistence* PASS only when a callback written by an earlier process is reloaded. It marks *Reboot behaviour* PASS only when failed callbacks exist across two boots.
- **Uncertainty:** whether the system delivers watch-login broadcasts to a `directBootAware` receiver before the first unlock on HyperOS/MIUI. Test 14 measures this ("Recorded before first unlock = Yes").

## 3. Camera feasibility (Android 14)

| Item | Analysis | Expected | Verified by |
|---|---|---|---|
| A. Permission | `CAMERA` is a runtime permission granted by the owner. | Grantable | Test A |
| B. CameraX while visible | Normal foreground camera use. | Works | Test B |
| C. Camera FGS while visible | Service declared `foregroundServiceType="camera"`, started from a visible activity. | Works, with notification and privacy dot | Test C |
| D. Android 14 FGS requirements | targetSdk 34 requires: a type in the manifest, the `FOREGROUND_SERVICE_CAMERA` permission, `CAMERA` granted before `startForeground`, and the type passed to `startForeground`. | Requirements met | Test D (static checks + C result) |
| E. FGS from background after callback | Android 12+ blocks starting foreground services from the background (`ForegroundServiceStartNotAllowedException`) unless an exemption applies. Camera is also a **while-in-use** permission: Android 14 refuses `startForeground(type=camera)` for an app that is not in an eligible state (`SecurityException`), or denies the camera open. | **Likely BLOCKED** | Test E (one-shot, opt-in) |
| F. Privacy indicator | Android 12+ shows a green dot whenever the camera is open, and the app cannot suppress it. | Visible during B/C | Manual observation |
| G. Denial behaviour | The app catches the exception or camera error, records the exact class/message, and keeps monitoring. | No crash; recorded | Automatic text |

**Safeguards (all implemented):**
- no hidden capture;
- no workaround after a refusal;
- no capture after a successful unlock;
- frames are measured and discarded, never written to storage;
- test E is opt-in and one-shot.

**Known side effect:** if Android accepts `startForegroundService()` but then refuses `startForeground()`, the system may kill the process because it "did not call startForeground". The app persists the BLOCKED result before this can happen.

**Implication for later milestones:** if E is BLOCKED, the product cannot honestly promise automatic intruder photos from the native lock screen on Android 14. Alternatives for Milestone 2+ must be visible to the user and respect platform rules; they need a separate design review.

## 4. Xiaomi / Redmi (MIUI & HyperOS) considerations

| Area | Risk | Prototype handling |
|---|---|---|
| Battery saver / background restrictions | May keep the app process from starting for broadcasts, or kill it. | Setup guide; test 15 configurations; no guarantee claimed |
| Autostart | Off by default for sideloaded apps; can block boot broadcasts and possibly others. | `BootReceiver` marker shows whether boot broadcasts arrive; deep link to the Autostart screen (falls back to App info) |
| Device admin activation | Standard system screen; on some versions the confirm button has a countdown. | Uses `ACTION_ADD_DEVICE_ADMIN` only |
| Force stop | Android disables Force stop for active admins; MIUI's Recents "clear all" or memory cleaner may still kill processes. | Config D in test 15 |
| Notifications | Lock-screen notifications may be hidden by default; the channel may need enabling. | Setup shortcut; the intrusion event stores whether the notification was posted |
| USB install | Requires "Install via USB" (Mi account). | README |

## 5. Technical uncertainties (to be resolved by testing)

1. Whether MIUI/HyperOS delivers `ACTION_PASSWORD_FAILED` to third-party admins from its custom keyguard. **This decides the milestone.**
2. Delivery when the app process is not running and battery restrictions are at their defaults.
3. Delivery during Direct Boot.
4. Whether `getCurrentFailedPasswordAttempts()` matches the visible count on HyperOS.
5. Behaviour of test E on this specific ROM. This is expected BLOCKED but must be observed.

## 6. Build verification

The code was written against AGP 8.5.2 / Kotlin 2.0.21 / compileSdk 34. It has **not** been compiled in the authoring environment, which had no access to Google's Maven repository or the Android SDK. The included GitHub Actions workflow compiles it, runs the unit tests and produces `app-debug.apk`. Treat the first CI run as the build verification step and fix any reported compile errors before device testing.
