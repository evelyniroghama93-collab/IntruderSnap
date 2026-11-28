# IntruderSnap Milestone 1 — Compatibility Results

> Only observed results are recorded here. Anything not observed is **NOT TESTED**.
> Allowed values: **PASS · FAIL · BLOCKED · NOT TESTED**.
> Every result names its evidence file under `docs/evidence/`.

## Device under test

| Field | Value | Source |
|---|---|---|
| Environment | **Physical device** (no emulator testing performed) | Owner |
| Manufacturer / brand | Xiaomi / Redmi | Report 2026-11-27 |
| Exact model | **Redmi 12C, model 22120RN86G, device codename "earth"** | Report 2026-11-27 |
| Android version / API | 14 / 34 (build UP1A.231005.007) | Report 2026-11-27 |
| HyperOS version | HyperOS OS1.0 (OS1.0.16.0.UCVMIXM), incremental V816.0.16.0.UCVMIXM | Report 2026-11-27 |
| Security patch | 2026-02-01 | Report 2026-11-27 |
| Device administrator | Active; watch-login granted | Report 2026-11-27 |
| Threshold at time of report | 3 | Report 2026-11-27 |
| App build used for the report | `debug-build-3`. The events lack the build-4 diagnostics fields. | Report format |
| Lock type / battery / Autostart settings | Not recorded | — |

Evidence files:
- `docs/evidence/2026-11-21/` — dashboard screenshots, first run.
- `docs/evidence/2026-11-27/in-app-test-report-163816.txt` — the in-app report. The pasted copy is cut off after event #67, so older events (including the camera-test events) are not included.
- `docs/evidence/2026-11-27/dashboard-intrusion-163718.png` — intrusion banner.

## Acceptance results

| # | Required result | Result | Device observation |
|---|---|---|---|
| 1 | `onPasswordFailed` callback | **PASS** | 38 callbacks recorded from the native lock screen. Example: #70–#72 at 16:37:14, 16:37:15 and 16:37:18 on 2026-11-27. |
| 2 | `onPasswordSucceeded` callback | **PASS** | 8 callbacks recorded. Example: #67 on 2026-11-24 15:13:56 with 8 previous failures; the system counter read 0 afterwards. |
| 3 | `DevicePolicyManager.getCurrentFailedPasswordAttempts()` | **PASS** | Read in 38/38 failed events (maximum 8). It incremented 1 → 2 → 3 → 4 in #70, #71, #72, #74, and reset to 0 after a success (#67). |
| 4 | Three-attempt threshold | **PASS** | #73 INTRUSION at 16:37:18, system count 3, threshold 3, notification posted (matches the screenshot). The 4th failure (#74, count 4) did **not** create a second intrusion, so "once per incident" is confirmed. 6 intrusion events in total. |
| 5 | Event persistence | **PASS** | 42 callback events written by earlier app processes were reloaded from device-protected storage. |
| 6 | Reboot behaviour | **PASS** | Callbacks were recorded across 4 boots. **3 failed callbacks were recorded before the first unlock after a reboot (Direct Boot).** Boot broadcasts were delivered: #68 `LOCKED_BOOT_COMPLETED` at 02:53:25 (user locked), #69 `BOOT_COMPLETED` at 07:01:01 (after first unlock). |
| 7 | Xiaomi/HyperOS background behaviour | **PASS (limited scope)** | The 3 Direct-Boot callbacks were delivered to an IntruderSnap process the user **could not have opened**: the app's screen cannot run before the first unlock. So HyperOS delivered lock-screen callbacks to an app that was not open, at least after a reboot. **Still NOT TESTED:** delivery after HyperOS kills a long-idle app, and the battery/Autostart configuration matrix. The settings in use were not recorded. |
| 8 | Background camera feasibility | **PASS** | Controlled repeat on 2026-11-27: test E was armed at 17:22:57 and the app was last on screen at ~17:23:01 (test F record). The tester closed the app, locked the phone, **waited at least 1 minute**, and entered one wrong PIN. At **17:26:58** the camera foreground service started from the background and delivered one 1920x2560 frame (discarded). Roughly 4 minutes separate the app being visible and the capture, which rules out Android's short grace period. Why Android allowed it has not been established (possibly an exemption linked to active device-admin status). Evidence: `camera-test-E-pass-172658.png`. |

