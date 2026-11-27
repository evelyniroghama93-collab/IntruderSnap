# IntruderSnap — Milestone 1/6: Technical Feasibility Spike

This prototype answers one question: **can IntruderSnap find out when someone enters an incorrect PIN, pattern or password on the real Android lock screen of a Xiaomi / Redmi phone running Android 14 (API 34)?**

It uses the official Device Administration API (`DeviceAdminReceiver` with the `watch-login` policy only). There is no fake lock screen.

> **Milestone 1** (branch `milestone-1-feasibility-spike`): native lock-screen monitoring was **verified** on a Redmi 12C running HyperOS OS1.0.16.0. See [docs/COMPATIBILITY_RESULTS.md](docs/COMPATIBILITY_RESULTS.md).
>
> **Milestone 2** (branch `milestone-2-monitoring-core`, this branch): reliable monitoring core. It covers reconciliation with Android's counter, guided HyperOS setup, a monitoring-health screen, owner-protected encrypted history, admin-loss detection and retention. It is CI-verified (build, 38 unit tests, 11 emulator integration tests, no-internet check). **Physical-device results: NOT TESTED.** Design, file paths and known limitations are in [docs/MILESTONE_2.md](docs/MILESTONE_2.md); device tests M2-0 to M2-14 are in [docs/TEST_PLAN.md](docs/TEST_PLAN.md).
>
> Neither branch is merged into `main` until the project lead has reviewed the physical-device results. Nothing in this repository claims a result that has not been observed.
>
> Since Milestone 2: minimum Android version is **11 (API 30)**. The main tabs are Health · History · Setup · Settings. The Milestone 1 camera tools and acceptance screen are under **Labs**, hidden unless an authenticated owner enables experimental tools.

---

## 1. What the prototype does

| Area | Implementation |
|---|---|
| Lock-screen failures | `IntruderSnapAdminReceiver.onPasswordFailed(Context, Intent, UserHandle)` |
| Success after failures | `IntruderSnapAdminReceiver.onPasswordSucceeded(Context, Intent, UserHandle)` |
| System counter | `DevicePolicyManager.getCurrentFailedPasswordAttempts()` |
| Threshold | 3 / 5 / 7 / custom (1–30), default **3**. Creates one intrusion event, an in-app alert and a notification per incident |
| History | JSON-lines file in **device-protected storage**, fsync'd on every write |
| Dashboard | Device, ROM, admin status, monitoring status, counters, threshold, last events, camera permission |
| Camera feasibility | Tests A–G, all visible and user-initiated. Frames are measured and **discarded**, never stored |
| Results | The 7 acceptance criteria, derived only from recorded evidence, plus a shareable text report |

Device-admin policies requested: **`watch-login` only.** It does **not** request wipe-data, reset-password, force-lock or any other policy.

## 2. Build

Requirements: Android Studio (Koala or newer), JDK 17, and Android SDK Platform 34.

