# Changelog

## Unreleased

## 1.0.13 (33)

- Present CSV save/share choices as equal flat rows with leading icons and touch
  feedback, removing the almost identical button/dialog fills and native shadows.
  Preserve accessible button semantics, 56 dp minimum targets, scalable text and
  the fixed Cancel action.
- Include the NFC compatibility, foreground polling, recovery and diagnostics
  changes from the 1.0.12 preparation. Existing 1.0.12 artifacts are retained.

## 1.0.12 (32)

- Transparently select a Xiaomi NXP compatibility path for Redmi 2312DRA50G /
  garnet_global on Android 15+, including Android 16 and newer, when the standard
  OEM API is absent or raw mode is selected. Validate live chip/firmware services
  and the current firmware CORE_CONF before writing; preserve unrelated parameters.
  A stored baseline is used only for the exact previously analyzed Global 206 build.
- Retain recovery before changing the transit configuration, request firmware
  defaults on stop/failure/startup recovery, and keep failed resets pending.
  Bound vendor-call waits and prevent overlapping calls after timeout or process
  death. Reader activation and payment recovery on this model remain unverified.
- Include Xiaomi vendor identity, configuration requests, returned status and
  separate rollback evidence in diagnostic reports.
- Rename the NFC diagnostic check to Prepare report and explain its purpose.
  When controller details can be collected, enable Copy and Email after the
  collection attempt completes, including failed checks in the exported report.

## 1.0.11 (31)

- Align the add/search/menu toolbar buttons and match the Name field styling and
  label spacing to the UID controls in the card editor.
- Experimentally route OnePlus CPH2707 on Android 16+ through the existing
  setHceTypeAConfig vendor path, including its saved-state restoration. Hardware
  activation and restoration on this model remain unverified.
- Search saved cards by name or UID in the catalog and Quick Settings chooser;
  select a tile target directly from each card's menu without starting emulation.
- Match the CSV export action dialog to the tile chooser and use emula.csv for saved
  and shared files, keeping independent immutable snapshots and existing share URIs.
- Match the phone tile card chooser to the card list and editor, with a selected
  target indicator, separate name/UID text and a bounded scrolling list.
- Bind the phone NFC tile when Quick Settings opens and retain listening until it
  closes, instead of reconnecting the active-mode tile around clicks and updates.
- Refresh an already-listening Quick Settings tile immediately after NFC changes,
  and remove opaque window/task animations from the shortcut routing Activity.
- Show foreground NFC notifications immediately, replace stale result toasts and
  add result haptics that respect silent mode, DND and touch feedback settings.
- Keep the Motorola ST experiment disabled in ordinary phone builds pending reliable
  controller identification and reader verification. Preserve restoration of snapshots
  from earlier test builds even when new ST activations are disabled.
- Run phone shortcuts, widgets and Quick Settings toggles in a foreground service
  without opening the card list; offer deactivation in its notification. Share one
  process-wide queue with the UI, diagnostics and catalog changes, retain pending
  restoration after failure, and keep active sessions when the UI is recreated.
- Add dynamic and pinned card shortcuts, configurable home screen card widgets,
  and a Quick Settings tile using the existing card activation flow.
- Add UTF-8 CSV import/export for named 4/7/10-byte UIDs through Android document
  pickers and file sharing, with explicit import approval and append/replace modes.
- Preserve existing cards when appending and deactivate an active card before
  replacing the catalog. Validate the whole file before saving.
- Cancel CSV reads on dismissal, replacement or Activity destruction; close input
  off the UI thread and notify the document provider while opening a file.
- Preserve SAK/ATQA research and primary-source artifacts in docs/features/sak-atqa.

## 1.0.10 (29)

- Add 7- and 10-byte UIDs alongside existing 4-byte cards, with a length selector and
  automatic length detection when reading a card, explained below the read button.
- Show centered, numbered byte cells with whole-byte selection and a visible cursor.
- Keep the keyboard and editor layout stable, suppress credential autofill suggestions,
  and continue held backspace across bytes while moving the active cell left.
- Preserve drafts across length changes and rotation, and keep existing four-byte cards
  compatible with the unchanged storage format.
- Use consistent punctuation in explanatory text and instructions.

Long UID activation depends on the phone, firmware, and reader; external-reader
confirmation for 7- and 10-byte UIDs is still pending.

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
