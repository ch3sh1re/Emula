package dev.dunno.emula;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;

/** The protected NFC broadcast may come from the NFC app's privileged UID. */
public final class NfcQuickAccessReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) {
            if (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF) == NfcAdapter.STATE_OFF) {
                PhoneNfcRuntime.adapterOff();
            }
            QuickAccess.refresh(context);
        }
    }
}
