# Android NFC on Wear OS

Some watches advertise card emulation and secure-element features without advertising NFC reader mode. Emula checks the adapter and the available APIs when it selects a path. The manual UID editor also works on watches that cannot scan a physical card. [Android NfcAdapter](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/framework/java/android/nfc/NfcAdapter.java)

The Android OEM interface accepts NFC-A parameters and a UID. Emula uses a separate profile for Google watches and records controller readback when available. Testing with an independent reader establishes which UID the watch presents. [Android OEM extension](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r4/framework/java/android/nfc/NfcOemExtension.java) · [Device observations](../docs/compatibility.md)

The system service checks permissions on the raw interface. On watches without the OEM interface, the experimental path also requires the user's firmware-specific approval, control of foreground discovery and a complete configuration snapshot for restoration. [Android NFC service](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/NfcNci/src/com/android/nfc/NfcService.java)