**Main acceptance criterion — real failed-password callbacks from the Redmi's native lock screen: PASS.**

### Camera feasibility (A–G)

| Test | Result | Observation |
|---|---|---|
| A. Camera permission | **PASS** | Granted by the owner. |
| B. CameraX while visible | **PASS** | 1920x2560 frame while visible; discarded. |
| C. Camera foreground service (visible) | **PASS** | 1920x2560 frame through the camera FGS while visible; discarded. |
| D. Android 14 FGS requirements | **PASS** | `startForeground(type=FOREGROUND_SERVICE_TYPE_CAMERA)` accepted on API 34 with `FOREGROUND_SERVICE_CAMERA` declared and CAMERA granted. |
| E. Background start after callback | **PASS** | 17:26:58, app closed, phone locked ≥ 1 min. See row 8. |
| F. Privacy indicator | **PASS for B/C · FAIL for E (as observed)** | Green indicator seen during B/C. During the background capture in E, the tester **did not see the camera indicator** on the lock screen. Not yet established: whether HyperOS did not show it on the lock screen, or it appeared too briefly to notice. Check with Android's Privacy dashboard (does it list IntruderSnap camera use at 17:26:58?) or film the lock screen with a second phone. |
| G. Denial behaviour | **BLOCKED (permission-denial case)** | When camera permission was denied, the app could not open the camera, reported BLOCKED and kept recording lock-screen events. A background-denial case was not observed, because Android allowed test E. |

### Logs

No logcat was captured. The evidence is the in-app report and screenshots. Representative records from the report:

```
#74 PASSWORD_FAILED            2026-11-27 16:37:20 sys=4 proto=4 thr=3 callback=true unlocked=true boot=2853
#73 INTRUSION_THRESHOLD_REACHED 2026-11-27 16:37:18 sys=3 thr=3 note=Effective count 3 >= threshold 3 (source: system counter). Notification posted.
#72 PASSWORD_FAILED            2026-11-27 16:37:18 sys=3 proto=3 thr=3 callback=true
#71 PASSWORD_FAILED            2026-11-27 16:37:15 sys=2 proto=2 thr=3 callback=true
#70 PASSWORD_FAILED            2026-11-27 16:37:14 sys=1 proto=1 thr=3 callback=true
#69 BOOT_COMPLETED             2026-11-27 07:01:01 unlocked=true  boot=2853
#68 BOOT_COMPLETED             2026-11-27 02:53:25 unlocked=false boot=2853   (LOCKED_BOOT_COMPLETED, Direct Boot)
#67 PASSWORD_SUCCEEDED         2026-11-24 15:13:56 sys=0 proto=8 prev=8 thr=1
```

### Observations worth noting

