# Emula code map

Use this map to choose a narrow reading set before opening implementation files.
Symbol names are intentionally used instead of line numbers so the map survives
ordinary edits.

## Runtime flow

```text
card tap / startup restore
  -> MainActivity.setActiveCard
     -> NfcActivationPolicy (pure path selection)
     -> OemNfcController (preferred API)
        -> OnePlusVendorNfcController (CPH2747 firmware-scoped vendor HCE)
     -> MainActivity.performLegacyCardChange
        -> LegacyNfcController (catalog-gated raw fallback)
           -> RawNciCodec + RawOperationReport

app menu -> MainActivity.showNfcDiagnostics
            -> LegacyNfcDiagnostics (read-only raw probe)
```

## 1. UI and card editor

Purpose: render cards, edit or scan a four-byte UID, start activation, and show
status/errors.

- Start in `MainActivity.java` at `buildUi`, `renderCards`, `createCardView`,
  `toggleCard`, `showCardEditor`, `saveEditor`, and `startReadingIntoEditor`.
- Edge-to-edge is automatic on supported Android versions (minSdk 35). `onCreate`
  sets the window background and system-bar icon appearance; `buildUi` applies system-bar
  and display-cutout insets to content and snackbars. Dialog bounds use current window metrics.
- NFC tag reading continues through `enableReaderModeForEditor`,
  `onTagDiscovered`, and `stopReaderMode`.
- `refreshPermissionUi` updates the ADB button during rendering and on resume, then
  checks once per second while the activity is resumed and permission is missing.
  `stopPermissionChecks` cancels callbacks on pause/destroy. A grant hides the button,
  dismisses the permission dialog, clears a stale permission status, and shows confirmation
  without changing NFC state. `showPermissionRequestIfNeeded` explains the one-time ADB
  grant with a package-specific command on opening without permission; the prompt is
  dismissible and its shown/open state survives activity recreation.
- Activation orchestration starts at `setActiveCard`; raw fallback continues at
  `performLegacyCardChange`; uncatalogued-device consent starts at
  `confirmExperimentalRawMode`; startup cleanup starts at
  `restoreDefaultsAtSessionStart`.
- UI text lives in `app/src/main/res/values/strings.xml`; permissions and the
  launcher activity live in `app/src/main/AndroidManifest.xml`.
- `showAppMenu` exposes raw mode, NFC diagnostics, and the privacy policy. External
  browser navigation is handled by `openExternalPage`; there is no donation or payment flow.
- Activation/interface failures offer support through `showNfcSupportDialog`, which
  opens diagnostics without starting a probe or sending a report.
- Direct dependencies: card-storage slice, OEM UID-emulation slice, raw
  UID-emulation slice.
- Validate UI changes with `assembleDebug`/`lintDebug` and a focused device check.

## 2. Card storage and session state

Purpose: persist named four-byte UIDs and keep one process-local active card.

- `SavedCard.kt`: immutable value object; copies UID input and enforces length.
- `UidCodec.kt`: `parse` plus Java-facing formatting helpers.
- `CardCatalog.kt`: pure `upsert` and `delete` list transformations.
- `CardStore.kt`: `getCards`, `save`, and `delete`; JSON is stored under
  SharedPreferences file `office_cards`, key `cards`.
- `CardSession.kt`: `beginProcess`, `getActiveCardId`, `isActive`,
  `setActiveCardId`, and `clearActiveCard`.
- Callers are primarily `MainActivity.renderCards`, `saveEditor`, `deleteCard`,
  `setActiveCard`, and `restoreDefaultsAtSessionStart`.
- Tests: `SavedCardTest.kt`, `UidCodecTest.kt`, `CardCatalogTest.kt`, and
  `CardSessionTest.kt`. Android persistence behavior is not covered by JVM tests.

## 3. OEM UID emulation

Purpose: use Android/OEM NFC-A tag emulation when the extension API exists.

- Enter through `OemNfcController.isUidEmulationSupported`, `setCardEnabled`, or
  `restoreDefaults`; capability and last-operation evidence is exposed by
  `inspectCapabilities`, `lastOperationForDisplay`, and `lastOperationDetailed`.
- `resolveAndroidApi` reflects `getNfcOemExtension` and
  `emulateNfcTechnologyATag`; `resolveProfile` chooses Pixel-minimal or CTS Type 4A
  arguments.
- `OnePlusVendorNfcController` is restricted to OnePlus CPH2747 on API 36+ and
  reflects the optional firmware library `nfcvendorlib`. It invokes
  `setHceTypeAConfig`, neutralizes the firmware RF-field guard, and persists the
  pre-existing OnePlus access-card state until deactivation or startup restore.
