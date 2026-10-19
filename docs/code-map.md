# Emula code map

Use this map to choose a narrow reading set before opening implementation files.
Symbol names are intentionally used instead of line numbers so the map survives
ordinary edits.

## Runtime flow

```text
card tap / startup restore / launcher shortcut / widget / Quick Settings tile
  -> MainActivity.setActiveCard / CardActionActivity / CardTileService
  -> BackgroundNfcService + PhoneNfcRuntime (one application-owned worker)
     -> PhoneNfcSession (resolve current record, toggle, initialize once)
     -> PhoneNfcBackend + NfcActivationPolicy (existing path selection)
     -> OemNfcController (preferred API)
        -> OnePlusVendorNfcController (CPH2747/CPH2707 vendor HCE)
        -> MotorolaStNfcController (explicit experiment, off by default; pending restore)
     -> PhoneNfcBackend.rawChange
        -> LegacyNfcController (catalog-gated raw fallback)
           -> RawNciCodec + RawOperationReport

app menu -> MainActivity.showNfcDiagnostics -> PhoneNfcRuntime queue
            -> LegacyNfcDiagnostics (read-only raw probe)
```

## 1. UI and card editor

Purpose: render cards, edit or scan a 4-, 7-, or 10-byte UID, start activation, and show
status/errors.

- Start in `MainActivity.java` at `buildUi`, `renderCards`, `createCardView`,
  `toggleCard`, `showCardEditor`, `saveEditor`, and `startReadingIntoEditor`.
- Catalog search starts at openMainSearch/closeMainSearch/filterMainCards. CardSearch
  matches names and normalized UID fragments for both the main list and tile chooser;
  CardSearchTest owns the pure rules. Query changes only toggle row visibility,
  retaining original color indices, IDs and input focus. An excluded active card
  keeps a visible summary and stop action. Main query/open state survives recreation;
  the platform back callback closes search after IME handling.
- Header add/search/menu buttons share iconAction, 48 dp cells, 24 dp icons and
  centered drawing with equal spacing; no extra margin precedes the overflow menu.
- `showCardEditor` places the 4/7/10-byte selector before the UID fields.
  Name and UID labels/control boundaries share the form margins and 8 dp label gaps;
  `editorFieldBackground` gives the name field and UID cells the same rounded fill
  and focus outline (actual focus for Name, logical activation for UID bytes).
  Ten numbered byte cells are created once; `setEditorUidLength` changes visibility
  and clamps the logical active byte. `UidByteGrid` measures equal-sized cells
  and centers rows (4, 4+3, or 5+5) without detaching editors.
  The grid always reserves two rows, centering the single 4-byte row vertically;
  `getEditorDialogHeight` is independent of UID length, and switching does not resize
  the window, keeping the read button in place.
  `UidByteGrid` owns focus and one `BaseInputConnection` for the entire UID. Taps,
  completed bytes, IME Next, and backspace update the logical byte/selection without
  changing the served view or restarting the keyboard during held deletion.
  `createUidField` configures cells and confines IME Next to visible bytes.
  `UidByteEditText` draws the logical selection, cursor, and activated outline.
  Decorations use the same scrolled canvas as TextView (no second scroll subtraction);
  cursor blinking starts visible on each editing interaction.
  The form excludes itself and descendants from autofill;
  UID fields also opt out individually. Their input type is normal uppercase text with
  suggestions disabled, rather than a password variation that can trigger credential
  suggestions in the keyboard. IME flags request ASCII input and no personalized
  learning; the existing filters enforce two uppercase HEX characters per byte.
  `UidByteGrid.deleteBeforeCursor` handles hardware backspace and both IME surrounding
  deletion APIs. Pure `UidByteDeletion.delete` computes the changed bytes and cursor,
  including repeated/accelerated deletion, selections, empty gaps, and the first-byte
  boundary. It only changes visible bytes at/before the cursor; hidden drafts survive.
  Tests: `UidByteDeletionTest.kt` owns these transitions.
  `UidEditorTestInstrumentation` in `app/src/androidTest/` exercises real Android
  input connections on unattached views: one connection across repeated deletion,
  typing/taps/IME Next, resumed typing after deletion, length changes, and hardware key
  repeat, scrolled-cell cursor rendering, and old four-byte storage round-trips in an
  isolated preference file, without opening an Activity. Build with `assembleDebugAndroidTest`, install
  debug and androidTest APKs, then run
  `adb shell am instrument -w dev.dunno.emula.debug.test/dev.dunno.emula.UidEditorTestInstrumentation`.
  `readEditorUidParts` saves all ten draft fields; activation
  uses only `editorUidLength`. Editor state includes the selected length and full draft.
  `onTagDiscovered` selects the scanned UID length automatically.
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
- Activation UI starts at `setActiveCard`; PhoneNfcBackend.change/rawChange owns
  the shared transaction; uncatalogued-device consent starts at
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

