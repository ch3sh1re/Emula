# Emula code map

## Phone

| Start here | Responsibility |
| --- | --- |
| `MainActivity`, `UidByteGrid`, `UidCodec` | Card list and editor, NFC reading, setup and diagnostic actions. |
| `CardStore`, `SavedCard`, `CardPalette` | Local card storage, migration of older records and stable card colors. |
| `QuickAccess`, `BackgroundNfcService`, `PhoneNfcRuntime`, `PhoneNfcSession` | Shortcuts, widgets and tile actions share the same serialized session as the foreground UI. |
| `PhoneNfcBackend`, `NfcActivationPolicy`, `RawNfcDeviceCatalog` | Firmware capabilities, activation path selection, saved originals and startup recovery. |
| `OemNfcController`, `LegacyNfcController` | OEM configuration, raw operations, controller readback and restoration. Device-specific recovery and vendor controllers live alongside these classes. |
| `StPhoneNfcController`, `StVendorSdk`, `StHceProfile` | Select the Fairphone 5 and Redmi sapphiren ST service, capture its HCE profile and verify the fields restored by the shared ST transaction. |
| `NfcDiagnosticAssessment`, `NfcStackDiagnostics`, `LegacyNfcDiagnostics`, `RawOperationReport`, `OemOperationReport` | Interpret prerequisites and retain separate evidence for activation, recovery and the current read-only probe. |
| `DiagnosticsEmail`, `CardTransfer`, `CardCsv` | Sharing diagnostics and importing or exporting cards. |

## Watch and shared code

| Start here | Responsibility |
| --- | --- |
| `wear/…/WatchActivity`, `WatchCardSwipe` | Card list, gestures, editor, setup and diagnostics. |
| `WatchActivationPolicy`, `WatchHardwareBackend` | Watch capabilities, activation, readback and recovery. |
| `WatchCardTileService`, `WatchTileLayout`, `WatchTileAction` | Wear tile, saved-card selection and replay-safe actions. |
| `WatchNfcRuntime`, `WatchNfcSession`, `WatchSessionStore`, `WatchStopAlarm` | Shared watch NFC queue, durable session and optional timed restoration. |
| `app/…/WatchCardSender`, `WatchCardPacket`, `LocalWatchLink` | Paired Bluetooth card transfer, acknowledgements and optional color exchange. |
| `WatchReportPacket`, `WatchReportLink`, `WatchReportReceiver` | A separate paired transfer for a user-selected diagnostic report. |
| `wear/build.gradle.kts` | Selects shared phone sources and launcher resources for the watch build. |
| `wear-diagnostics/` | Standalone read-only watch diagnostic app. |

## Checks

JVM tests live in `app/src/test` and `wear/src/test`. `UidEditorTestInstrumentation` covers Android input and phone storage. `WatchFlowInstrumentation` covers watch controls, gestures and error states. Hardware activation additionally needs an external reader. [Build and test commands](../CONTRIBUTING.md) · [Compatibility](compatibility.md)
