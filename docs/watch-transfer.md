# Local phone-to-watch card transfer

The first release deliberately implements a manual append, not automatic catalog
replication. Open **Receive from phone** on the watch, then **Send all cards to
watch** in the phone app menu. Select the paired watch the first time; a successful
destination is remembered on the phone. Allow **Nearby devices** on both devices.

## Behavior

- Both apps must remain open. The watch receives only on its Receive screen.
- Send a snapshot of the entire saved phone list. An empty phone list cannot
  erase the watch. Existing watch cards, names and IDs are kept.
- Deduplicate by the complete UID, including its length, against both existing
  watch cards and earlier entries in the same batch. The first new name wins.
- Give new watch records local IDs. No phone record ID, active-card selection,
  NFC permission or hardware configuration is transferred.
- Parse and validate the complete batch before saving it. Store the append in
  one synchronized read/modify/commit operation, and acknowledge only after save.
- Show added/skipped counts. If the connection breaks after save but before its
  acknowledgement reaches the phone, the phone reports unconfirmed delivery;
  repeating the operation skips already received UIDs.
- Cancel, backgrounding the watch or destroying either Activity closes its local
  sockets. Each operation also has a 90-second deadline. An already committed
  append is not undone by cancellation.
- Copies are independent. Phone edits/deletions do not propagate automatically.

## Transport and limits

`LocalWatchLink` uses Android secure RFCOMM sockets and the system's existing
Bluetooth pairing. The watch checks that the peer is bonded before sending its
protocol greeting. It accepts a single connection per Receive action. This
trusts the paired device; it is not application-certificate authentication.
Keep pairing limited to devices you trust.

There is no Google SDK, Data Layer, cloud relay, network socket, Internet
permission, device scan, advertising or location permission. Bluetooth access is
requested only for an explicit transfer. No background receiver/service is added.

`WatchCardPacket` uses a separate versioned greeting, length-prefixed batch and
counted receipt. Limits: 2,000 incoming cards, 1,024 UTF-8 bytes per name and
256 KiB per encoded batch. Only 4-, 7- and 10-byte UIDs are accepted. Invalid
versions, lengths, UTF-8, truncated batches and trailing payload bytes fail before
storage. The current card JSON format is retained.

Implemented from Emula's own card model and official Android documentation;
no code was imported from another emulator:

- [Connect Bluetooth devices](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Data Layer routing considerations](https://developer.android.com/training/wearables/data/overview)
  explain why Data Layer was not selected for the strict local-only requirement.

## Verification and remaining device checks

`WatchCardPacketTest` covers all UID lengths, Unicode, malformed/truncated frames,
limits, receipts, duplicate detection, repeated delivery and existing-record
preservation. `CardTransferChecks.watchAppend` checks real isolated Android
storage, concurrent appends and unchanged active-card state, without NFC calls.
The same packet/link sources compile in both phone and watch modules.

Physical Bluetooth RFCOMM interoperability, device-pairing behavior and transfer
while NFC emulation is active still need confirmation on a real phone/watch pair.
This feature adds no emulation models. The watch activation gate remains Galaxy
Watch9 SM-L355F; reader evidence still covers four-byte OEM emulation only.

For a tester report, record both device models, Android/firmware and Emula
versions, whether both Nearby devices grants were accepted, the added/skipped
counts and the result of a repeat transfer. Do not include real UIDs in public
issues. Report an actual reader result separately from transfer success.
