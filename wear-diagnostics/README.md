# Emula Watch Probe

Standalone diagnostic APK for Wear OS 5.1 / Android 15 (API 35) and later. It does
not activate cards. The package is `dev.dunno.emula.watchprobe`, independent of the
phone application. There are no activation controllers, startup NFC resets,
polling changes, or SET_CONFIG calls in this module.

`WatchNfcCapabilities` reports firmware, advertised NFC/HCE features, adapter state,
permission, and reflected OEM/raw API availability. Resolving a method is not proof
that UID emulation works. The **Прочитать NFC** button reuses
`LegacyNfcDiagnostics.runReadOnlyProbe`, reading only `0x30`, `0x31`, `0x32`, `0x33`,
`0x58`, `0x59`, and `0x85`. Transport and controller statuses remain in the report.

The Gradle sync task selects five existing diagnostic/codec sources from `app/`;
phone activities and activation controllers are not included. This is a probe,
not the shared-core extraction for a future production watch application.

Build with JDK 17:

```sh
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
  PATH=/usr/lib/jvm/java-17-openjdk/bin:/usr/bin:/bin \
  ./gradlew :wear-diagnostics:lintDebug :wear-diagnostics:assembleDebug
```

After pairing the watch's wireless ADB, use its serial explicitly if other devices
are connected:

```sh
adb -s WATCH_SERIAL install -r wear-diagnostics/build/outputs/apk/debug/wear-diagnostics-debug.apk
adb -s WATCH_SERIAL shell pm grant dev.dunno.emula.watchprobe android.permission.WRITE_SECURE_SETTINGS
adb -s WATCH_SERIAL shell am start -n dev.dunno.emula.watchprobe/dev.dunno.emula.WatchDiagnosticsActivity
```

Tap **Проверить API** after granting permission. Tap **Прочитать NFC** for the
read-only GET_CONFIG probe. The report scrolls by touch or the watch crown. Pull
the latest report without relying on watch clipboard or email apps:

```sh
adb -s WATCH_SERIAL exec-out run-as dev.dunno.emula.watchprobe cat files/nfc-diagnostics.txt > pixel-watch-3-nfc.txt
```

The report can contain the controller's current UID and firmware fingerprint.
Nothing is transmitted automatically. Debug logs use the existing `Emula` tag.
No physical watch compatibility is claimed until device checks have run.
