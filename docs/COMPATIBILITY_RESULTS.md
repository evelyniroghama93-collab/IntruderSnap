# IntruderSnap Milestone 1 — Compatibility Results

> Fill this in **only** with observed results. Leave `NOT TESTED` where a test was not run.
> Paste the in-app "Share test report" text at the bottom as raw evidence.

## Device under test

| Field | Physical device | Emulator (optional) |
|---|---|---|
| Manufacturer / brand | | |
| Exact model (marketing name + model code) | | |
| Android version / API level | | |
| MIUI or HyperOS version | | n/a |
| Security patch level | | |
| Build number | | |
| Lock type used (PIN / pattern / password) | | |
| Device administrator activated (yes/no) | | |
| Tester / date | | |

## Acceptance results

| Criterion | Physical device | Emulator | Evidence (event IDs / logcat lines) |
|---|---|---|---|
| Native failed-password callback | NOT TESTED | NOT TESTED | |
| System attempt counter | NOT TESTED | NOT TESTED | |
| Successful authentication callback | NOT TESTED | NOT TESTED | |
| Threshold detection | NOT TESTED | NOT TESTED | |
| Event persistence | NOT TESTED | NOT TESTED | |
| Reboot behaviour | NOT TESTED | NOT TESTED | |
| Background camera feasibility (PASS / BLOCKED / NOT TESTED) | NOT TESTED | NOT TESTED | |

**Main test verdict (physical device only):** NOT TESTED
*(PASS only if a real `onPasswordFailed` callback was recorded from the Redmi's native lock screen.)*

## Camera feasibility (A–G)

| Test | Result | Exact detail / exception |
|---|---|---|
| A. Camera permission | NOT TESTED | |
| B. CameraX while visible | NOT TESTED | |
| C. Camera foreground service (visible) | NOT TESTED | |
| D. Android 14 FGS requirements | NOT TESTED | |
| E. Background start after callback | NOT TESTED | |
| F. Privacy indicator | NOT TESTED | |
| G. Denial behaviour | NOT TESTED | |

## Xiaomi configuration matrix (Test 15)

| Config | Autostart | Battery saver | Locked in Recents | Failed callback | Success callback | Threshold | Notes |
|---|---|---|---|---|---|---|---|
| A | default | default | no | NOT TESTED | NOT TESTED | NOT TESTED | |
| B | allowed | No restrictions | no | NOT TESTED | NOT TESTED | NOT TESTED | |
| C | allowed | No restrictions | yes | NOT TESTED | NOT TESTED | NOT TESTED | |
| D | default | default | no + Recents cleared | NOT TESTED | NOT TESTED | NOT TESTED | |

## Reboot (Test 14)

| Observation | Result |
|---|---|
| Boot broadcast received event present | NOT TESTED |
| Failed callback recorded before first unlock (Direct Boot) | NOT TESTED |
| Failed callback recorded after first unlock | NOT TESTED |

## Raw report

```
(paste "Share test report" output here)
```
