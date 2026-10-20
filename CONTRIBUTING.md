# Contributing

Start from `main` in a focused `feature/`, `fix/`, or `docs/` branch and open a pull
request targeting `main`. Explain the problem, resulting behavior, and relevant
verification. Start with [docs/code-map.md](docs/code-map.md) to locate code.

## Local checks

Use JDK 17 and Android SDK Platform 36. Debug builds/JVM tests need no private
signing files or physical NFC device.

```sh
./gradlew --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :wear:testDebugUnitTest :wear:lintDebug :wear:assembleDebug \
  :wear-diagnostics:lintDebug :wear-diagnostics:assembleDebug
```

For phone UI/input changes, install debug and instrumentation APKs on an emulator
or suitable Android device:

```sh
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.dunno.emula.debug.test/dev.dunno.emula.UidEditorTestInstrumentation
```

These checks do not open an NFC-writing Activity. NFC transport/reflection changes
also need an explicitly scoped physical-device check.

## NFC invariants

- Keep OEM activation primary and raw NCI as fallback or explicit override.
- Preserve device/profile scope and existing verified behavior.
- Record each raw SET with purpose, candidate, stage, TX/RX, framework status,
  and controller status.
- Resume polling on every exit after a successful pause; distinguish activation
  and rollback failures.
- UID-only activation changes only `LA_NFCID1`, verifies it, and restores its
  snapshot. Unknown-device writes need firmware-scoped consent and a full snapshot.
- Do not add commands or compatibility claims without device evidence. GETs are
  suitable for inspection; raw SETs are not inspection operations.
- Report model/firmware, path, statuses, reader result, restoration, and remaining
  limits for physical tests.

## Repository contents

Commit source, public documentation, and meaningful tests. Keep signing material,
local SDK paths, private card catalogs/reports, build logs, and captured firmware
outside Git. Release binaries belong in GitHub Releases rather than source history.
Use synthetic UID examples in tests and issues.

New original contributions use GPL-3.0-only. Preserve third-party notices.
