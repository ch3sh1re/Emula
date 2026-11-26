# Watch diagnostics

If activation fails, open **Settings → Diagnostics** on the watch or use the report action in the error dialog. **Prepare report** reads the current NFC configuration and includes the most recent activation or recovery result. To investigate a reader that stays silent, prepare the report while the card is still active.

1. On the watch choose **Send report to phone**.
2. On the paired phone choose **Get watch diagnostics → Receive report from watch**, then select your watch. Allow Nearby devices on both devices and keep both apps open.
3. Review the received report on the phone. You can copy or share it, or return to **View last watch report** later.

The phone retains the most recently received report in private storage. A report can contain card UIDs, firmware details and values observed during activation or restoration. Review it before sharing. Sending a report uses a separate Bluetooth connection from card transfer. [Card transfer](watch-transfer.md)