Purpose: persist named 4-, 7-, or 10-byte UIDs and keep one process-local active card.

- `SavedCard.kt`: immutable value object; copies UID input and enforces length.
- `UidCodec.kt`: `isSupportedLength`, default/maximum lengths, and hexadecimal `parse`.
  Formatting lives in `Hex.kt`. Storage keeps the existing Base64 JSON format.
- `CardCatalog.kt`: pure `upsert` and `delete` list transformations.
- `CardStore.kt`: `getCards`, `save`, and `delete`; JSON is stored under
  SharedPreferences file `office_cards`, key `cards`.
- `CardSession.kt`: `beginProcess`, `getActiveCardId`, `isActive`,
  `setActiveCardId`, and `clearActiveCard`.
- Callers are primarily `MainActivity.renderCards`, `saveEditor`, `deleteCard`,
  `setActiveCard`, and `restoreDefaultsAtSessionStart`.
- Tests: `SavedCardTest.kt`, `UidCodecTest.kt`, `CardCatalogTest.kt`, and
  `CardSessionTest.kt`. Android persistence behavior is not covered by JVM tests.

## CSV file transfer

- MainActivity delegates menu import/export, ACTION_SEND/ACTION_VIEW, activity
  results and lifecycle state to CardTransfer. The Activity is singleTask so a
  shared file reaches the existing card session through onNewIntent.
- CardCsv owns UTF-8 name,uid parsing/formatting, RFC-style quoting, 4/7/10-byte
  HEX validation and bounded input. CardImport.plan handles append/replace;
  CardImport.apply commits the complete validated list through CardStore.replaceAll.
  The shared CardStore/CardCatalog files have no CSV dependency for watch builds.
  CSV classes are phone-only and are excluded from syncWatchSources. Append preserves
  existing records and skips matching UIDs; replace preserves every input row.
- CardTransfer reads/writes documents off the main thread, always asks approval,
  and offers append/replace only for a nonempty catalog. MainActivity.importCsvCards
  deactivates an active card before committing a replacement; append leaves it active.
  Pending URIs survive recreation; CSV contents are never stored in a Bundle.
  Host.chooseExport delegates the export action menu to MainActivity.showCsvExportChooser;
  it shares choiceDialogPanel/List, addChoiceCancel and showChoiceDialog with the
  tile card chooser. CardTransfer owns and dismisses the menu on disposal.
- CardReadCancellation coordinates Future interruption, stream ownership and
  asynchronous close/provider cancellation. CardTransfer uses CancellationSignal
  when opening an asset; cancel, replacement and dispose invalidate the request
  and release its input. CardCsv checks interruption during reads and parsing.
- CardExportProvider exposes only generated immutable cache snapshots through
  temporary read grants. Document picker imports and exports need no broad storage
  permission. Manifest filters handle CSV shares and document opening.
  FILE_NAME is emula.csv for document-picker suggestions, sharing metadata and the
  last URI segment. Unique snapshot IDs map to private UUID.csv cache files;
  legacy timestamp/UUID URIs remain readable. Provider checks cover equal visible
  names with distinct contents, short metadata names, old URIs and path/write rejection.
- CardCsvTest and CardImportTest own pure parsing/merge behavior;
  CardReadCancellationTest owns queued/opening/blocked-read cancellation races.
  CardTransferChecks
  adds isolated Android storage, provider read/write/path restrictions and manifest
  resolution and real Android pipe cancellation to UidEditorTestInstrumentation
  (15 checks total including quick access and background lifecycle, no hardware NFC calls).
- User behavior and format: docs/features/csv/README.md. Deferred SAK/ATQA research
  and preserved primary-source artifacts: docs/features/sak-atqa/README.md.

## Phone quick access

- QuickAccess publishes the first four saved cards through ShortcutManager, pins
  individual cards, creates immutable PendingIntents, and remembers the tile target.
  Launcher requests contain record IDs and an installation-local token, never a UID.
