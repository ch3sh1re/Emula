# Changelog

## Unreleased

## Wear OS 1.0.4 (10005)

- Offer an experimental raw NFC fallback on Android 15+ watches, including unknown
  models, when the OEM API is absent. Require exact-firmware approval and a complete
  saved baseline before writing; verify UID readback and exact restoration. Keep
  failed recovery retryable and allow disabling the experiment in Settings.
- Add Prepare report with a read-only NFC probe, capabilities and recovery details.
  Sending from an error prepares the report automatically. Preserve the original
  operation result separately from diagnostic results, including unavailable NFC.
- Include the shared correction explaining an unavailable firmware OEM API instead
  of incorrectly asking for a newer Android version. Raw reader behavior on the
  reported SM-L315F and SM-L320 firmware still requires tester confirmation.

## 1.0.21 (41)

- Observe delayed GET_CONFIG callbacks for one additional second after framework
  FAILED on HONOR REA-NX9 firmware 9.0.0.206C185E9R2P1. Preserve RX and controller
  status alongside the original framework failure in diagnostic reports.
- Keep these responses out of activation/restoration snapshots and stop further
  GETs after the failed request. The supplied firmware has a service-monitor
  conflict; delayed responses and UID emulation still require physical-device
  verification. No new NFC writer, permissions or data collection are added.

## 1.0.20 (40)

- Exclude the HONOR REA-NX9 ST candidate when the firmware identifies an NXP
  controller. Preserve pending restoration and existing OEM/raw safety checks.
- Stop repeated diagnostic GETs after framework FAILED without a controller
  response, including transport loss during individual reads. Keep fallback
  for explicit batch rejections and retain partial configuration evidence.
- Include the NFC service version, APK/library paths and read-only NXP
  API/firmware evidence in prepared HONOR reports. Bound vendor-probe reporting
  and prevent duplicate pending calls. HONOR UID emulation remains unverified.

## 1.0.19 (39)

- Add an experimental NXP transit path for Xiaomi 24117RN76E / tanzanite /
  tanzanite_eea on Android 15+. Complete the sparse firmware configuration only
  from a full live seven-parameter snapshot; persist it before writing, verify
  activation and exact restoration by readback, and retain failed recovery.
- Allow the existing consent-gated raw path if the tanzanite vendor path is
  unavailable before any write. Never switch writers after a vendor setter.
  Reader behavior on the reported firmware remains unverified.

## 1.0.18 (38)

- Use foreground Android discovery control for the experimental raw path on
  vivo V2536A / Android 15+ instead of the OEM polling API that returns 0.
  Background raw operations require opening Emula; firmware consent, configuration
  snapshots and exact restoration still apply. Reader behavior needs device testing.
- Try the HONOR ST vendor HCE API on REA-NX9 / HNREA with Android 15+ when the
  standard OEM API is absent. Require a live ST service and complete read-only
  configuration evidence; persist the original settings before writing, verify
  activation and restore the saved settings on stop or failure. Reader behavior
  on the reported firmware remains unverified.
- Include HONOR chip hints, API/Binder resolution failures and restoration state
  in prepared NFC reports. Replace the misleading Android-upgrade diagnosis with
  an explanation that the firmware lacks the Android tag-emulation API.

## Wear OS 1.0.3 (10004)

- Use the same Emula launcher artwork as the phone app (two overlapping cards).
- Show the launcher icon at 48×48 dp on a black splash screen during startup,
  using the standard Wear OS splash screen lifecycle (Google Play WO-V15).

## 1.0.17 (37)

- Apply Xiaomi NXP transit UID changes through an unused trailing RF block when
  the firmware skips unchanged CORE_CONF. Read the current main/RF files, preserve
  calibration blocks and refuse unsupported layouts before writing.
- Persist the firmware-default reset command before activation; apply it before
  removing the transit override on stop or failure. Recover markers from older
  builds and retain separate evidence for every vendor call and failed cleanup.
- Keep the existing CORE_CONF path when the firmware always applies it. Physical
  UID, reader acceptance and normal NFC after reset still need target verification.

## 1.0.16 (36)

- Receive NFC diagnostic reports explicitly offered on paired watches over direct
  encrypted Bluetooth. Save the last report privately before acknowledging receipt;
  offer review, copy, system sharing and reopening the last report.
- Keep card transfer independent, without cloud delivery or automatic external
  sending. Requires Wear 1.0.2 (10003) or newer for diagnostic transfer.
- Include the shared garnet/diting Android parser fix from 1.0.15 (35).

## Wear OS 1.0.2 (10003)

- Show activation/restoration failures in a persistent dialog with an option to
  send the cached diagnostic report to the paired phone over local Bluetooth.
- Place progress, missing-permission and NFC-off messages below the selected
  card, with actions for setup or NFC settings.
- Offer a compact one-time setup prompt when the ADB grant is missing. Setup
  shows instructions; it does not grant permission itself.
- Require phone 1.0.16 (36) or newer to receive reports. NFC activation paths,
  Android 15+ minimum and existing recovery rules remain unchanged. Physical
  phone/watch diagnostic transfer still needs device verification.

## 1.0.15 (35)

- Fix Xiaomi NFC configuration parser initialization on Android: escape the
  closing brace required by Android's ICU regex engine. The prior pattern passed
  JVM tests but caused PatternSyntaxException followed by NoClassDefFoundError
  on-device, blocking the shared garnet/diting path before configuration writes.
- Add Android runtime checks for class initialization and 4/7/10-byte config
  generation, preserved firmware parameters and malformed-input rejection.
  Existing chip, firmware, model and recovery checks remain unchanged.

## 1.0.14 (34)

- Allow Xiaomi 12T Pro 22081212UG / diting / ditingp_global on Android 15+ to
  try the existing Xiaomi/NXP configuration path when the standard OEM UID API
  is unavailable or raw mode is selected. Keep the chip/firmware checks, current
  firmware configuration validation, pending reset and failure recovery.
- Use a model-neutral Xiaomi NXP profile label in diagnostics. The original
  garnet scope and its exact-firmware fallback remain unchanged. Reader behavior
  and ordinary NFC after resetting defaults on diting need device verification.

## Wear OS 1.0.1 (10002)

- Allow OEM UID emulation attempts on Android 15+ watches without a model whitelist.
  NFC, permission, OEM API, UID validation and recovery checks still apply.
- Update compatibility guidance and diagnostics to describe capability-based access.
  External-reader evidence remains limited to SM-L355F with a four-byte UID.

## 1.0.13 (33)

- Send the saved phone catalog to a paired watch over secure local Bluetooth.
  The watch receives only while Receive from phone is open. Append by UID,
  retain existing watch names/IDs, acknowledge durable storage, and never activate
  NFC as a result of receiving cards. Retry, cancellation and timeout are bounded.
- Show card counts and short Add cards / Replace list explanations during CSV
  import, without a preview of card names.
- Use 48 dp card-menu buttons, an explicit Selected for Quick Settings state and
  a separate deletion group.
- Share the same dialog styling for deletion, errors and ADB instructions, with
  scrollable content and actions that accommodate large text.
- Increase the Name placeholder contrast.
- Present CSV save/share choices as equal flat rows with leading icons and touch
  feedback, removing the almost identical button/dialog fills and native shadows.
  Preserve accessible button semantics, 56 dp minimum targets, scalable text and
  the fixed Cancel action.

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
