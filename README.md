# Emula

Read, save, and emulate NFC-A card UIDs on compatible Android devices, without root.

Emula is free, with no ads, accounts, analytics, subscriptions, or cloud storage.
Cards stay on the device. It reproduces the UID only: card memory, cryptographic
keys, authentication, and protected application data are not copied.

[Google Play](https://play.google.com/store/apps/details?id=dev.dunno.emula) ·
[Compatibility](docs/compatibility.md) · [Wear OS](wear/README.md) ·
[Changelog](CHANGELOG.md) · [GPLv3](LICENSE)

## Background

Emula began with wanting to use an Android phone instead of carrying a physical
NFC access card, without rooting the phone. The
[original announcement on r/NFC](https://www.reddit.com/r/NFC/comments/1wvof7a/i_built_emula_nfc_uid_emulation_app_on_android_15/)
describes how the project started and includes early compatibility reports and
feedback.

## Features

- Send saved cards to a paired watch over direct encrypted Bluetooth. Open
  Receive from phone on the watch, then Send all cards to watch on the phone.
  Existing watch cards and names are kept; matching UIDs are skipped. This is a
  manual transfer without a cloud relay. Both apps need Nearby devices access.
  See [transfer behavior and verification limits](docs/watch-transfer.md).
- Read an NFC-A card or enter a 4-, 7-, or 10-byte UID manually.
- Save, name, edit, and search multiple cards locally.
- Tap a card to activate it; tap again to restore ordinary NFC operation.
- Use launcher shortcuts, home screen widgets, and a Quick Settings tile on phones.
- Import and export named UIDs as CSV through Android's file picker or sharing.
- Collect NFC diagnostics for compatibility troubleshooting.
- Use the standalone Wear OS app on the currently verified watch model.

Long UIDs can be stored and passed to the controller, but external-reader
confirmation currently covers four-byte UIDs. Support depends on the device,
firmware, and reader; an Android version or successful API call alone is not proof.

## Requirements and setup

- Android 15 / API 35 or newer.
- Compatible NFC hardware and firmware; see the [device notes](docs/compatibility.md).
- A one-time `WRITE_SECURE_SETTINGS` permission grant through ADB.

Install Emula, enable USB or wireless debugging, connect ADB, and run:

```sh
adb shell pm grant dev.dunno.emula android.permission.WRITE_SECURE_SETTINGS
```

The app detects the permission without a restart. For the debug phone build, use
`dev.dunno.emula.debug` instead. Wear OS installation and package names are described
in the [watch guide](wear/README.md).

Add or scan a card, save it, and tap it to activate. Tap the active card again to
stop. Switching cards replaces the active UID. Emula does not automatically
reactivate a card after process restart, reboot, or NFC being switched off.

Use Emula with cards and readers you own or are authorized to test. A system that
checks card authentication or data beyond the UID may not accept UID emulation.

## Build from source

Use JDK 17 and Android SDK Platform 36. Install the SDK packages requested by Gradle
if they are missing. Open the project in Android Studio, or set `ANDROID_HOME` to
your SDK directory and run the checked-in Gradle wrapper:

```sh
./gradlew --no-daemon --max-workers=2 :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.dunno.emula.debug android.permission.WRITE_SECURE_SETTINGS
```

Debug builds need no private signing files. Additional modules:

```sh
./gradlew --no-daemon --max-workers=2 :wear:assembleDebug :wear-diagnostics:assembleDebug
```

The watch diagnostics app performs read-only checks and is separate from the
watch emulator. See [wear-diagnostics/README.md](wear-diagnostics/README.md).

To build your own signed release, copy `keystore.properties.example` to
`keystore.properties` and point it to your own keystore. The original signing key
and passwords are not part of this repository.

```sh
./gradlew :app:assembleRelease :app:bundleRelease
```

## Verification and contributions

```sh
./gradlew --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :wear:testDebugUnitTest :wear:lintDebug :wear:assembleDebug \
  :wear-diagnostics:lintDebug :wear-diagnostics:assembleDebug
```

These checks do not prove hardware compatibility. NFC changes also require a
scoped physical-device check and external-reader evidence. See
[CONTRIBUTING.md](CONTRIBUTING.md) and the [code map](docs/code-map.md).

Report a problem through the repository's issue tracker or the app's NFC
diagnostics email action. Include the app version, model, firmware, activation
path, and reader behavior. Review reports before posting: they can contain card
UIDs and other device information. Nothing is sent automatically.

## Repository history

This public copy retains the dates and ordering of the private development
history while removing private reports, build output, signing artifacts, and
internal task material. Commit IDs changed during that cleanup. The first retained
snapshot is Emula 1.0.9, dated October 5, 2026. See [HISTORY.md](HISTORY.md).

## License

Copyright (C) 2026 dunno.

Emula's original code is licensed under the GNU General Public License, version 3
only (`GPL-3.0-only`). See [LICENSE](LICENSE). Third-party components retain their
own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The application privacy policy is available
[here](https://sites.google.com/view/emula-privacy-policy).
