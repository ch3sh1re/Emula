# Watch error UI and local diagnostic reports

Card names and UIDs stay visible during activation. Starting / stopping progress
appears below the selected card. A missing ADB grant or disabled NFC shows a short
message and Setup / Open NFC settings beside that card, with scrolling to reveal
the remedy. These prerequisites do not open a diagnostic-error dialog.

Opening without WRITE_SECURE_SETTINGS shows a compact One-time setup prompt with
Later and Setup. It does not repeat on every resume; shown/open state survives
Activity recreation. Setup explains wireless ADB, offers system Developer options
when resolvable, and displays the installed package's command. It does not implement
ADB pairing or grant itself permission. A new grant is detected without restarting
or automatically activating a card.

Actual activation / deactivation / restoration failures open a persistent fullscreen
dialog with a short reason and Send report to phone. Recovery retains Restore NFC.
Diagnostics opens the cached operation report without repeating the operation or
issuing diagnostic GETs. Prepare report runs a fresh read-only GET and refreshes
capability/restoration details without replacing the last operation result.
An unavailable/failed GET is included in a shareable report. Preparing never
activates a card or retries restoration.
Security errors after the grant offer diagnostics rather than another grant.

## Transfer

1. On the watch error dialog, choose **Send report to phone**. It is also available
   in Settings → Diagnostics. It prepares the report automatically when needed;
   there is no separate prerequisite diagnostic button. A just-prepared report is
   reused. Allow Nearby devices if requested.
2. On the paired phone, open Emula's menu → **Get watch diagnostics** and select
   that watch. Keep both apps open. Delivery uses direct secure Bluetooth RFCOMM,
   without Internet permission, Data Layer or a cloud relay.
3. The phone saves the report privately before acknowledging delivery and opens
   a selectable preview with **Copy**, **Share report**, and **Close**. Share opens
   the system chooser; the user chooses the destination and whether to send.
   The watch confirms that the phone has saved the report.
4. **Get watch diagnostics → View last watch report** reopens the retained report,
   including after Activity recreation or an interrupted acknowledgement.

Reports can contain UIDs, firmware and operation / rollback details. The watch
warns before transfer and the phone warns before copying/sharing. Only the most
recent received report is retained in private phone files, separately from the
phone's NFC diagnostics. Sending a report never changes cards or NFC state.

Both apps need the new diagnostics transport. Older builds still use the unchanged
card-transfer protocol, but cannot receive reports. Devices must already be paired;
no discovery or background service is added. The watch accepts one bonded peer
while its Send screen is open. This trusts Bluetooth pairing rather than app
certificates. Back / Cancel, leaving the foreground, or a 90-second deadline closes
the sockets. An already saved report remains available after cancellation or a
lost acknowledgement; retrying replaces the last report.

Diagnostic transfer requires phone 1.0.16 (36) and Wear 1.0.2-wear (10003)
or newer. Prepared reports and the experimental watch raw fallback are included
in Wear 1.0.4-wear (10005).

## Ownership and verification

- `WatchUiError` classifies prerequisites and diagnostic failures; `WatchActivity`
  owns inline status, setup/error dialogs and offering a report.
- `WatchReportPacket` bounds version-1 UTF-8 reports to 256 KiB, with separate
  hello, request, report and receipt frames. Invalid UTF-8, headers, lengths and
  truncated data fail before phone storage.
- `WatchReportLink` owns secure sockets, cancellation and timeout. Its service
  UUID/protocol are separate from `LocalWatchLink` / `WatchCardPacket`, preserving
  existing card-transfer compatibility. It calls no NFC controller.
- `WatchReportReceiver` owns phone selection, atomic private save, preview,
  clipboard and system sharing. MainActivity forwards menu and lifecycle events.
- `WatchReportPacketTest` covers Unicode, byte limits, malformed/truncated reports
  and protocol stages. `WatchUiErrorTest` covers prerequisites, reflected failures
  and rollback evidence. `WatchFlowInstrumentation` adds small-screen setup,
  progress, inline prerequisites, failure actions and read-only preparation checks to CRUD
  on a clean emulator without NFC.

Implemented from Emula's own source and official Android APIs. No implementation
from another emulator was used. Activation transactions and the Android 15+ minimum
remain unchanged. This does not add Wear OS 4 (Android 13) compatibility or prove
support for another watch model.

Native Wear OS rendering,
real phone/watch Bluetooth interoperability, permission dialogs and reader behavior
still need physical-device checks. Verify cancellation/backgrounding, both Nearby
devices grants and report contents. Record reader-confirmed activation separately;
a successful diagnostic transfer does not prove emulation support.