- CardActionActivity is a no-display authenticated launcher/widget router; ordinary
  actions start BackgroundNfcService and finish without opening MainActivity.
  Platform Theme.NoDisplay supplies translucency and a null background/animations;
  launcher intents use FLAG_ACTIVITY_NO_ANIMATION, and the router suppresses open
  and close transitions for previously pinned intents too.
  CardQuickActions handles the interactive fallback: it consumes requests once, persists only a pending request through
  recreation, waits for startup/UI work, and resolves current saved records before
  calling MainActivity.toggleCard. Ordinary tile taps use the last selected/current
  card; tile preferences open selection without changing NFC. Existing CSV approval
  takes priority. All activation paths use PhoneNfcRuntime.
  CardQuickActions.Host.chooseCard delegates the tile chooser to
  MainActivity.showTileCardChooser. It reuses editor dialog colors, window sizing,
  action buttons and the main list's cardBackground; selected rows expose radio
  semantics, while a bounded scroll area retains the title and Cancel action.
  Visible and accessibility UID labels reuse card_uid, matching the main list.
  Rows are created eagerly in catalog order; a fixed search field filters existing
  rows with CardSearch. CardQuickActions saves its independent query on recreation.
  fitSearchDialogToKeyboard caps the floating window above the IME and moves Cancel
  beside the input when height is limited; the input is never detached or recreated.
  Selection callbacks still resolve the current record ID before remembering it.
  showCardMenu also exposes a checked Use in Quick Settings action that resolves
  the current record and remembers its ID without activating NFC.
- QuickCardPolicy owns pure ID lookup, tile target priority and toggle identity.
  QuickCardPolicyTest covers duplicate names/UIDs, edits, deletion, replacement and
  the remembered target taking priority over a different active card.
- CardWidgetProvider renders per-card RemoteViews. CardWidgetConfigureActivity
  validates the widget belongs to this provider before accepting a selection.
  Deleted cards offer reconfiguration instead of selecting another record.
- CardTileService exposes a normal, toggleable system tile (ACTIVE_TILE=false); it requests unlocking
  before starting the foreground service, with an immutable activity PendingIntent
  for permission/selection/consent fallback. CardTilePreferencesActivity
  handles system long presses even when System UI drops the intent action.
  QuickAccess.addTile uses the system placement prompt.
  System UI starts listening when QS opens, retains listening across tile updates,
  and stops when QS closes. A weak reference lives only during listening and is
  cleared on destruction. QuickAccess.refresh immediately renders a listening tile
  on main; closed tiles render on their next onStartListening. No app-triggered
  requestListeningState calls are needed. Debug QS event logs expose lifecycle,
  click/preflight and render timing without card contents.
- EmulaApplication listens to phone catalog/quick-access preferences and refreshes
  launcher surfaces after process creation. PhoneNfcRuntime refreshes after NFC state
  transitions; NfcQuickAccessReceiver handles the protected adapter-state broadcast,
  invalidating active state on OFF through the queue without controller writes.
  These phone-only classes are absent from the explicit wear source include list.
- QuickAccessChecks adds authenticated intents, distinct immutable PendingIntents,
  real RemoteViews rendering, resolved router window attributes and component metadata
  checks to the Android runner. TileListeningChecks covers a cached ACTIVE state,
  retained listening after updates, closing and reopening QS in an actual System UI
  bound tile without clicking it or issuing hardware NFC commands.
  Feature behavior and verification: docs/features/quick-access/README.md.
  Search and per-card tile selection: docs/features/search/README.md.

## Phone background control

- PhoneNfcRuntime owns the only phone executor. Activity recreation never interrupts
  controller/restoration work. NFC, read-only diagnostics, CRUD and approved CSV
  commits are serialized; foreground editor/reader/import approval block quick actions.
- PhoneNfcSession resolves IDs inside the queue, initializes once per process/NFC
  reset, and deactivates before deleting/replacing/changing an active UID. Renaming
  an active card retains activation. PhoneNfcSessionTest owns these pure contracts.
- PhoneNfcBackend uses application context and the existing OEM/raw controllers.
  Recovery includes raw, OnePlus and ST snapshots. Raw recovery preferences commit
  before activation; primary/rollback evidence remains in RawOperationReport.
- BackgroundNfcService is an unexported connectedDevice foreground service. It
  promotes before queued hardware work, keeps a stop notification while active or
  recovery is pending, requests immediate notification visibility, stops when idle,
  and uses START_NOT_STICKY. No boot activation. QuickActionFeedback replaces the
  previous result toast and adds result haptics respecting silent mode, DND and touch
  feedback settings; queued intermediate successes do not produce stale feedback.
  PhoneNfcRuntime/PhoneNfcBackend log debug-only queue, work, delivery and controller
  initialization/change timings without card identifiers or UID data.
