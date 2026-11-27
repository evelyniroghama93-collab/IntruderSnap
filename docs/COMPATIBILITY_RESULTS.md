# IntruderSnap Milestone 1 — Compatibility Results

> Only observed results are recorded here. Anything not observed is **NOT TESTED**.
> Allowed values: **PASS · FAIL · BLOCKED · NOT TESTED**.
> Every result names its source: an in-app screenshot, the in-app report, logcat, or an owner statement.

## Device under test

| Field | Physical device | Source |
|---|---|---|
| Manufacturer / brand | Xiaomi (Redmi) | Dashboard screenshot 01 |
| Exact model | **Redmi 12C, model code 22120RN86G** (identified by the app) | Dashboard screenshot 01 |
| Android version / API level | 14 / 34 | Dashboard screenshot 01 |
| HyperOS version | HyperOS OS1.0 (OS1.0.16.0.UCVMIXM) | Dashboard screenshot 01 |
| Build | V816.0.16.0.UCVMIXM | Dashboard screenshot 01 |
| Security patch level | 2026-02-01 | Dashboard screenshot 01 |
| Device administrator | Active (watch-login only) | Dashboard screenshot 01 |
| Lock type used | Not recorded | — |
| Battery / Autostart settings during run 1 | Not recorded (assumed defaults) | — |
| Build tested | `debug-build-3` (commit 89e45b5) | Release page |
| Emulator testing | None performed | — |

> The owner's 2026-11-27 update lists the exact model as "not yet identified". The app has already identified it as **Redmi 12C (22120RN86G)**. Please confirm it in Settings › About phone.

## Acceptance results (physical device only)

| # | Criterion | Result | Observation | Evidence |
|---|---|---|---|---|
| 1 | `onPasswordFailed` callback | **PASS** | "Total recorded failed attempts: 6". These records can only be created by the system calling `onPasswordFailed`. | Screenshot 02 (2026-11-21 ~15:49) |
| 2 | `onPasswordSucceeded` callback | **PASS** | "Last successful authentication: 2026-11-21 15:45:20 · previous failures 1". | Screenshot 02 |
| 3 | `DevicePolicyManager` failed-attempt count | **PASS** | "Last failed attempt: 2026-11-21 15:49:16 · system count 3"; "System counter now: 3"; the counter matched the prototype count (3). | Screenshot 02 |
| 4 | Three-attempt threshold | **PASS** | Intrusion banner: "Time 2026-11-21 15:51:44 · System failed-attempt count 3 · Threshold 3 · source: system counter · Notification posted". | Screenshot 03 |
| 5 | Event persistence | **NOT TESTED** | No evidence that events written by one app process were reloaded by a new process. | — |
| 6 | Reboot behaviour | **NOT TESTED** | As of 2026-11-21 15:59 the phone had not been restarted. | Owner message |
| 7 | Xiaomi/HyperOS background behaviour | **NOT TESTED** | Callbacks arrived while the phone was locked, so IntruderSnap was not in the foreground. The run-1 build did not record whether the app process was still alive, so delivery to a *closed* app is unproven. The configuration matrix has not been run. | Screenshot 02 (partial) |
| 8 | Background camera feasibility | **NOT TESTED** | Test E not run. Camera permission was not granted at the time of screenshot 02. | Screenshot 02 |