```bash
./gradlew testDebugUnitTest   # unit tests (threshold logic)
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and click **Run**.

**CI:** `.github/workflows/android-debug-apk.yml` builds and unit-tests the project on every push and uploads `app-debug.apk` as a workflow artifact. To get it, open GitHub → Actions → *Build debug APK* → latest run → *intrudersnap-debug-apk*.

Pinned versions: AGP 8.5.2, Gradle 8.9, Kotlin 2.0.21, Compose BOM 2024.09.03, CameraX 1.3.4. `compileSdk` and `targetSdk` are 34; `minSdk` is 26 (the 3-argument callbacks were added in API 26).

## 3. Install on the Redmi

1. On the phone, open **Settings › About phone** and tap **OS version** (or **MIUI version**) 7 times to enable Developer options.
2. Open **Settings › Additional settings › Developer options** and enable **USB debugging**. To install with adb you also need **Install via USB**, which requires being signed in to a Mi account.
3. Run `adb install -r app-debug.apk`, or copy the APK to the phone and open it.
4. Open IntruderSnap. The **Dashboard** shows the exact model, Android version, API level, MIUI/HyperOS version and security patch.

## 4. Activate and deactivate the device administrator

- **Activate:** Dashboard › **Activate device administrator**. This opens Android's own system screen; the owner must confirm there. The app cannot activate itself.
- **Deactivate:** Dashboard › **Deactivate**, or Settings › Passwords & security › Privacy › Special app access › Device admin apps (the path varies by MIUI/HyperOS version).
- **Uninstalling:** Android will not uninstall an app while it is an active device administrator. **Deactivate first, then uninstall.**

## 5. Testing

See **[docs/TEST_PLAN.md](docs/TEST_PLAN.md)** for the 15-step physical-device procedure. Record results in **[docs/COMPATIBILITY_RESULTS.md](docs/COMPATIBILITY_RESULTS.md)**. The technical analysis is in **[docs/FEASIBILITY_REPORT.md](docs/FEASIBILITY_REPORT.md)**.

**Safety:** use a phone you control. Keep the correct credential at hand. Never enter enough wrong attempts to reach Android's long lockout delays or any configured wipe policy; the plan uses at most 3–4 wrong attempts per cycle.

Logcat filter: `adb logcat -s IntruderSnap/Admin IntruderSnap/Login IntruderSnap/Events IntruderSnap/CamExp IntruderSnap/CamFGS`. Logs never contain credentials; Android does not give them to admin apps.

## 6. Known limitations (by design of Android)

- **Biometrics:** fingerprint and face failures are not password failures and are **not** reported or counted.
- **Success callback:** `onPasswordSucceeded` is only sent after a correct credential that **follows failed attempts**. It does not fire on every unlock, and may not fire if the phone is finally unlocked with a fingerprint.
- **Background camera:** expected to be **BLOCKED** on Android 14; test E measures it. The app never tries to bypass camera privacy restrictions.
- **Xiaomi power management** may delay or drop delivery to a stopped app. Changing battery settings does not guarantee delivery.

## 7. File map

```
IntruderSnap/
├── README.md
├── settings.gradle.kts · build.gradle.kts · gradle.properties · gradlew · gradlew.bat
├── gradle/wrapper/gradle-wrapper.jar · gradle-wrapper.properties
├── .github/workflows/android-debug-apk.yml
├── docs/TEST_PLAN.md · FEASIBILITY_REPORT.md · COMPATIBILITY_RESULTS.md
└── app/
    ├── build.gradle.kts · proguard-rules.pro
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/res/xml/device_admin_policies.xml
        ├── main/res/values/strings.xml · themes.xml · colors.xml
        ├── main/res/drawable/ic_launcher_foreground.xml · ic_stat_shield.xml
        ├── main/res/mipmap-anydpi-v26/ic_launcher.xml
        ├── main/java/com/intrudersnap/prototype/
        │   ├── IntruderSnapApp.kt
        │   ├── admin/IntruderSnapAdminReceiver.kt   ← DeviceAdminReceiver
        │   ├── admin/AdminController.kt             ← DevicePolicyManager integration
        │   ├── admin/LoginEventProcessor.kt         ← record / count / threshold / notify
        │   ├── boot/BootReceiver.kt
        │   ├── camera/CameraFeasibilityService.kt   ← tests C, D, E (visible FGS)
        │   ├── camera/BackgroundCameraExperiment.kt ← test E trigger
        │   ├── data/SecurityEvent.kt · EventRepository.kt · SettingsRepository.kt · RuntimeEnvironment.kt
        │   ├── device/DeviceInfoProvider.kt
        │   ├── domain/ThresholdPolicy.kt · AcceptanceEvaluator.kt · ReportBuilder.kt · TestOutcome.kt · CameraTestKey.kt
        │   ├── notify/Notifier.kt
        │   └── ui/MainActivity.kt
        │       ui/theme/Theme.kt · ui/components/Components.kt
        │       ui/dashboard/DashboardScreen.kt · DashboardViewModel.kt
        │       ui/history/HistoryScreen.kt · HistoryViewModel.kt
        │       ui/settings/SettingsScreen.kt · SettingsViewModel.kt
        │       ui/camera/CameraTestScreen.kt · CameraTestViewModel.kt
        │       ui/results/ResultsScreen.kt · ResultsViewModel.kt
        └── test/java/com/intrudersnap/prototype/domain/ThresholdPolicyTest.kt
```

Milestone 2 (the full IntruderSnap application) must not start until the native lock-screen test has been run and documented on the Redmi.
