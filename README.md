# Emula

A small Android app for checking emulation of four-byte NFC-A UIDs.
It can store several cards.

Emula is free and has no ads, paid features, purchases, subscriptions or donations.

Emula reproduces only the UID. The physical card retains its MIFARE memory, keys, counters and
cryptographic authentication. Use Emula with cards and readers
you have permission to test.

## Features

- Store multiple cards locally on the phone.
- Enter a UID manually or read it from a physical NFC-A card.
- Keep the editor and its draft open when the screen rotates.
- Account for system bars, gesture navigation and display cutouts in either orientation.
- Edit or delete cards through the `⋮` menu.
- Tap a card to activate it and tap it again to restore the normal NFC configuration.
- Restore the default NFC configuration on every cold start.

Restarting the process, the phone or NFC ends the active selection.
Saved cards stay in the list until you choose one to activate again.

## Compatibility

The app uses the system OEM method
`NfcOemExtension.emulateNfcTechnologyATag()`. It appeared in Android 16 QPR2
(`android-16.0.0_r4`, 25Q4, API 36.1) and is also present in Android 17.

The OnePlus 15 CPH2747 on Android 16 has a separate path. The reviewed global firmware
provides the system library `nfcvendorlib` and a method called
`setHceTypeAConfig()` that performs the ST54L configuration inside the NFC service.
On this model the app prefers the vendor path for the matching firmware and uses CTS Type-4A
parameters `ATQA 06 0C` and `SAK 20` with a four-byte UID. Before activation the app saves
the system access-card state for restoration after deactivation, a failure or the next
cold start. The app manages the system RF-field guard during the session
so the configuration persists when an external reader first detects the phone.
An external reader confirmed activation of a four-byte UID through this path on the
OnePlus 15 CPH2747 running Android 16 / API 36. On other models the library is
optional and the app skips this path.

The app checks whether the method is present on the installed system. Checking only
`SDK_INT` would be insufficient because Android 16 QPR2 still reports major API level 36, and a manufacturer
may update or backport the NFC module separately. Original Android 16 and Android 16
QPR1 require such a backport for this API.

The path was confirmed on a Pixel 8 Pro running Android 17 / SDK 37, build
`CP2A.260805.005`, and a Samsung Galaxy A16 running One UI 8.5 based on Android 16 QPR2. Samsung
uses the CTS Type-4A profile (`06 / 0C / 20`, RATS `40`) because the minimal
Pixel profile caused an NFC controller error during activation. The CTS profile was also
tested on Pixel. The controller saw the reader's RF field, but the card failed to activate, so
the two families use separate parameters. Other models and firmware need a physical
test to establish whether their controller accepts the configuration.
The app chooses a profile using `Build.MANUFACTURER`. Google devices use the tested
Pixel profile, while Samsung and manufacturers still awaiting tests use the reference CTS profile.
The choice does not depend on the NFC controller model because Android does not expose it
to apps through a stable system API.

Android 15 / One UI 7 predates the standard UID emulation method. The app's `⋮` menu
includes a read-only diagnostic for the older raw NCI API. It sends
`CORE_GET_CONFIG` and leaves the UID and NFC mode in place. It requires
`WRITE_SECURE_SETTINGS`, the same permission used for raw commands. A successful diagnostic
confirms communication with the controller. Setting a persistent UID requires further verification
because the older NFC stack rewrites the listen parameters when polling starts.
On Samsung devices with Android 15, the app temporarily disables polling and listening using the public
discovery API before `CORE_SET_CONFIG`. One UI 7 blocks the older hidden
`pausePolling` API, and the NXP controller rejects configuration changes while discovery is active.

On Google, Samsung, OnePlus and Sony Xperia 1 VI XQ-EC72 devices without an OEM or vendor method,
tapping a card automatically uses raw NCI. Tested Pixel, Samsung
and Sony devices use their existing full NFC-A profile. If the OnePlus 15 CPH2747 vendor path
is unavailable or Raw mode was selected manually, the raw fallback first tries changing only
`LA_NFCID1 (0x33)`. The existing full profile remains available if the minimal candidate fails.
The app sends `CORE_SET_CONFIG`, then reads back
`0x30/0x31/0x32/0x33/0x58` and requires `0x33` to match before marking the UID active.
Tapping again sends the reset configuration. If the process ends while a card is active,
the app records the pending reset and performs it the next time it starts
from cold. This path primarily targets Android 15 and early Android 16 builds. Compatibility
depends on the individual NFC controller's response.

On an uncatalogued device, you can start the app and add, edit or read UIDs normally.
If the standard method is absent but the raw NCI API is available,
tapping a card for the first time offers experimental Raw mode. Before writing,
the app runs a read-only probe and saves the controller's original parameters so it can
restore them on failure. Approval is tied to the current
`Build.FINGERPRINT`, and the app requests it again after a firmware update.
On a device without either interface, the app reports that condition when you try activation.

