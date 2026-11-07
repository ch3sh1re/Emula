# Emula for Wear OS

Standalone manual UID storage and experimental emulation for Wear OS watches
running Android 15 / API 35 or newer. Activation has no manufacturer/model whitelist;
it requires NFC and the ADB permission. The Android OEM UID API remains primary.
If it is absent, Emula offers an experimental raw NCI path when raw transport and
foreground polling control are available. This allows testing on unknown models,
but does not establish their compatibility. A failed OEM activation is restored;
it does not automatically switch writers.

The user confirmed a 4-byte UID on Samsung SM-L355F with an external reader on 2026-10-17.
The reported firmware was Android 17 / API 37, L355FXXS1AZH4, security patch
2026-08-05. Other models and screen-off behavior are not verified.

The editor accepts 4-, 7-, and 10-byte UIDs (8, 14, or 20 HEX characters).
Physical-reader evidence currently covers 4 bytes only. Only the UID is stored,
not card memory, keys, or APDU behavior.

## Controls

The app launcher shares the phone's Emula icon (two overlapping cards).
Startup shows the same round icon as the app launcher, centered at 48×48 dp on
a black background. The launcher theme uses AndroidX SplashScreen and switches
to the normal watch theme before drawing the UI, without an artificial delay.

- Receive from phone opens a temporary Bluetooth receiver. On the paired phone,
  choose Send all cards to watch and select this watch the first time. Allow
  Nearby devices on both devices and keep both apps open during transfer.
  Only new UIDs are appended; existing names and cards remain unchanged.
  Delivery is acknowledged after storage; retries skip already received UIDs.
  This is direct encrypted RFCOMM, without a Google SDK or cloud relay.
  The receiver closes on Cancel, backgrounding or a 90-second timeout.
  Actual phone/watch Bluetooth interoperability still needs tester confirmation.
- Tap a saved card to enable emulation; tap the active card again to stop.
- Progress appears below the selected card. Missing ADB permission / disabled NFC
  shows a short adjacent message with an action. Actual emulation failures open a
  persistent dialog offering a diagnostic report transfer to the phone.
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
snapshot. Experimental raw activation requires explicit approval for the exact
firmware fingerprint, a fresh complete snapshot of all six parameters it changes,
and durable storage before the first SET. It uses the existing experimental profile
and verifies UID readback after resuming polling. Stop/failure restores the saved
values and verifies every parameter, retaining recovery state if that fails.
Consent is stored separately from recovery state and expires after a firmware
change. Settings > Disable experimental NFC restores an active raw session before
revoking consent. Existing phone NFC profiles are unchanged.

On 2026-11-04, SM-L315F / fresh7ul / API 36 reported a successful seven-parameter
read-only GET_CONFIG in 28 ms, but no OEM UID API. SM-L320 / fresh8bs / API 36 also
reported no OEM API; its report did not include a raw probe. Neither report proves
raw writes, restoration, or reader acceptance. Test those using this experimental
release, beginning with a 4-byte UID, then stop and prepare/send another report.

## Packages and installation

- Release: dev.dunno.emula, version 1.0.4-wear / code 10005. Official phone/watch releases
  share a Play listing. Local release builds require your own signing key.
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
installation through Google Play. The first opening without permission offers a
compact Setup / Later prompt. Setup explains the grant and can open system
Developer options; it does not pair an ADB client or execute the grant. A new grant
is detected without activating NFC. On Samsung, double press of Home can be assigned
to open Emula in the watch system settings.

## Diagnostics

Settings > Diagnostics > Prepare report collects an English report with a fresh
read-only GET; no prior emulation attempt is required. Reports include device,
firmware, permissions, OEM/raw availability, firmware consent, polling control,
and operation/restoration evidence. The last operation result is kept separate
from the diagnostic GET result. A failed or unavailable probe remains shareable.
Selectable text is shown on the watch. Opening diagnostics preserves the existing
report without a probe. Send report to phone automatically prepares it if needed,
including from an error dialog; a just-prepared report is reused for transfer.
In the phone menu choose Get watch diagnostics, select the watch, then
review / copy / share the received report. No report is automatically sent to a
developer or external service. Diagnostic transfer requires phone 1.0.16 (36) and
Wear 1.0.2-wear (10003) or newer; older releases support card transfer only. See
[the diagnostic flow and limits](../docs/watch-diagnostics.md).
Include the reader model and whether it detected the
selected UID when reporting a result. Debug export:

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
WatchReleasePolicy owns strict HEX parsing. WatchActivationPolicy owns watch/API
eligibility, OEM priority and exact-firmware consent matching. WatchNfcSession and
WatchSessionStore preserve activation/restoration ordering. WatchHardwareBackend
uses the shared OEM controller or consent-gated raw controller with exact snapshot
restoration.

WatchActivationPolicyTest covers fallback eligibility, consent and the reported
Samsung snapshot. WatchNfcSessionTest owns transaction/recovery behavior. WatchUiErrorTest covers
failure classification. WatchFlowInstrumentation exercises manual CRUD and
failure / setup UI on a clean emulator without NFC; it is not a hardware
test. Run the JVM and lint checks in [CONTRIBUTING.md](../CONTRIBUTING.md).
A successful build or emulator check does not establish hardware compatibility.
Physical raw activation/restoration, reader behavior and Bluetooth interoperability
remain unverified for the experimental watch path.

## Card transfer

Manual phone-to-watch append is implemented over direct Bluetooth. It requires
Nearby devices access and both apps in the foreground. There is no Data Layer
dependency, cloud relay or automatic catalog replication. See
[transfer behavior and device-check limits](../docs/watch-transfer.md).