- The phone restarted at ~02:53 on 2026-11-27 with no user involvement (#68 was recorded while locked). This could be a HyperOS scheduled restart; it should be confirmed.
- The system counter reached 8 during earlier testing. Android applies lockout delays from the 5th failure, so stay at 3–4 in future runs.
- Some earlier tests ran with threshold = 1 (#67 shows `thr=1`). The 2026-11-27 run used 3.

## Remaining checks

1. **Camera indicator during E:**
   - Open Settings › Privacy (or Privacy protection) › **Privacy dashboard / Permission usage** › Camera, and see whether IntruderSnap's access at 17:26:58 is listed.
   - Repeat E once while filming the lock screen with another phone.
   - Also record whether the "IntruderSnap camera experiment" notification appeared.
2. **Background delivery after an idle kill:** follow TEST_PLAN Test 15, ideally on build 5, which records the process diagnostics. Record the Autostart and Battery saver settings in use.
3. Capture logcat for one run if a computer is available.

## Recommendation

Native lock-screen monitoring is **feasible and verified** on the Redmi 12C / HyperOS OS1.0 / Android 14, using only the supported Device Administration API:
- failure and success callbacks;
- the system attempt counter;
- the threshold, once per incident;
- persistence across process restarts;
- delivery after reboot, including before the first unlock.

**Next milestone: proceed to Milestone 2 — the reliable monitoring core.** Its scope:
- onboarding with a live self-test;
- owner authentication (`BiometricPrompt`) before viewing or clearing history or deactivating;
- tamper alerts on `onDisableRequested` / `onDisabled`;
- a versioned event store;
- a guided HyperOS setup screen.

**Camera evidence capture should remain a separate gated item, not part of Milestone 2's core.** Test E proved that background capture is technically possible on this device, but before it is built:
1. Resolve the indicator finding. On this device the tester did not see a camera indicator during the background capture. The requirement is that capture is never secret, so the product must show its own clear, persistent disclosure (for example a lock-screen notification) and must not rely on the OS indicator alone.
2. Find out *why* Android allowed it (for example an exemption tied to active device-admin status). If it depends on that, it may change with Android versions or OEM builds, and must be re-verified per device and OS update.
3. Complete a privacy and policy review before any image is kept. Photographing people who pick up the phone raises data-protection questions (for example consent and notice duties under the Nigeria Data Protection Act 2023) and app-store policy questions.

The feasibility branch stays separate from `main` until these results are reviewed.


---

# Milestone 2 — Reliable Monitoring Core (branch `milestone-2-monitoring-core`)

**Build:** debug-build-11 (commit a29da44), the first build signed with the fixed test key. CI build, 38 unit tests, 11 emulator integration tests and the no-INTERNET check pass; none of that is a device result.
**Installed on the Redmi 12C on 2026-11-28** after a one-time uninstall (builds ≤ 10 used a different key each). Device evidence: `docs/evidence/2026-11-28/m2-health-verified-intrusion-152457.png` and `m2-1558-1.png` … `m2-1558-6.png` (Labs, History, Health at 15:58) and `m2-1622-1.png` … `m2-1622-12.png` (run at 16:21-16:22) `m2-1705-1.png` … `m2-1705-6.png` (16:48-17:05) `m2-9-1.png` … `m2-9-6.png` (deactivation via Android Settings, 17:08-17:12) `m2-5-1.png` … `m2-5-3.png` (run at 17:43-17:44) and `m2-5-run2-1.png` … `m2-5-run2-3.png` (run at 20:35-20:36).

| # | Physical-device test | Result | Observation |
|---|---|---|---|
| M2-0 | Upgrade and migration of Milestone 1 history | NOT TESTED | Not testable after the one-time uninstall (no Milestone 1 history left to migrate). |
| M2-1 | Owner authentication (biometric and PIN fallback, auto-lock) | **PASS (unlock part)** | History, Settings and Labs were opened after authenticating (screenshots at 15:58). Cancel → stays locked, the PIN fallback, and re-lock after leaving the app are not yet shown. |
| M2-2 | Protected settings + audit records | **PASS (audit part)** | History #14, 15:57:09, *Setting changed by owner*, source Owner: "Experimental Labs tools enabled." Threshold change not yet shown. |
| M2-3 | Protected deletion + "history cleared" record | **PASS** | History #17, 16:20:18, *History cleared by owner*, source Owner: "The owner cleared 1 record(s) after authenticating." The audit record stays after clearing. |
| M2-4 | Callback, counter, threshold with the new incident logic | **PASS** | Run 16:21:56-16:22:01: first failure "Android count 1 · incident 1 · Normal · New incident"; #23 count 3 · incident 3 · Normal; #24 *Intrusion threshold reached* at incident 3 (threshold 3), notification posted; #25 count 4 · incident 4 · Normal, with **no second intrusion** (once per incident). Success callback: #13 at 15:55:58, previous failed attempts 3, Android count 0. |
| M2-5 | New incident after a biometric unlock + 15-min gap | **FAIL on build 11 → fixed in code, re-test required** | Run 20:35-20:36 (build 11, after fingerprint unlock and 16-min gap): #98 20:35:22 Android count **5**, incident 1, **"Duplicate (not counted)"**, app not open, process age **4.7 s**; #99 20:36:06 count 6, incident 2, Normal; #100 20:36:10 *Password succeeded*, previous failed attempts 6. **No second alert**: 3 real failures (Android 4, 5, 6) were counted as 2. **Cause:** IntruderSnap was cold-started (~5 s) to deliver the first failure of the group, and by then Android's counter already included the next failure. The old rule ("same Android count = duplicate") then dropped a real failure. The 17:43 run shows the same pattern. **Fix** (IncidentTracker): every broadcast is counted, and Android's counter can only add failures, never remove them; incident count = max(broadcasts received, Android's count − baseline); baseline uses the last counter value seen before the incident. A unit test replays this exact device sequence. Must be re-tested on the Redmi with the new build. |
| M2-6a | Closed-app delivery (app closed, short interval) | **PASS** | Labs › background delivery: "3 failed callback(s) delivered while IntruderSnap was not open (3 in a process started within 10 s of the callback). Latest: process age 2469 ms, standbyBucket 5 (EXEMPTED), backgroundRestricted=false, ignoringBatteryOptimizations=true." History #13 (success, 15:55:58): "App open when received: No (process age 5605 ms)". **HyperOS started IntruderSnap's process specifically to deliver the lock-screen callbacks.** Health = **Verified**. Settings at the time: Android battery optimization off for IntruderSnap; Autostart / HyperOS battery saver not recorded. |
| M2-6 | **Long-idle HyperOS background delivery (≥ 2 h idle)** | NOT TESTED | (Milestone 1 open item) The Verified state above does not by itself prove long-idle delivery. |
| M2-7 | Reconciliation of missed callbacks | **PASS (consistent case)** · missed case NOT TESTED | Health: "Last successful reconciliation 2026-11-28 16:22:06 · Consistent with Android's counter", with Android's counter = 4 matching the incident. No missed callback has occurred, so the missed-failure path is verified on the emulator/unit tests only. |
| M2-8 | Admin loss detection (in-app deactivation) | **PASS** | 17:04:05: #76 *Setting changed by owner* "Owner deactivated the device administrator from IntruderSnap"; #77 *Device admin disabled*, source `DeviceAdminReceiver.onDisabled`, "Owner alert: Notification posted". The notification "IntruderSnap monitoring stopped" appeared. Health: **Permission required**, "Device administrator is not active", status Inactive, watch-login Not granted. Re-activated: #78 *Device admin enabled* 17:05:15. (An earlier disable/enable pair, #74/#75 ~17:03, is also present.) |
| M2-9 | Admin loss via Android Settings | **PASS (detected when the app next ran)** · `onDisabled` callback **not received** | At ~17:08 the device admin was deactivated in HyperOS Settings › Device admin app. **No** *Device admin disable requested* / *Device admin disabled* record was written, and no new notification appeared (the shade at 17:11 shows only the 17:04 notification). HyperOS also showed no app-supplied warning on its deactivation screen, which suggests `onDisableRequested` was not delivered either. When IntruderSnap was opened at 17:12:16, `AdminStatusCheck(app open)` recorded **#79 *Device admin found inactive*** and posted **"IntruderSnap monitoring stopped"**; Health = Permission required. Requirement 5 ("record and notify when the application next has an opportunity to run") is met. Immediate notification after a Settings deactivation is **not** achieved on this device. The periodic check runs about every 6 h, so without opening the app the detection could take hours. The cause (HyperOS not delivering the admin-disabled broadcast to a background app, or another reason) is not established. |
| M2-10 | Reboot / Direct Boot with encrypted storage | NOT TESTED | |
| M2-11 | Health confidence states | **PASS** | **Verified** with the correct reason after closed-app callbacks; **Permission required** with "Device administrator is not active" after deactivation (17:04). Last reboot, boot count and admin-check times shown. |
| M2-12 | Retention setting | **PASS** | History #36 16:48:12 "Retention changed from 90 to 30 days", #37 16:48:26 "from 30 to 90 days", both source Owner. Recent records remained. Deletion of old records is verified by the emulator test only. |
| M2-13 | Labs hidden by default | **PASS** | After install the tabs were Health · History · Setup · Settings (screenshot 15:24). Enabling experimental tools (audit record #14) added the Labs tab; its contents were shown only in an unlocked session. |
| M2-14 | Camera Privacy Dashboard / lock-screen notification (experimental) | NOT TESTED | Related Labs observation (#22, 16:21:58): test E captured a frame from the background with deviceLocked=true, but the app had been on screen 12.5 s earlier, so the grace-period rule correctly recorded it as **INCONCLUSIVE**, not PASS (shown as NOT TESTED on the M1-results card). The arm switch turned itself off after the attempt (one-shot confirmed). |
