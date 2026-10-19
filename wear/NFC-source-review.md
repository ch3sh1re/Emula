# Android NFC on Wear OS, source review of 2026-10-16

The review covered AOSP `platform/packages/modules/Nfc` on Android 16 QPR2
(`android-16.0.0_r4`) and the `main` branch at the time of the review. Findings from AOSP
still require device verification on the Galaxy Watch9 firmware.

## NFC reading and card emulation

`NfcAdapter.enableReaderMode` checks `sHasNfcFeature`. When the feature is absent,
it throws `UnsupportedOperationException`. Some other operations accept
either NFC or card-emulation features. Watches that offer an adapter, HCE or contactless
payments may therefore still lack support for reading the UID of a physical card.
[NfcAdapter, main](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/framework/java/android/nfc/NfcAdapter.java).

The app uses standard reader mode with NFC-A and `FLAG_READER_SKIP_NDEF_CHECK`.
If the request fails, Emula keeps the original error and respects the firmware's reader
capabilities. Test version 0.3 added manual UID entry.

## OEM API for setting a UID

The release version of `NfcOemExtension` includes `emulateNfcTechnologyATag`, gated by
`WRITE_SECURE_SETTINGS` and `FLAG_OEM_EXTENSION_25Q4`. Its contract accepts UIDs of
4, 7 or 10 bytes. This method was absent in the reviewed `main`, so Emula checks
for the method on the installed firmware regardless of the SDK or Wear OS version.
[NfcOemExtension, android-16.0.0_r4](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r4/framework/java/android/nfc/NfcOemExtension.java).

`NfcService.emulateNfcATag` checks the administrative permission, stops
discovery, sets `0x85`, `0x30`, `0x31`, `0x32`, `0x33`, `0x58` and optionally `0x59`,
then applies routing. Disabling removes the custom configuration and clears some
values. This implementation does not pass individual `setNciConfig` results
back to the calling app. The service returns OK at the end, so that status alone
does not establish whether the controller applied the configuration. The watch reads back the UID
when raw GET is available. A mismatch triggers rollback. When readback is unavailable,
the result requires confirmation with an external reader.
[NfcService, android-16.0.0_r4](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r4/NfcNci/src/com/android/nfc/NfcService.java).

## Raw NCI and discovery control

`NfcService.sendVendorNciMessage` calls `NfcPermissions.enforceAdminPermissions`,
checks that NFC is on and forwards the request through `DeviceHost.sendRawVendorCmd`.
The administrative permission is `WRITE_SECURE_SETTINGS`, which explains the ADB grant.
The controller can still reject a SET even when the method and permission are available.
[NfcService, main](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/NfcNci/src/com/android/nfc/NfcService.java),
[NfcPermissions, main](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/NfcNci/src/com/android/nfc/NfcPermissions.java).

`setDiscoveryTechnology` accepts NFC or CE features and is incompatible with reader mode.
The watch app stops reading before activation. For an approved raw session on a watch,
it prefers this API. Other devices follow the phone's existing policy.
The shared controller resumes discovery after the operation. On the watch it saves
an exact snapshot of the six raw parameters it changes. The snapshot does not cover
every secure-element or proprietary routing state.
[NfcAdapter, main](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/framework/java/android/nfc/NfcAdapter.java).

## Galaxy Watch9 observations

A report came from a Samsung SM-L355F (`fresh9ul`, `qcom`) running Android 17 / API 37,
fingerprint `samsung/fresh9ulxx/fresh9ul:17/CP2A.260330.023/L355FXXS1AZH4:user/release-keys`,
security patch 2026-08-05. FEATURE_NFC=false, HCE/HCEF/eSE=true; `enableReaderMode`
threw `UnsupportedOperationException` at `NfcAdapter.java:1834`. The watch had
the WRITE_SECURE_SETTINGS grant. The OEM UID and raw NCI APIs resolved, and
discovery control was available. The report recorded no GET, SET or reader-confirmed
activation. Version 0.3 added manual UID entry for an emulation test.

On 2026-10-17 the user confirmed that an external reader detected
a four-byte UID using the standard OEM path. This confirmation applies to the reported
SM-L355F. No additional firmware or operation report accompanied it.
Screen-off use was not tested. Seven- and ten-byte UIDs, raw writes,
NFC restoration and other watch models still need verification.

Public source for the NFC service on that particular Samsung firmware was unavailable
for this review. The `collect-watch-nfc.py` script records
this information and, with an explicit flag, downloads available NFC APKs and framework JARs
for later analysis. It sends no NCI commands and leaves the settings in place.

Samsung provides instructions for connecting to the watch through ADB in
its [official guide](https://developer.samsung.com/health/sensor/guide/connect-watch.html).
