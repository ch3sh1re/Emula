# Emula for Wear OS

Standalone manual UID storage and OEM emulation for Samsung Galaxy Watch9
SM-L355F. The user confirmed a 4-byte UID on an external reader on 2026-10-17.
The reported firmware was Android 17 / API 37, L355FXXS1AZH4, security patch
2026-08-05. Other models and screen-off behavior are not verified.

The editor accepts 4-, 7-, and 10-byte UIDs (8, 14, or 20 HEX characters).
Physical-reader evidence currently covers 4 bytes only. Only the UID is stored,
not card memory, keys, or APDU behavior.

## Controls

- Tap a saved card to enable emulation; tap the active card again to stop.
- Hold a card to edit its name and UID. Saving preserves the card ID.
- Swipe left on a card to open deletion confirmation. Vertical movement remains
  scrolling; a right swipe is not consumed by the deletion gesture.
- Accessibility actions expose Edit card and Delete card without gestures.
- Add card opens a compact name / HEX UID / Save form without a title or explanatory
  paragraph. The UID field permits only 0-9 and A-F, with a 20-character limit.
- Settings contains Setup, Diagnostics, control instructions, and the supported scope.
  The main list has no branding heading, reader controls, or raw switches.

Editing or deleting the active card first restores NFC. A failed restoration leaves
the original card and recovery state available. Receiving or saving a card never
activates it. Pending state is persisted before activation, restored on a cold start,
and retained when rollback fails. A firmware change blocks restoration of an old
snapshot. Raw activation is unavailable; the backend can restore old snapshots left
by the earlier test app. Existing phone NFC profiles are unchanged.

## Packages and installation

- Release: dev.dunno.emula, version 1.0.0-wear / code 10001. Official phone/watch
  releases share a Play listing. Local release builds require your own signing key.
  Wear version codes use the 10000 range.
- Debug: dev.dunno.emula.watchtest. It updates the earlier watch test package and
  retains its cards and permissions. The release package has separate storage and
  needs its own permission grant; Android does not migrate data between packages.

On the watch, enable Developer options, ADB debugging, and Wireless debugging.
Pair with the pairing port, connect with the connection port, then install:

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb -s WATCH_IP:CONNECTION_PORT install -r wear/build/outputs/apk/release/wear-release.apk
adb -s WATCH_IP:CONNECTION_PORT shell pm grant dev.dunno.emula android.permission.WRITE_SECURE_SETTINGS
```

For the debug update, use wear/build/outputs/apk/debug/wear-debug.apk and grant
WRITE_SECURE_SETTINGS to dev.dunno.emula.watchtest instead. The app shows the correct
command for the installed package under Setup. The grant remains necessary after
installation through Google Play. On Samsung, double press of Home can be assigned
to open Emula in the watch system settings.

## Diagnostics

Settings > Diagnostics builds an English report. Read NFC configuration performs
a read-only GET; it does not activate a card. Reports include device, firmware,
permissions, OEM capability, and operation / restoration evidence. No report is
automatically sent. Debug export:

```sh
adb -s WATCH_IP:CONNECTION_PORT exec-out run-as dev.dunno.emula.watchtest cat files/nfc-diagnostics.txt > watch-nfc.txt
```

The release is not debuggable, so run-as export is limited to the debug package.
Reports can contain UIDs. Firmware source research and its limits remain in
[NFC-source-review.md](NFC-source-review.md). collect-watch-nfc.py only reads device
information and optionally copies framework / NFC files.

## Build

```sh
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
  PATH=/usr/lib/jvm/java-17-openjdk/bin:/usr/bin:/bin \
  ./gradlew :wear:assembleDebug :wear:assembleRelease :wear:bundleRelease
```

Release signing reads the existing local keystore.properties, including secret-file
references. Signing material is never included in release artifacts.

WatchActivity owns the compact list/editor, gestures, and serialized card operations.
WatchReleasePolicy owns model scoping and strict HEX parsing. WatchNfcSession and
WatchSessionStore preserve activation/restoration ordering. WatchHardwareBackend
uses the shared OEM controller and retains exact legacy snapshot restoration.

WatchNfcSessionTest owns transaction/recovery behavior. WatchFlowInstrumentation
exercises manual card CRUD only on a clean emulator without NFC; it is not a hardware
test. Run the JVM and lint checks described in
[CONTRIBUTING.md](../CONTRIBUTING.md); a successful build does not establish hardware
compatibility.

## Deferred synchronization

Phone-to-watch card transfer is intentionally deferred; this release has no Data
Layer dependency, synchronization service, Bluetooth permissions, or network sync.
