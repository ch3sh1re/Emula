package dev.dunno.emula;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;

/** The protected NFC broadcast may come from the NFC app's privileged UID. */
public final class NfcQuickAccessReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) {
            int state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, -1);
            if (state == NfcAdapter.STATE_OFF) {
                PhoneNfcRuntime.adapterOff(XiaomiNfcController.ownsRestartBroadcast(context));
            } else if (state == NfcAdapter.STATE_ON) {
                PhoneNfcRuntime.adapterOn();
            }
            QuickAccess.refresh(context);
        }
    }
}
