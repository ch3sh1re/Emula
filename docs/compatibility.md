# Compatibility and evidence

Emula requires Android 15 / API 35 or newer. UID emulation also depends on NFC
firmware, controller behavior, and the external reader.

## Activation paths

The preferred Android OEM API is
`NfcOemExtension.emulateNfcTechnologyATag()`, present in Android 16 QPR2
(`android-16.0.0_r4`, API 36.1) and Android 17. Manufacturers can backport or omit
interfaces, so Emula checks actual methods instead of relying only on `SDK_INT`.
Original Android 16 and QPR1 need a backport for this path.

[AOSP API source](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r4/framework/java/android/nfc/NfcOemExtension.java)

Raw NCI is a fallback or explicit override. Catalogued Google, Samsung, OnePlus,
and Sony XQ-EC72 devices follow their profiles. An uncatalogued write requires
firmware-scoped opt-in and a complete original snapshot. Read-only diagnostics
can be available even when activation is unsupported.

## Recorded physical-device results

These observations apply to the stated devices and firmware. Four-byte results
do not establish 7- or 10-byte activation support.

| Device | Recorded firmware | Result |
| --- | --- | --- |
| Pixel 8 Pro | Android 17 / API 37, `CP2A.260805.005` | OEM activation confirmed with an external reader, Pixel profile. |
| Samsung Galaxy A16 | Android 16 QPR2 / One UI 8.5 | OEM activation confirmed, CTS Type-4A profile. |
| OnePlus 15 CPH2747 | Android 16 / API 36 | Vendor `setHceTypeAConfig` activation confirmed with an external reader. |
| Galaxy Watch9 SM-L355F | Android 17 / API 37, reported `L355FXXS1AZH4` | User confirmed a four-byte UID with an external reader on October 17, 2026, OEM path. |

Additional working-phone reports include Pixel 9, Pixel 10, Galaxy S23 Ultra,
Galaxy S25 Ultra, and Sony Xperia 1 VI XQ-EC72. Complete firmware/reader details
are not recorded here; support across each model's firmware variants is not implied.

## Experimental and unverified cases

- OnePlus CPH2707 on Android 16+ attempts the existing vendor path. Physical
  activation and restoration remain unverified.
- Xiaomi 2312DRA50G / garnet_global on Android 15+ has a scoped NXP path. Reader
  activation and payment/transit behavior after restoration remain unverified.
  Stop requests firmware defaults; a previous custom NFC/transit override is not
  preserved by this path.
- Motorola Moto G Stylus 5G (2024), `boston`, Android 15+ has an ST-first raw
  compatibility attempt. Reader activation and ordinary NFC after stop/reboot
  remain unverified.
- A Nothing A063 report includes successful activation and matching controller
  readback on Emula 1.0.12. Explicit reader evidence and deactivation/recovery
  confirmation are missing. No new catalogued profile is implied.
- Other watches, screen-off watch use, and 7-/10-byte activation require separate
  verification. Watch activation is currently gated to SM-L355F.

## Reporting results

Include app version, exact model/firmware, OEM/vendor/raw path, controller result,
and external-reader behavior. Check ordinary NFC after deactivation. Use GET-only
diagnostics for inspection; do not issue raw SET commands merely to inspect a device.

Review reports and redact card UIDs, serial numbers, and personal information.
An API returning OK or a successful GET alone does not confirm reader activation.
