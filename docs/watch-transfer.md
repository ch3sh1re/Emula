# Sending cards to a watch

Pair the watch and phone in system settings. In Emula on the watch, tap the circular arrows (**Receive from phone** on older versions). On the phone choose **Send all cards to watch**, select the watch and allow Nearby devices on each device. Keep both apps open until the transfer finishes.

New UIDs are added. Matching UIDs keep their watch names. Phone 1.0.40 and Wear 1.0.6 also copy colors, including colors for matching cards. With an older peer, names and UIDs still transfer. Edits and deletions on either device are local.

## How the transfer works

`LocalWatchLink` opens an encrypted Bluetooth RFCOMM connection to a system-paired peer. The watch accepts one connection per Receive action. Cancellation, leaving the receive screen and the 90-second deadline close the connection. The phone can retry an interrupted transfer, and the watch skips UIDs it already has.

`WatchCardPacket` checks the whole batch before saving. It accepts 4-, 7- or 10-byte UIDs, up to 2,000 cards, 1,024 UTF-8 bytes per name and 256 KiB per batch. The watch acknowledges the saved cards first. Peers that support colors then exchange them in the same card order and confirm storage.