**Main acceptance criterion (real failed-password callbacks from the Redmi's native lock screen): PASS.**

### Logs

- **Logcat: none captured yet.** All run-1 evidence comes from in-app screenshots.
- For the remaining tests, capture logcat over USB:
  `adb logcat -v time -s IntruderSnap/Admin IntruderSnap/Login IntruderSnap/Events IntruderSnap/CamExp IntruderSnap/CamFGS`
  Expected lines (no credentials are ever logged):
  - `onPasswordFailed received: systemCount=N prototypeCount=N threshold=3 uiInProcess=… processAgeMs=… standbyBucket=…`
  - `Intrusion threshold reached: effective=3 threshold=3`
  - `onPasswordSucceeded received: previousFailed=N systemCountNow=0`
- Without USB: Results › **Share test report**. The report includes every event with its diagnostics.

## Camera feasibility (A–G)

| Test | Result | Observation | Source |
|---|---|---|---|
| A. Camera permission | **PASS** | Granted by the owner. | Owner statement 2026-11-27 |
| B. CameraX while visible | **PASS** | Preview and one capture while IntruderSnap was open; frame discarded. | Owner statement 2026-11-27 |
| C. Camera foreground service (visible) | **PASS** | One frame delivered through the declared camera foreground service while the app was visible; frame discarded. | Owner statement 2026-11-27 |
| D. Android 14 FGS requirements | **NOT TESTED** (not reported) | The app records D automatically when C runs. Read the result from the Camera tab. | — |
| E. Background start after callback | **NOT TESTED** | — | — |
| F. Privacy indicator | **NOT TESTED** | — | — |
| G. Denial behaviour | **NOT TESTED** | — | — |

A, B and C show that the camera works **while the app is visible**. They say nothing about capture after a lock-screen failure. Only test E answers that.

> **Screenshots or the shared report are still needed** to attach evidence to A–C.

## Xiaomi configuration matrix (Test 15) — NOT TESTED

| Config | Autostart | Battery saver | Locked in Recents | App process closed first | Failed callback | Success callback | Threshold |
|---|---|---|---|---|---|---|---|
| A | default | default | no | yes | NOT TESTED | NOT TESTED | NOT TESTED |
| B | allowed | No restrictions | no | yes | NOT TESTED | NOT TESTED | NOT TESTED |
| C | allowed | No restrictions | yes | yes | NOT TESTED | NOT TESTED | NOT TESTED |
| D | default | default | no | Recents "clear all" | NOT TESTED | NOT TESTED | NOT TESTED |

## Reboot (Test 14) — NOT TESTED

| Observation | Result |
|---|---|
| "Boot broadcast received" event present | NOT TESTED |
| Failed callback recorded before first unlock (Direct Boot) | NOT TESTED |
| Failed callback recorded after reboot | NOT TESTED |

## Open items before Milestone 1 can be closed

1. Install the newest debug build. It records whether the app was open when each callback arrived, and adds the Xiaomi/HyperOS background-delivery result.
2. **Set the threshold back to 3.** The owner reports it is currently Custom = 1.
3. Run Test 13b (persistence), Test 14 (reboot), Test 15 configs A–D (background) and Camera test E.
4. Send the Results › **Share test report** text and, if possible, the logcat output.

## Recommendation

The callback tests (1–4) passed on the target device, so **native lock-screen monitoring is technically feasible on HyperOS OS1.0 / Android 14** through the supported Device Administration API. The recommendation is to proceed to Milestone 2 once items 5–8 above are recorded. They decide *how* Milestone 2 is built, not *whether* it can be.

**Proposed Milestone 2 — Reliable monitoring core (no evidence capture yet):**
- Production-quality onboarding: explain watch-login, activate the admin, request notification permission, and run a guided HyperOS setup with a self-test that confirms one real callback.
- Owner protection for the app itself: biometric/PIN confirmation (`BiometricPrompt`) before viewing or clearing history or deactivating, so an intruder holding an unlocked phone cannot quietly erase records.
- Tamper visibility: alert on `onDisableRequested` / `onDisabled`.
- Hardened storage and history: a schema with versioning; keep device-protected storage only for the minimum pre-unlock record.
- Alerting design: decide whether alerts stay on-device or need a remote channel (e.g. email or push to a second device). This needs a privacy review.

**Decisions that depend on the open tests:**
- **Test 7 (background delivery):** if callbacks are missed after HyperOS closes the app, Milestone 2 must include a documented setup flow and a delivery health check. It must not promise guaranteed delivery.
- **Test 8 (background camera):** if test E is **BLOCKED**, as Android 14's rules predict, automatic intruder photos from the lock screen are not a supported capability. The product must not advertise them, and no workaround should be attempted. Any evidence feature would then need a separate, visible, user-initiated design. If E passes, it still needs a privacy and policy review before it is built.

The feasibility branch stays separate from `main` until these results are reviewed.
