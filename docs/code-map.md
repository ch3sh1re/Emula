# Emula code map

Start with the relevant symbols below, then follow their callers. Device captures
and internal release evidence are excluded from public source. See
[compatibility](compatibility.md) for hardware limits and
[CONTRIBUTING.md](../CONTRIBUTING.md) for checks.

## Phone UI and storage

- `MainActivity`: list/editor, search, NFC reading, permission setup and diagnostics.
  Start at `buildUi`, `renderCards`, `showCardEditor`, `setActiveCard` and
  `showNfcDiagnostics`. Text lives in `app/src/main/res/values/strings.xml`.
- `UidByteGrid`, `UidByteEditText`, `UidByteDeletion`: one input connection, logical
  selection and repeated backspace; 4/7/10-byte drafts survive length changes.
  `UidByteDeletionTest` owns the pure transitions.
- `AppDialogs`: shared confirmation/choice surfaces, scrolling and scalable actions.
- `SavedCard`, `UidCodec`, `Hex`, `CardCatalog`, `CardStore`, `CardSession`: named
  UIDs, parsing, JSON persistence and process-local active-card state. Their JVM
  tests cover pure rules; Android checks cover actual persistence.
- `CardSearch`: names and normalized UID fragments in the list and tile chooser.

## CSV and quick access

- `CardTransfer`: file-picker/share import and export, approval and lifecycle.
  `CardCsv`, `CardImport`, `CardReadCancellation` and their tests own bounded parsing,
  append/replace plans and cancellation. `CardExportProvider` exposes immutable
  snapshots through temporary URI grants; `CardTransferChecks` tests Android storage.
- `QuickAccess`, `CardActionActivity`, `CardQuickActions`, `QuickCardPolicy`,
  `CardWidgetProvider`, `CardWidgetConfigureActivity`, `CardTileService` and
  `CardTilePreferencesActivity`: shortcuts, widgets and tile targeting by current ID.
- `BackgroundNfcService`, `PhoneNfcRuntime`, `PhoneNfcSession`, `PhoneNfcBackend`:
  one serialized worker shared by UI/background activation and recovery.
  `PhoneNfcSessionTest` covers initialization, switching and restoration ordering.
- `EmulaApplication`, `NfcQuickAccessReceiver`: refresh quick-access surfaces and
  invalidate state after NFC resets without automatically reactivating a card.

## NFC controllers and evidence

- `NfcActivationPolicy`: OEM/catalog/experimental path selection.
- `OemNfcController`: preferred Android OEM UID API and polling/discovery controls.
  Capability checks inspect actual firmware methods. vivo V2536A raw operations
  use foreground discovery; background actions require opening the app.
- `OnePlusVendorNfcController`: scoped vendor HCE path with saved-state recovery.
- `MotorolaStNfcController`: separate opt-in experiment and Boston raw compatibility
  candidate. Preserve their distinct build flags and restoration behavior.
- `HonorStNfcController`: REA-NX9 ST candidate, service/chip checks, durable snapshot
  and readback. NXP identification excludes ST. `HonorStNfcControllerTest` owns policy.
- `XiaomiNfcController`, `XiaomiNxpConfig`: garnet/diting/tanzanite NXP trials.
  The parser preserves firmware parameters and rejects malformed/proprietary TLVs.
  Conditional CORE_CONF application uses an unused RF block after calibration;
  recovery persists before writes. Tanzanite requires a live seven-parameter snapshot
  and activation/restoration readback. Raw fallback is possible only before a vendor
  setter and without pending recovery. `XiaomiNfcControllerTest`, `XiaomiNxpConfigTest`
  and `TanzaniteNxpTrialTest` own these contracts; the public fixture is synthetic.
- `LegacyNfcController`: catalog-gated fallback and explicit experimental raw path,
  complete snapshots, per-SET evidence, polling resume and exact restoration.
  `RawNfcDeviceCatalog` owns device/firmware policy; preserve existing profiles.