On recent Pixel, Samsung, OnePlus and Sony Xperia 1 VI XQ-EC72 devices you can select Raw mode
for all cards from the `⋮` menu. The preference persists when you close the app and applies to
subsequent activations. With Raw mode off, the app uses the standard OEM API.
On a device with the standard API, switching on Raw mode requires confirmation. Switching it off
takes effect immediately. Changing the preference does not restart an active card.
On catalogued devices without the standard method, the app selects Raw mode automatically. The menu
shows it as the only available mode. It uses the family's tested profile,
the minimal Pixel profile for Google or CTS Type-4A for Samsung, OnePlus and the supported Xperia.
On OnePlus and Xperia, raw remains experimental. These devices use OEM
`pausePolling` when available and otherwise use Android 15+ public discovery control.
The OnePlus 15 CPH2747 uses public discovery control first
to turn off both polling and listening before a write. The app then waits briefly
for the NFC stack to apply the routing change.
Confirmation for a particular controller and firmware requires an NCI response and a successful
UID read by an independent reader. Diagnostics retain the latest OEM and raw operations in memory.
For the OnePlus vendor path, the report records the availability of the library, Binder adapter and method,
the requested ATQA/SAK/UID, RF guard and snapshot state, and the rollback result. For raw operations, it shows
the purpose, candidate, polling state, TX/RX, statuses and UID readback. Operation history stays in
memory. The app temporarily stores the original configuration for recovery
and clears it after successful restoration. The read-only
probe is also available on uncatalogued devices and reads
`0x30/0x31/0x32/0x33/0x58/0x59/0x85` without writing to the controller.

## Build and install

Open the project in Android Studio with JDK 17 or build from the command line.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses its own application ID, `dev.dunno.emula.debug`, so you can install it
alongside the release build.

`./install_and_grant.sh` installs the debug APK and grants the debug package its permission.
For a release APK, pass the path and application ID explicitly.

```bash
./install_and_grant.sh app/build/outputs/apk/release/app-release.apk dev.dunno.emula
```

The OEM NFC API requires a one-time system permission grant via ADB.

```bash
adb shell pm grant dev.dunno.emula.debug android.permission.WRITE_SECURE_SETTINGS
```

The application ID for the release package is `dev.dunno.emula`.

The permission survives a phone restart and an update installed with `adb install -r`.
After uninstalling the app, grant it again.

When opened without permission, Emula shows setup instructions and the ADB command
for the installed package. Tap **Copy ADB command** to copy it. You can dismiss the dialog
and continue adding or reading cards. Rotating the screen restores an open dialog
and respects a previously dismissed one.

Emula detects the grant without restarting. While the app is open and the grant is
missing, it checks once per second. Once detected, the dialog closes,
the **Copy ADB command** button disappears and you can start activating
cards. The timed checks stop. Returning to the app triggers an immediate permission check.

## Usage

1. Tap `+` and enter a card name.
2. Enter the four UID bytes in hex or tap **Read from card** and hold the card against
   the back of the phone.
3. Save the card.
4. Tap the card in the list to activate it. A light outline and an **Active** badge
   next to its name mark the active card. Tapping it again restores normal NFC.
5. Tap the active card again to restore normal NFC.

Only one card can be active at a time. Selecting another card replaces
the current configuration.

The default interface language is English. To add a translation, create
`app/src/main/res/values-<language>/strings.xml` and add the language code to `res/xml/locales_config.xml`.

The core model, storage and UID handling use Kotlin. Run the JVM unit tests
with the following command.

```bash
./gradlew testDebugUnitTest
```

Only debug builds write diagnostic messages to logcat. The release app still displays
errors that require the user's attention.

## Reporting emulation problems

If activation fails or the device lacks an emulation interface,
the app offers to open **NFC diagnostics** and send a report to
`dunn0dud3@gmail.com`. If Emula shows an active card but an external reader cannot
detect it, open the same report from `⋮` → **NFC diagnostics**.

**Email** explains what the report contains and opens a draft in your email app.
You can review the contents before sending. The report includes the Emula version,
device model, firmware and NFC operation results. It may contain card UIDs. If you lack an email
app, copy the report and send it through webmail. Include a description of the problem
and the external reader model. Opening diagnostics or an email draft does not send any
new NFC commands. **Check raw NCI** is a separate read-only check.

## Signing a release build

Use a dedicated upload key for Google Play. Create it interactively and store
the keystore outside the repository.

```bash
keytool -genkeypair -v \
  -keystore /safe/path/emula-signing.p12 \
  -storetype PKCS12 \
  -alias emula \
  -keyalg RSA -keysize 4096 -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties` and provide the absolute path,
alias and passwords. Then build the signed bundle.

```bash
./gradlew bundleRelease
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
```

Git ignores `keystore.properties`, `.signing/`, `*.jks`, `*.p12` and `*.keystore`. Keep the keystore and password
in at least two independent secure locations. With Play App Signing, this key is used
to upload new versions. Google signs the APKs distributed to users with the app signing key.

Use the system log to check the service operation.

```bash
adb logcat -s NfcService Emula AndroidRuntime
```

`emulateNfcACard: setConfig:true` indicates that the saved UID was enabled.
`setConfig:false` indicates restoration of the default NFC configuration.

## Reader results

On the Pixel described above, the method returned success and a Flipper Zero read the test UID
`01 02 03 04` as ISO14443-4A NFCID1. On the Samsung phone, another phone read the emulated UID
and the office reader accepted it. ISO14443-4B uses PUPI, and FeliCa uses IDm,
so these technologies present different identifiers.

## Documentation

- [Code map](docs/code-map.md) covers entry points, contracts and checks.
- [Changelog](CHANGELOG.md) lists changes by version.
- [TODO](TODO.md) tracks outstanding checks and the most recent release.
- [Archive](docs/history/README.md) contains historical prompts, reports and older release checklists.
