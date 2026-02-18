# Stage 8: Release Readiness, CI/CD, E2E Smoke

## Delivered
- CI/CD:
  - added `.github/workflows/android.yml`;
  - `build-and-unit` job: `lintDebug`, `testDebugUnitTest`, `assembleDebug`, `assembleRelease`;
  - `instrumentation-smoke` job: emulator run with all `app` androidTest smoke classes and report artifacts.
- Build reproducibility:
  - added Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`).
- Release hardening:
  - enabled `isMinifyEnabled=true` and `isShrinkResources=true` for `release`;
  - added env-based release signing config hooks (`IPTV_RELEASE_*`);
  - added practical ProGuard keep rules for Hilt/WorkManager/Room/Media3;
  - fixed WorkManager startup lint by removing `WorkManagerInitializer` in manifest;
  - added Leanback TV banner resource and manifest binding (`android:banner`);
  - fixed lint blockers for CI-wide `lintDebug`:
    - Media3 `UnstableApi` opt-in annotations in player modules,
    - API24-safe date parsing in scanner network data source,
    - diagnostics network permission lint annotations and API guard.
- E2E smoke:
  - importer UI received stable test tags for critical actions;
  - `AppSmokeTest` + `SyncSmokeTest` cover text import/save/player route and manual refresh smoke.

## Key Files
- `.github/workflows/android.yml`
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/drawable/tv_banner.xml`
- `core/network/src/main/java/com/iptv/tv/core/network/datasource/PublicRepositoryScannerDataSource.kt`
- `core/player/src/main/java/com/iptv/tv/core/player/PlayerContracts.kt`
- `feature/diagnostics/src/main/java/com/iptv/tv/feature/diagnostics/DiagnosticsViewModel.kt`
- `feature/player/src/main/java/com/iptv/tv/feature/player/PlayerScreen.kt`
- `feature/importer/src/main/java/com/iptv/tv/feature/importer/ImporterScreen.kt`
- `app/src/androidTest/java/com/iptv/tv/AppSmokeTest.kt`

## Signing Setup
Release signing is activated only when all env vars are present:
- `IPTV_RELEASE_STORE_FILE`
- `IPTV_RELEASE_STORE_PASSWORD`
- `IPTV_RELEASE_KEY_ALIAS`
- `IPTV_RELEASE_KEY_PASSWORD`

If they are absent, `assembleRelease` still builds an unsigned release artifact.

## Acceptance Readiness Checklist
- [x] Reproducible Gradle builds via wrapper.
- [x] CI runs lint + unit + debug/release assembly.
- [x] CI runs instrumentation smoke on emulator.
- [x] Release minification/resource shrinking is enabled.
- [x] E2E smoke includes import/save/player core scenario.
- [ ] Device burn-in (8h+) on target TV hardware.
- [ ] Weak-network playback soak validation on target ISPs/Wi-Fi.
- [ ] Production keystore wiring in CI secrets and signed release build.

## Suggested Verification Commands
- `./gradlew :app:lintDebug`
- `./gradlew lintDebug testDebugUnitTest`
- `./gradlew :app:assembleDebug :app:assembleRelease`
- `./gradlew :app:connectedDebugAndroidTest`
