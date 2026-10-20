# Code map

All classes below use package `dev.dunno.emula`. Start with the symbols involved
in a change, then follow their callers rather than reading an entire controller.

## Phone UI and data

- `app/src/main/java/dev/dunno/emula/MainActivity.java`: list/search, editor,
  reader mode, permission setup, diagnostics, and interactive activation fallback.
- `UidByteGrid`, `UidByteEditText`, `UidByteDeletion`: one UID input connection,
  byte drawing, cursor/selection handling, and pure deletion transitions.
- `SavedCard`, `UidCodec`, `Hex`, `CardCatalog`, `CardStore`, `CardSession`:
  immutable cards, 4/7/10-byte UIDs, persistence, and active-card state.
- `CardCsv`, `CardImport`, `CardTransfer`, `CardReadCancellation`,
  `CardExportProvider`: bounded parsing, append/replace, file transfer, cancellation,
  and immutable share snapshots. `AppDialogs` owns common phone dialogs.
- `WatchCardSender` owns explicit send-all, the CONNECT permission and paired-device
  chooser. `LocalWatchLink` uses cancellable secure RFCOMM with a 90-second deadline.
  `WatchCardPacket` validates bounded batches and receipts, and plans append by UID.
  `CardStore.appendReceived` saves atomically without changing existing cards or NFC
  state. JVM codec/merge tests and Android isolated-storage checks own these rules.

Pure behavior lives in the corresponding `app/src/test/` classes. Android checks
in `app/src/androidTest/` cover input connections, isolated storage, file sharing,
and quick-access/session lifecycle without hardware NFC writes.

## Phone activation

```text
card tap / shortcut / widget / Quick Settings tile
  -> MainActivity / CardActionActivity / CardTileService
  -> BackgroundNfcService + PhoneNfcRuntime
  -> PhoneNfcSession + PhoneNfcBackend
  -> model-scoped vendor / OEM / catalogued or consented raw path
```

- `PhoneNfcRuntime` owns the application-wide queue; `PhoneNfcSession` resolves
  current records and initialization/toggles. `PhoneNfcBackend` selects controllers
  and persists recovery state. `NfcUiContext` carries the foreground Activity.
- `NfcActivationPolicy` separates OEM, catalogued raw, firmware-approved
  experimental raw, consent, and unsupported outcomes.
- `QuickAccess`, `QuickCardPolicy`, `CardQuickActions`, `CardWidgetProvider`,
  `CardWidgetConfigureActivity`, `CardTileService`, `CardTilePreferencesActivity`:
  authenticated actions, record resolution, widgets, shortcuts, and tile targets.
- `NfcQuickAccessReceiver` queues adapter-state invalidation without writes.

`PhoneNfcSessionTest`, `NfcUiContextTest`, `NfcActivationPolicyTest`, and
`QuickCardPolicyTest` cover the corresponding pure contracts.

## Controllers and diagnostics

- `OemNfcController`: reflect `getNfcOemExtension` /
  `emulateNfcTechnologyATag` and choose Pixel or CTS Type-4A parameters.
- `OnePlusVendorNfcController`: firmware-scoped `nfcvendorlib` /
  `setHceTypeAConfig`, RF guard handling, and exact state restoration.
- `XiaomiNfcController`, `XiaomiNxpConfig`: scoped garnet NXP compatibility,
  firmware configuration parsing, and durable recovery.
- `MotorolaStNfcController`: scoped ST experiment and pending restoration.
- `OemOperationReport`: capabilities, transaction, and restoration evidence.
- `RawNfcDeviceCatalog`: model/profile policy and discovery/UID-only preferences.
- `LegacyNfcController`: `activateUidOnlyThenLegacy`, `activateUidOnly`,
  `activateLegacy`, `restoreConfiguration`, and `executeWithPollingPaused`.
- `RawNciCodec`: payloads, GET parsing, and exact snapshot encoding.
  `RawOperationReport` records every SET and outcome.
- `LegacyNfcDiagnostics.runReadOnlyProbe`: GET-only inspection; diagnostic
  availability does not establish emulation support.

Corresponding controller/configuration/codec tests cover policy, parsing, and
transactions. Transport/reflection changes still need physical-device evidence;
see [compatibility.md](compatibility.md).

## Wear OS and build

- `wear/`: manual-card app. `WatchActivity` owns list/editor gestures;
  `WatchReleasePolicy` restricts activation to the current verified model.
- `WatchNfcSession`, `WatchSessionStore`: recovery state before writes, retained
  failures, and restoration blocked across firmware changes.
- `WatchHardwareBackend`: OEM activation, optional UID readback, and restoration
  of legacy raw snapshots left by older test builds.
- `WatchNfcSessionTest` covers transaction ordering; `WatchFlowInstrumentation`
  exercises manual CRUD on an emulator without NFC.
- `wear-diagnostics/`: independent GET-only probe; no activation controller.
- Wear Gradle sync tasks copy explicitly selected sources from `app/`, including
  LocalWatchLink and WatchCardPacket. Phone CSV and launcher classes are excluded.
  WatchActivity receives only on its foreground Receive screen; cancellation,
  backgrounding and timeout close the socket. Physical Bluetooth interoperability
  still needs confirmation. See [watch-transfer.md](watch-transfer.md).
- Each module's `build.gradle.kts` defines packages, SDKs, variants, and optional
  signing. The Gradle distribution/checksum is pinned under `gradle/wrapper/`.

Build commands and NFC invariants are in [../CONTRIBUTING.md](../CONTRIBUTING.md).
