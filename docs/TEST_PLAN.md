# IntruderSnap Milestone 1 — Physical-Device Test Plan

**Target:** Xiaomi / Redmi, Android 14 (API 34), MIUI or HyperOS.
**Rule:** a result counts only if it was observed on the **physical device**. Emulator runs go in a separate column of `COMPATIBILITY_RESULTS.md` and never count toward acceptance.

## Safety rules (read first)

- Use a phone you own and control. Make sure the correct PIN / pattern / password is known and written down.
- Android starts lockout delays after **5** consecutive wrong attempts, and the delays get longer with each further failure. This plan never needs more than **4** wrong attempts in a row; unlock correctly before continuing.
- Check that no "erase data after N failed attempts" policy is set. Look in Settings › Passwords & security, and check whether a work profile or MDM is installed. If one is set, stop and use another device.
- Do not test with fingerprint/face during password tests. Unlock with the **credential**, so that the success callback can be observed.
- Charge the phone above 50 %.

## Preparation

1. Build or download `app-debug.apk` (see README).
2. Connect USB and run: `adb logcat -c && adb logcat -s IntruderSnap/Admin IntruderSnap/Login IntruderSnap/Events IntruderSnap/CamExp IntruderSnap/CamFGS ActivityManager:W`
3. Choose a secure lock type (PIN recommended). Note the type in the results file.

## Tests

| # | Action | Expected observation | Where to check |
|---|---|---|---|
| 1 | Install IntruderSnap and open it. | Dashboard shows manufacturer, model, Android version, API 34, MIUI/HyperOS version, security patch. Copy them into the results file. | Dashboard › Device |
| 2 | Tap **Activate device administrator**; confirm on Android's system screen. | System screen lists only "watch-login"-type wording (monitor screen unlock attempts). | System dialog |
| 3 | Return to the app. | Device administrator = *Supported* (active). History shows **Device admin enabled** with callback = yes. Native monitoring = *Not yet tested*. | Dashboard, History |
| 4 | Confirm threshold = 3 (Threshold tab). Allow notifications (Dashboard). Press the power button to lock the phone. | Screen off and locked. | — |
| 5 | Wake the phone and enter **one** incorrect credential. Stay on the lock screen. | — | — |
| 6 | While still locked, check whether the callback arrived. | Logcat (over USB) shows `onPasswordFailed received: systemCount=1`. If nothing appears within ~5 s, record **FAIL**. | Logcat |
| 7 | Still locked, enter a **second** incorrect credential. | Logcat shows a second `onPasswordFailed received`. | Logcat |
| 8 | Check the system failed-attempt counter. | Logcat shows `systemCount=2` (prototypeCount=2). | Logcat |
| 9 | Still locked, enter a **third** incorrect credential. | Logcat: `Intrusion threshold reached: effective=3 threshold=3`. | Logcat |
| 10 | Check that the threshold was triggered. | Notification "Possible intrusion detected" is posted (its content may be hidden on the lock screen). There is exactly ONE intrusion event for this incident. | Lock screen / shade, Logcat |
| 11 | Enter the **correct** credential (PIN/pattern/password, **not** fingerprint/face). | Phone unlocks. | — |
| 12 | Check the success callback. | Logcat: `onPasswordSucceeded received: previousFailed=3`. | Logcat |
| 13 | Open IntruderSnap › History, Dashboard and Results. Tap **Share test report** and save the text. | History shows 3 × Password failed (system counts 1, 2, 3), 1 × Intrusion threshold reached, 1 × Password succeeded (previous failed attempts = 3). Each record has ID, type, timestamp, system count, callback status and device model. Dashboard "Current incident" = 0. Without USB/logcat, use the History timestamps instead of steps 6, 8, 10 and 12. | History, Dashboard, Results |
| 13b | **Persistence:** end the app process: swipe IntruderSnap away in Recents, or run `adb shell am kill com.intrudersnap.prototype` (Android disables *Force stop* for active device admins). Reopen the app. | Earlier events are still listed; Results › Event persistence = PASS. | Results |
| 14 | **Reboot** the phone. On the FIRST lock screen after boot, enter **one** incorrect credential, then unlock correctly. Open the app. | A failed event with a new boot number. "Recorded before first unlock = Yes" means the callback arrived in Direct Boot. Results › Reboot behaviour = PASS if recorded. Also note whether a **Boot broadcast received** event appears. | History, Results |
| 15 | Repeat tests 4–12 under each documented configuration (table below). Before each run, end the app process (swipe from Recents or `adb shell am kill`), or leave the phone idle for 30+ minutes. | Record PASS/FAIL per configuration. | Results file |

### Test 15 configurations

| Config | Autostart | Battery saver (App info) | App locked in Recents |
|---|---|---|---|
| A (default after install) | default | default ("Battery saver") | no |
| B | allowed | "No restrictions" | no |
| C | allowed | "No restrictions" | yes |
| D | default | default | no, and Recents cleared with MIUI's "clear all" button before the test |

Do not disable any Android security protection for these tests.

## Camera feasibility (Camera tab)

| Test | Action | Record |
|---|---|---|
| A | Request camera permission; also try **Deny** once, then grant from App info. | PASS when granted; denial is shown under A/G |
| B | Open preview, capture one frame. | PASS/FAIL. Watch for the green camera dot (F) |
| C/D | Close the preview, run the foreground-service test. | C PASS/FAIL; D PASS when `startForeground(type=camera)` is accepted |
| E | Arm the switch, lock the phone, enter ONE incorrect credential, unlock correctly, open the Camera tab. | PASS, **BLOCKED** (with exception text) or INCONCLUSIVE. Note any app crash: Android may kill the process if `startForeground` is refused. The result is saved first |
| F | Tap Indicator seen / Not seen after B or C. | PASS/FAIL |
| G | Read the automatically recorded denial text. | Copy the text into the report |

## Emulator (optional, separate)

On an Android 14 emulator image, set a PIN, activate the admin, then lock the emulator and enter a wrong PIN. Emulator results go only in the "Emulator" column. They show that the code path works on AOSP, not on MIUI/HyperOS.