- BackgroundNfcChecks injects a package-private fake backend and exercises the real
  no-display router, foreground service, notification action, queue, UI reopening,
  filtering while active and stopping a card excluded by the search,
  eight-command service bursts, reader conflict, failed replacement and NFC-off
  recovery on an emulator. Production
  has no Intent/debug flag to select this backend. See docs/features/background-control.

## 3. OEM UID emulation

Purpose: use Android/OEM NFC-A tag emulation when the extension API exists.

- Enter through `OemNfcController.isUidEmulationSupported`, `setCardEnabled`, or
  `restoreDefaults`; capability and last-operation evidence is exposed by
  `inspectCapabilities`, `lastOperationForDisplay`, and `lastOperationDetailed`.
- `resolveAndroidApi` reflects `getNfcOemExtension` and
  `emulateNfcTechnologyATag`; `resolveProfile` chooses Pixel-minimal or CTS Type 4A
  arguments.
- `OnePlusVendorNfcController` is restricted to OnePlus CPH2747/CPH2707 on API 36+ and
  reflects the optional firmware library `nfcvendorlib`. It invokes
  `setHceTypeAConfig`, neutralizes the firmware RF-field guard, and persists the
  pre-existing OnePlus access-card state until deactivation or startup restore.
  CPH2707 reuses the same method and transaction experimentally; hardware activation
  and restoration are pending device evidence. Raw candidate selection is unchanged.
- `OemOperationReport` records vendor-library/service/method resolution, ATQA/SAK/UID,
  guard transitions, snapshot state, failures, and rollback outcome for the NFC
  Diagnostics menu.
- `MotorolaStNfcController` probes the optional
  `com.st.android.nfc_extensions.NfcAdapterStExtensions` library and its
  `programHceParameters` / `setNciConfig` methods without invoking setters.
  Ordinary phone debug/release builds disable this candidate: model/SDK/library
  detection does not identify the NFCC chip, and external-reader proof is absent.
  Explicit debug opt-in (`-Pemula.motorolaStExperiment=true`) and historical
  `internalSt` remain limited to `moto g stylus 5G - 2024`, `boston`, API 35.
  The watch BuildConfig keeps it disabled. isApiAllowed separately permits resolving
  the API for an existing snapshot on that model with the flag off; setCardEnabled
  still rejects new activations. OemNfcController and PhoneNfcBackend prioritize
  pending ST restoration independently of the activation flag; wrong firmware/model
  blocks restoration without falling through to other writers.
  The vendor API returns `void`;
  `LegacyNfcDiagnostics` supplies read-only configuration evidence before/after it.
  Activation requires a complete seven-parameter snapshot with system-managed
  RF configuration (`0x85=1`), persisted with the firmware fingerprint before writing.
  Deactivation/rollback calls vendor disable, restores the saved NCI values through
  the service, verifies all seven values and only then clears the snapshot.
  The service resets routing/NDEF-NFCEE to its defaults; exact restoration evidence
  covers standard NCI parameters, not the full proprietary controller state.
  Startup only changes ST state when this app has a pending snapshot. A pending raw
  operation must be restored before switching to ST. Raw remains an explicit override
  with the existing firmware consent; its toggle is unavailable during an ST session.
  MotorolaStNfcController.tryFirst stops the chain after verified ST success, or permits
  fallback after failure before writes / verified rollback with no pending snapshot.
  Deactivation failure or unknown controller state blocks fallback. OemNfcController
  next tries the Android OEM API; only its absence raises MotorolaFallbackException
  so PhoneNfcBackend can apply the existing catalog/firmware-consent raw policy.
  OemOperationReport.stAttempt preserves the preceding ST failure and rollback.
  `MotorolaStNfcControllerTest` covers scoping, persistence ordering, silent failure,
  card switching, rollback failure/retry and firmware changes. Hardware is unverified.
  Disabled status, evidence and patch inventory: docs/features/motorola-auto/README.md.
  On the targeted Motorola, pending experimental raw restoration first uses GET-only
  readback: an exact match to all six saved parameters clears the pending state
  without another SET. Missing/different values keep the existing restoration flow;
  the comparison is covered by `LegacyNfcControllerTest`.
- `PhoneNfcBackend.change` chooses this path first unless raw mode is forced;
  `PhoneNfcBackend.rawChange` handles policy-authorized raw transactions.
- Contract: 4-, 7-, or 10-byte UID and an enabled NFC adapter. The Android OEM API
  returns integer status `0`; the experimental ST API returns `void` and requires
  independent configuration readback before reporting activation.
  Keep profile changes independent from raw-NCI candidate selection.