- `OemOperationReport` records vendor-library/service/method resolution, ATQA/SAK/UID,
  guard transitions, snapshot state, failures, and rollback outcome for the NFC
  Diagnostics menu.
- `MainActivity.setActiveCard` chooses this path first unless raw mode is forced.
- Failures may fall through to `MainActivity.performLegacyCardChange` only when
  the device catalog permits raw mode.
- Contract: four-byte UID, enabled NFC adapter, integer status `0` means success.
  Keep profile changes independent from raw-NCI candidate selection.
- Reflection and Settings writes require a matching physical-device check. Pure
  OnePlus device scoping, stored-value parsing, and report formatting are covered by
  `OnePlusVendorNfcControllerTest` and `OemOperationReportTest`.

## 4. Raw UID emulation

Purpose: provide the permission-gated, device-catalogued raw NCI fallback.

- Policy starts in `RawNfcDeviceCatalog.isSupported`, `profileFor`,
  `prefersUidOnly`, and `shouldUseDiscoveryTechnologyFallback`.
- `NfcActivationPolicy.choose` keeps OEM, catalog raw, fingerprint-approved
  experimental raw, confirmation, and unsupported outcomes mutually exclusive.
- Operation entry points are `LegacyNfcController.isExperimentalAvailable`,
  `setCardEnabled`, and `attachRollback`.
- Candidate flow: `activateUidOnlyThenLegacy` -> `activateUidOnly`, with
  `activateLegacy` retained as fallback; deactivation uses
  `restoreConfiguration`.
- Polling safety is centralized in `executeWithPollingPaused` and
  `PollingControl`. Transport/session work is in `RawNciSession`.
- Payload construction and response parsing live in `RawNciCodec`; evidence and
  user/debug formatting live in `RawOperationReport`.
- An uncatalogued opt-in must carry a complete original-configuration snapshot;
  it is encoded by `RawNciCodec` and retained only until exact restoration.
- UI ownership: `MainActivity.performLegacyCardChange`,
  `restoreDefaultsAtSessionStart`, `isRawModeForced`, and `setRawModeForced`.
- Tests: `LegacyNfcControllerTest.kt` owns device/profile policy;
  `RawNciCodecTest.kt` owns payload/snapshot parsing; and
  `NfcActivationPolicyTest.kt` owns routing compatibility.
- Hardware check: distinguish framework transport status, controller status,
  readback after SET/resume, rollback result, and external-reader activation.

## 5. Raw NFC diagnostics

Purpose: inspect raw-NCI availability and configuration without changing it.

- UI starts at `MainActivity.showNfcDiagnostics`; summaries are built by
  `diagnosticSummary`, `diagnosticDetailedSummary`, and `copyDiagnostics`. Both the
  latest OEM operation and the latest raw operation are included. The dialog uses a
  dedicated report `ScrollView` and a fixed responsive action footer instead of the
  platform three-button `AlertDialog` bar. Copy and Email are disabled while a probe
  is pending so they cannot export a stale report.
- `confirmEmailDiagnostics` explains possible UID contents; `openDiagnosticsEmail`
  opens an editable external email draft or offers a copy fallback. No report is sent
  automatically. Reports include the app version and firmware details.
- Probe entry points are `LegacyNfcDiagnostics.isRawNciApiAvailable` and
  `runReadOnlyProbe`.
- `DiagnosticSession.getConfig` sends GET_CONFIG requests; `formatProbe` renders
  parsed values and missing parameters.
- `RawNciCodec.parseGetConfigResponse` and status/parameter naming are shared
  with write diagnostics; `RawOperationReport` preserves per-request evidence.
- Contract: the probe reads parameters `0x30`, `0x31`, `0x32`, `0x33`, `0x58`,
  `0x59`, and `0x85`. It must not send SET_CONFIG or mutate NFC state.
- Parsing tests are in `RawNciCodecTest.kt`; the reflection/transport path needs
  a physical-device smoke test with `WRITE_SECURE_SETTINGS` granted.

## Cross-cutting build and release files

- `app/build.gradle.kts`: SDK levels, application IDs, version, JDK/Kotlin 17,
  signing, and dependencies.
- `README.md`, `CHANGELOG.md`, `TODO.md`: current behavior and project status.
- `distribution/google-play/`: listing, screenshots, release notes, checksums,
  and signed artifacts. Do not rewrite published release artifacts as part of an
  implementation change.