- `RawNciCodec`: framing, TLVs and snapshot comparison. `RawOperationReport` and
  `OemOperationReport` keep request/status evidence and separate rollback errors.
- `LegacyNfcDiagnostics`: GET-only preparation. `collectConfiguration` falls back
  from batch to singles only after explicit controller rejection, stopping after
  transport failure/timeout. On scoped HONOR firmware, `observesFailedResponse`
  allows another second for late callbacks. `receiveGetConfig` preserves FAILED
  and never returns these responses as snapshot values. `LegacyNfcDiagnosticsTest`
  covers delayed responses and the firmware monitor conflict.

Never use raw SET as an inspection step. A build, GET or successful API call does
not prove reader acceptance or ordinary NFC after restoration.

## Local Bluetooth transfer

- `WatchCardSender`, `LocalWatchLink`, `WatchCardPacket`, `CardStore.appendReceived`:
  explicit phone-to-watch append over secure RFCOMM. Existing cards/active state
  remain unchanged; acknowledgement follows durable storage. `WatchCardPacketTest`
  and `CardTransferChecks.watchAppend` cover protocol and persistence.
- `WatchReportReceiver`, `WatchReportLink`, `WatchReportPacket`: separate watch-to-phone
  report protocol, private save, review/copy/share and reopening the last report.
  `WatchReportPacketTest` covers bounded UTF-8 frames and malformed input.
- Both use existing pairing, foreground screens, cancellation and a 90-second
  deadline. They do not activate NFC or send reports to an external service.
  See [card transfer](watch-transfer.md) and [diagnostics](watch-diagnostics.md).

## Wear OS and read-only probe

- `WatchActivity`: compact list/editor, tap/hold/swipe controls, setup/error UI,
  inline progress and local transfers. Sources are in `wear/src/main/java/dev/dunno/emula`.
- `WatchActivationPolicy`, `WatchReleasePolicy`: Android 15+ capabilities, OEM
  priority, firmware-scoped consent and UID validation. No model whitelist.
- `WatchNfcSession`, `WatchSessionStore`, `WatchHardwareBackend`: recovery before
  writes, retained restoration failures and firmware checks. Raw fallback requires
  a complete snapshot, UID readback and exact restoration. `WatchActivationPolicyTest`,
  `WatchNfcSessionTest` and `WatchUiErrorTest` cover policy, ordering and failures.
- `WatchActivity.prepareReport`: read-only probe separate from the last operation.
  `beginReportTransfer` prepares when needed; failed/unavailable probes stay shareable.
- `wear/build.gradle.kts`: explicit shared-source list and launcher resources.
  AndroidX SplashScreen shows the shared icon at 48 dp on black.
- `WatchDiagnosticsActivity`, `WatchNfcCapabilities` in `wear-diagnostics`: standalone
  read-only probe, with no activation controller compiled in. See the
  [watch guide](../wear/README.md) and [probe guide](../wear-diagnostics/README.md).

## Android verification and build

- `UidEditorTestInstrumentation`: input connections on unattached views plus
  isolated storage/provider, quick-access and background checks. `XiaomiNxpConfigChecks`
  tests the Android/ICU parser; JVM regex tests alone cannot establish Android behavior.
- Build `:app:assembleDebugAndroidTest`, install debug/test APKs and run
  `adb shell am instrument -w dev.dunno.emula.debug.test/dev.dunno.emula.UidEditorTestInstrumentation`.
- `WatchFlowInstrumentation`: CRUD, setup/failure UI and report preparation with
  unavailable NFC on a clean emulator; not a hardware activation test.
- Root/module Gradle files: SDK levels, package IDs, versions, JDK 17 and optional
  local release signing. Debug builds need no signing secrets.
- [README](../README.md), [changelog](../CHANGELOG.md) and [history](../HISTORY.md)
  describe the public snapshot. No GitHub Actions workflow is configured.