- Physical-reader confirmations currently cover four-byte UIDs; 7/10-byte operation
  still requires device and reader evidence.
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
- `uidOnlyPayload` and `legacyPayload` encode the full 4/7/10-byte UID and its actual
  length. Legacy profile parameters stay unchanged; snapshot restoration accepts
  exact original values, including an empty NFCID1.
- An uncatalogued opt-in must carry a complete original-configuration snapshot;
  it is encoded by `RawNciCodec` and retained only until exact restoration.
- Transaction ownership: `PhoneNfcBackend.rawChange`, `initialize`, `saveLegacy`
  and `loadOriginal`; UI preferences remain in MainActivity.isRawModeForced and
  setRawModeForced. The shared PhoneNfcRuntime serializes every entry point.
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
  platform three-button `AlertDialog` bar. Prepare report explains the read-only
  collection step. If probing is possible, Copy and Email stay disabled until an
  attempt completes, including failure; otherwise the base report can be exported.
  Repeating preparation disables them again while the probe is pending.
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
- Xiaomi garnet investigation and preserved firmware evidence are in
  docs/features/xiaomi-garnet/firmware-analysis.txt. The neighboring Global
  2.0.205 NXP service exposes stubbed raw-NCI methods; this is not yet established
  for the reported 2.0.206 service. Its NXP transit setter persists configuration
  and restarts NFC, so it has not been added as an activation path.
- Parsing tests are in `RawNciCodecTest.kt`; the reflection/transport path needs
  a physical-device smoke test with `WRITE_SECURE_SETTINGS` granted.

## Cross-cutting build and release files

- `wear/`: standalone manual-card Wear OS release, package `dev.dunno.emula`
  (debug remains `dev.dunno.emula.watchtest` for test-app upgrades). `WatchActivity`
  renders cards without a branding header: tap toggles OEM emulation, hold opens
  the name/UID editor, and a left swipe opens deletion confirmation. Vertical
  scrolling and right swipes remain independent; accessibility actions also expose
  editing/deletion. The compact editor has strict 0-9/A-F input, 8/14/20 characters,
  and no explanatory paragraph. Settings owns Setup and read-only Diagnostics.
  `WatchReleasePolicy` gates activation to Samsung SM-L355F and parses 4/7/10-byte
  HEX UIDs; external-reader evidence covers four bytes. Editing/deleting an active
  card restores NFC before changing storage. `WatchNfcSession` and `WatchSessionStore`
  persist restoration state before writing, retain failures, and block restoration
  across firmware changes. `WatchHardwareBackend` activates through OEM only, adds
  optional UID readback, and retains raw restoration for snapshots from older test
  builds. Existing phone routing is unchanged. `WatchNfcSessionTest` owns transaction
  ordering/recovery; `WatchFlowInstrumentation` exercises manual CRUD only on a clean
  emulator without NFC. See `wear/README.md` for build/ADB/run commands and
  `wear/NFC-source-review.md` for AOSP findings and device evidence. Deferred card
  synchronization is documented only on branch `feature/watch-card-sync`.

- `wear-diagnostics/`: standalone read-only Wear OS probe, package
  `dev.dunno.emula.watchprobe`. `WatchDiagnosticsActivity` owns the round-screen UI
  and saves `files/nfc-diagnostics.txt`; `WatchNfcCapabilities.inspect` resolves
  OEM/raw methods without invoking emulation or polling controls. Its Gradle sync
  task reuses `LegacyNfcDiagnostics`, `RawNciCodec`, `RawOperationReport`, `UidCodec`,
  and `AppLog` only. No phone Activity or activation controller is compiled in.
  Build with `:wear-diagnostics:lintDebug :wear-diagnostics:assembleDebug`; see
  `wear-diagnostics/README.md` for ADB installation, grant, and report retrieval.
  The probe does not establish UID-emulation support on any watch.

- `app/build.gradle.kts`: SDK levels, application IDs, version, JDK/Kotlin 17,
  signing, and dependencies.
- `README.md`, `CHANGELOG.md`, `TODO.md`: current behavior and project status.
- `docs/history/`: archived task prompts, reports, and release-preparation checklist;
  use only when a request explicitly refers to historical work.
- `distribution/google-play/`: listing, screenshots, release notes, checksums,
  and signed artifacts. Do not rewrite published release artifacts as part of an
  implementation change.
- `distribution/google-play/screenshots/README.md`: isolated emulator setup,
  demo-mode 5G/Wi-Fi commands, and repeatable release screenshot capture. The AVD
  profile, status-bar script, and capture script live in `distribution/google-play/source/`.
