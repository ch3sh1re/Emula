# Emula for Wear OS

Emula stores NFC-A card UIDs on the watch and can emulate them on compatible watches running Android 15 (API 35) or later. The watch app runs on its own. A paired phone can send cards and receive diagnostics. [Compatibility](../docs/compatibility.md)

## Setup

Install the Wear OS build on the watch and enable NFC. Open **Settings → Setup** in Emula. For wireless ADB, enable Developer options on the watch first. On a Pixel Watch, open **Settings → System → About → Versions** and tap **Build number** seven times. In Developer options, enable wireless debugging and pair your computer with the watch.

With ADB connected to the watch, grant the release app its one-time permission.

```sh
adb shell pm grant dev.dunno.emula android.permission.WRITE_SECURE_SETTINGS
```

The debug build uses the separate package `dev.dunno.emula.watchtest`. Grant that package the permission when testing a debug build. Each installation keeps its own cards. Stop an active card before switching between installations.

## Using cards

- Tap **+** to add a card. Enter a 4-, 7- or 10-byte UID. Card scanning also requires the watch's NFC reader capability.
- Tap the **circular arrows** to receive cards. On the paired phone, choose **Send all cards to watch** and select the watch. Keep both apps open and allow Nearby devices. Phone 1.0.40 can also send card colors. [Card transfer](../docs/watch-transfer.md)
- Tap a card to activate it. An outline marks the active card. Tap it again to stop and restore NFC. Keep the screen awake when checking the UID with a reader.
- Hold a card to edit it. To delete, swipe the card left until you feel a vibration, then release and confirm. A shorter swipe returns the card to its original position.
- Open the **gear** for Setup and Diagnostics. If activation fails, prepare a report on the watch and send it to the paired phone for review. [Watch diagnostics](../docs/watch-diagnostics.md)

## Build

Use JDK 17 and Android SDK Platform 36.

```sh
./gradlew :wear:assembleDebug :wear:testDebugUnitTest :wear:lintDebug
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
adb shell pm grant dev.dunno.emula.watchtest android.permission.WRITE_SECURE_SETTINGS
```
