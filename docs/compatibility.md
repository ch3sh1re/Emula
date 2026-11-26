# NFC compatibility

Emula runs on Android 15 (API 35) and later. The NFC service, controller and reader all affect whether the reader sees the selected UID. When an OEM interface is available, Emula uses it first. On supported devices it can also use Raw mode. An experimental Raw session on an uncatalogued device requires your approval for that firmware and a saved configuration for recovery.

## Reader observations

| Device | Observation |
| --- | --- |
| Google Pixel 8 Pro | An external reader saw the selected UID on Android 17. |
| Samsung Galaxy A16 | OEM activation was confirmed on Android 16 QPR2 / One UI 8.5. |
| OnePlus 15 CPH2747 | A reader confirmed the vendor path on the tested Android 16 build. Other OnePlus models and firmware need their own checks. |
| Google Pixel Watch 3 | A Pixel 8 Pro read the selected four-byte UID with the Pixel Watch profile and saw the usual changing UID again after Stop. |
| Samsung Galaxy Watch9 SM-L355F | A user confirmed a four-byte UID with a reader on Android 17 using the OEM path. |
| Samsung Galaxy Watch SM-L705F | A reader accepted a four-byte UID in Raw mode on Android 16. The saved NFC settings and normal operation were restored after Stop. [Report #3](https://github.com/ch3sh1re/Emula/issues/3) |

The watch app selects a dedicated NFC-A profile on Google watches. Pixel Watch 5 reader behavior is still being checked. [Report #15](https://github.com/ch3sh1re/Emula/issues/15)

## Checking your device

Activate a saved card, keep Emula open and check the UID with an independent reader. Tap the active card again to stop, then check ordinary NFC operation. Emula can store 4-, 7- and 10-byte UIDs. A result for one UID length applies to that length on the tested firmware.

If activation or restoration fails, prepare a diagnostic in Emula. Include the app version, device, OS, reader model and what the reader observed. Review the report for card UIDs and personal information before sharing it.

On the Xiaomi garnet and diting experimental vendor paths, Stop requests firmware defaults. If you rely on custom NFC or transit settings, take this into account before enabling the trial.
