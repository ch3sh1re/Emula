# Emula

A small Android app for testing emulation of 4-, 7- and 10-byte NFC-A UIDs.
It can store several cards.

Emula is free and has no ads, paid features, purchases, subscriptions or donations.

Emula reproduces only the UID. The physical card retains its MIFARE memory, keys, counters and
cryptographic authentication. Use Emula with cards and readers
you have permission to test.

## Features

- Store multiple cards locally on the phone.
- Enter a 4-, 7- or 10-byte UID or read it from an NFC-A card.
- Select the UID length in the editor. Reading a card sets the length automatically.
- Keep the editor and its draft open when the screen rotates.
- Account for system bars, gesture navigation and display cutouts in either orientation.
- Edit or delete cards through the `⋮` menu.
- Search for a card by name or part of its UID in the main list or the tile chooser.
- Tap a card to activate it and tap it again to restore the normal NFC configuration.
- Switch cards through launcher shortcuts, pinned shortcuts, home screen widgets or the Quick Settings tile.
- Restore the default NFC configuration on every cold start.

Restarting the process, the phone or NFC ends the active selection.
Saved cards stay in the list until you choose one to activate again.

Long-press the Emula launcher icon to see shortcuts for the first four cards.
To pin an individual shortcut, open the card menu and choose Add home screen shortcut.
Add the Emula card widget from the launcher's widget list and select a card when placing it.
To add the tile, open the app menu and choose Add Quick Settings tile.
It toggles the last card you selected. The first time you use the tile,
it opens a card chooser. Hold the tile to choose a different card.
You can also open a card's menu and choose Use in Quick Settings.
This saves the selection without starting emulation.
Tap the magnifying glass between `+` and the menu to search. In the tile chooser, the search field
is below the title. A UID can include spaces, colons or hyphens.
[Search behavior and checks](docs/features/search/README.md).
A quick action toggles the card in the background. While a card is active, a notification
offers a stop action. Tap the shortcut, widget or tile again to stop the card.
Emula opens when it needs a permission, a card selection or approval for experimental mode.
Opening the app preserves the current background session.
[Quick actions](docs/features/quick-access/README.md) ·
[Background control](docs/features/background-control/README.md).

On the Moto G Stylus 5G (2024), `boston`, running Android 15 or later, Raw mode first tries
the experimental ST vendor method. The app selects Raw mode automatically when the regular
OEM API is absent. With the OEM API available, the ST path requires explicitly choosing Raw mode.
Emula saves the original configuration and checks it during restoration. Direct raw NCI
is a fallback after a failure before the first write or after a verified rollback.
External-reader emulation and normal NFC operation after stopping still need to be checked
on this model. [Scope and verification](docs/features/motorola-auto/raw-mode-15-17.txt).

In the editor, the `4 / 7 / 10 bytes` selector comes before the UID fields. The bytes
appear in numbered cells of equal size. Four bytes fit in one row, seven in rows of 4+3,
and ten in rows of 5+5. Each row is centered. Tapping a filled byte selects
the entire byte for replacement. Changing length retains the fields and input focus
so the keyboard stays in UID entry mode.
The byte fields request a Latin keyboard for plain text with suggestions disabled.
The editor is excluded from credential autofill.
Clearing a cell moves the active byte to the left, skipping empty cells.
The cursor goes to the end of the previous byte so that deletion can continue
one character at a time. Deleting in an empty cell also moves left.
The whole UID area uses one keyboard connection. Moving between bytes changes the
highlight and input position and preserves the handler for holding down the delete key.
The UID area always reserves two rows so the read button stays in place and the window
keeps the same height when the length changes. Four bytes are centered in that space.
Changing the length keeps all entered bytes in the draft, including temporarily hidden bytes.
The draft and selected length survive screen rotation. New cards default
to four bytes. Existing saved cards use the same storage format.

The [Android OEM API](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r4/framework/java/android/nfc/NfcOemExtension.java)
accepts 4-, 7- and 10-byte UIDs. The OEM and raw paths pass the full UID
and preserve the existing profile parameters. The physical-reader results
listed below cover four-byte UIDs. Seven- and ten-byte UIDs still need testing
on a specific phone with an external reader.

## CSV import and export

The app menu includes Import CSV and Export CSV. An export can be saved
to a file selected in the system picker or sent through Share. A CSV file can also
be opened in Emula or sent to the app through the system share sheet.

The file uses UTF-8, a `name,uid` header, a comma between fields and UIDs of 8, 14
or 20 hexadecimal characters with no separators.

~~~csv
name,uid
Office,01020304
"Gym, entrance",04A1B2C3D4E5F6
~~~

Before importing, the app validates the entire file and asks for confirmation.
If cards already exist, Add appends new UIDs and keeps existing cards,
and Replace all replaces the list with the file contents. If the list is empty,
confirm Import. Replacing the list first deactivates any active card.

[CSV format and behavior](docs/features/csv/README.md).

## Compatibility

The app uses the system OEM method
`NfcOemExtension.emulateNfcTechnologyATag()`. It appeared in Android 16 QPR2
(`android-16.0.0_r4`, 25Q4, API 36.1) and is also present in Android 17.

The OnePlus 15 CPH2747 on Android 16 has a separate path. The reviewed global firmware
provides the system library `nfcvendorlib` and a method called
`setHceTypeAConfig()` that performs the ST54L configuration inside the NFC service.
On this model the app prefers the vendor path for the matching firmware and uses CTS Type-4A
parameters `ATQA 06 0C` and `SAK 20` with the selected UID. Before activation the app saves
the system access-card state for restoration after deactivation, a failure or the next
cold start. The app manages the system RF-field guard during the session
so the configuration persists when an external reader first detects the phone.
An external reader confirmed activation of a four-byte UID through this path on the
OnePlus 15 CPH2747 running Android 16 / API 36. An experimental version of this vendor path
is also enabled for OnePlus CPH2707 on Android 16 / API 36 or later when the library,
method and system adapter are available. Activation and restoration on CPH2707 still
need a physical-device test. Other models skip this path.
The library is optional.

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

## Development

The integration branch is `main`. Start each feature, fix or documentation change
from current `main` in a separate `feature/<name>`,
`fix/<name>` or `docs/<name>` branch. Integrate through a merge request targeting `main`
(a pull request in Gitea), describing the result and checks performed.
Until the Gitea repository is available, keep working branches and commits locally.

Mark releases with `v<version>` tags on the corresponding commit in `main`.
For version 1.0.11, use `v1.0.11`. A separate branch for every version is unnecessary.
See the [agent guide](AGENTS.md).

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
2. Choose 4, 7 or 10 bytes and enter the UID in hex, or tap **Read from card**
   and hold the card against the back of the phone.
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
new NFC commands. **Prepare report** performs a separate
read-only check. When the check is available, **Copy** and **Email** are enabled when the
attempt finishes, including on failure. On the targeted Xiaomi firmware, the report also includes
the result from the NFC identification services.

Xiaomi 2312DRA50G / garnet_global running Android 15 or later has a separate NXP path.
It is selected when the standard OEM API is missing or when you choose Raw mode.
There is no additional consent dialog. Before a write, the app checks the services and base NFC configuration
of the installed firmware. It uses the saved 2.0.206 parameters only for an exact match
to that firmware. Stopping requests the firmware defaults and replaces
any previous user override of NFC or transit settings.
Reader behavior and payments after reset have yet to be confirmed on a device.
[Xiaomi trial details and verification](docs/features/xiaomi-garnet/release-trial.txt).

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
