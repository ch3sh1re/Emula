# Changelog

## 1.0.9 (28)

- Add startup instructions for granting the one-time ADB permission.
- Detect permission grants automatically, show confirmation, and hide the ADB command
  button without requiring an app restart.

## 1.0.8 (27)

- Remove the donation menu item, confirmation dialog, and PayPal link.
- Keep Emula free of ads, paid features, purchases, subscriptions, and donations.
- Update the store description and privacy policy to reflect the removal of donations.

## 1.0.7 (26)

- Use consistent activate/deactivate wording and an Active badge for card status.
- Align UID-reading instructions, UID-length labels, and NFC error messages.

## 1.0.6 (25)

- Replace deprecated system-bar color calls with a window background and explicit light
  system-bar icons. Keep content and snackbars clear of system bars and display cutouts
  using the current window-insets API on Android 15 and later.

## 1.0.5 (24)

- Offer NFC diagnostics and the support email after failed activation or an unavailable
  emulation interface.
- Add an optional email draft for diagnostics, with a UID disclosure and a copy fallback
  when no email app is installed. Include the app version and selected mode in reports.
- Document compatibility support in the store listing and voluntary diagnostic sharing in
  the privacy policy.

## 1.0.4 (23)

- Added a firmware-scoped OnePlus CPH2747 path through `nfcvendorlib` and
  `setHceTypeAConfig`, including RF-guard neutralization and exact restoration of the
  pre-existing OnePlus access-card state.
- Added copyable OEM diagnostics for vendor library/service resolution, ATQA/SAK/UID,
  guard transitions, pending snapshots, failures, and rollback results.
- Confirmed four-byte UID activation with an external reader on OnePlus 15 CPH2747
  running Android 16 / API 36.
- Reworked the NFC Diagnostics dialog so only the report scrolls while Check, Copy, and
  Close remain in a fixed responsive action area.

## 1.0.3 (22)

- Added a minimal UID-only raw NCI candidate for OnePlus 15 CPH2747 before the existing
  CTS Type-4A fallback.
- Added read-only raw NCI diagnostics with copyable parameter values and request statuses.
- Added UID readback after polling resumes, a single reapply attempt, and structured rollback
  evidence for failed raw activations.
- Resolve UID-emulation compatibility only after a card tap and show prominent, actionable
  activation/deactivation errors.
- Added an explicit raw opt-in for uncatalogued devices when the raw API exists. The app probes
  first, snapshots the original controller values, and restores them after a failed attempt.
- Remember successful experimental consent only for the current firmware fingerprint.
- Preserved the existing Pixel, Samsung, Sony, and generic OnePlus activation profiles.

## 1.0.2 (21)

- Added experimental raw NCI support for Sony Xperia 1 VI XQ-EC72 on Android 16.
- Use the Android CTS Type-4A profile on the supported Xperia model.
- Added a model-specific NFC discovery pause fallback without changing the existing Google,
  Samsung, or OnePlus paths.
- Centralized raw-mode device compatibility and profile selection in a dedicated catalog.

## 1.0.1 (20)

- Added experimental raw NCI support for OnePlus devices.
- Added a OnePlus-specific NFC discovery pause fallback on Android 15 and newer.
- Use the Android CTS Type-4A profile on OnePlus while preserving the existing Pixel and
  Samsung profiles.
- Verify that the controller retained the requested UID before reporting activation success.

## 1.0.0 (19)

- Improved raw NFC compatibility on Samsung devices running Android 15 / One UI 7.
- Raised the minimum supported version to Android 15.
- Added rejected NFC controller parameter IDs to raw-mode errors.
