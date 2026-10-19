package dev.dunno.emula;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Build;

import java.util.List;
import java.util.Map;

/** Existing phone OEM/raw transactions, independent of an Activity lifetime. */
final class PhoneNfcBackend implements PhoneNfcSession.Backend {
    private final Context context;
    private final NfcAdapter adapter;
    private final SharedPreferences legacy;
    private final SharedPreferences settings;
    private final NfcUiContext<Activity> pollingUi;

    PhoneNfcBackend(Context context, NfcUiContext<Activity> pollingUi) {
        this.context = context.getApplicationContext();
        this.pollingUi = pollingUi;
        adapter = NfcAdapter.getDefaultAdapter(context);
        legacy = context.getSharedPreferences("legacy_nfc", Context.MODE_PRIVATE);
        settings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }

    @Override public List<SavedCard> cards() { return CardStore.getCards(context); }
    @Override public String activeId() { return CardSession.getActiveCardId(); }
    @Override public String lastCardId() { return QuickAccess.lastCard(context); }
    @Override public void remember(SavedCard card) { QuickAccess.remember(context, card); }
    @Override public void forgetActive() { CardSession.clearActiveCard(); }
    @Override public boolean recoveryPending() {
        return legacy.getBoolean("pending_restore", false) || MotorolaStNfcController.hasPendingRestore(context)
                || OnePlusVendorNfcController.hasPendingRestore(context)
                || XiaomiNfcController.hasPendingRestore(context);
    }

    private boolean permission() {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private NfcActivationPolicy.Path path(boolean activating) {
        if (MotorolaStNfcController.hasPendingRestore(context)
                || OnePlusVendorNfcController.hasPendingRestore(context)) {
            return NfcActivationPolicy.Path.OEM;
        }
        return NfcActivationPolicy.choose(activating, OemNfcController.isUidEmulationSupported(context),
                LegacyNfcDiagnostics.isRawNciApiAvailable(context),
                LegacyNfcController.isExperimentalAvailable(context),
                settings.getBoolean("force_raw_mode", false),
                Build.FINGERPRINT.equals(settings.getString("experimental_raw_fingerprint", null)));
    }

    private boolean useXiaomi() {
        return XiaomiNfcController.hasPendingRestore(context)
                || (XiaomiNfcController.isTargetDevice()
                        && (settings.getBoolean("force_raw_mode", false)
                            || !OemNfcController.isUidEmulationSupported(context)));
    }

    private boolean useMotorolaRaw() {
        boolean pending = MotorolaStNfcController.hasPendingRestore(context);
        boolean targeted = MotorolaStNfcController.isRawModeTarget();
        if (!pending && !targeted) return false;
        boolean forced = settings.getBoolean("force_raw_mode", false);
        return MotorolaStNfcController.useRawMode(targeted, forced,
                !pending && !forced && OemNfcController.isUidEmulationSupported(context), pending);
    }

    @Override public PhoneNfcSession.Reason preflight(SavedCard card) {
        if (adapter == null) return PhoneNfcSession.Reason.NO_NFC;
        if (!adapter.isEnabled()) return PhoneNfcSession.Reason.DISABLED;
        if (!permission()) return PhoneNfcSession.Reason.PERMISSION;
        if (card == null && recoveryPending()) {
            return legacy.getBoolean("pending_unverified", false) && needsForeground()
                    ? PhoneNfcSession.Reason.FOREGROUND_REQUIRED : null;
        }
        if (useXiaomi()) return null;
        if (useMotorolaRaw()) return null;
        NfcActivationPolicy.Path path = path(card != null);
        if (path == NfcActivationPolicy.Path.UNSUPPORTED) return PhoneNfcSession.Reason.UNSUPPORTED;
        if ((path == NfcActivationPolicy.Path.EXPERIMENTAL_RAW
                || path == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW) && needsForeground()) {
            return PhoneNfcSession.Reason.FOREGROUND_REQUIRED;
        }
        if (path == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW) return PhoneNfcSession.Reason.CONSENT;
        return null;
    }

    private boolean needsForeground() {
        return !pollingUi.isResumed()
                && "UNAVAILABLE".equals(LegacyNfcController.pollingMethod(context));
    }

    private Context pollingContext(boolean unverified) throws Exception {
        Activity activity = pollingUi.acquire(2_000);
        Context selected = activity == null ? context : activity;
        if (unverified && "UNAVAILABLE".equals(LegacyNfcController.pollingMethod(selected))) {
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FOREGROUND_REQUIRED);
        }
        return selected;
    }

    @Override public boolean initialize() throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        try { return initializeController(); }
        finally { AppLog.info("NFC initialization duration_ms="
                + (android.os.SystemClock.elapsedRealtime() - started)); }
    }

    private boolean initializeController() throws Exception {
        CardSession.clearActiveCard();
        if (legacy.getBoolean("pending_restore", false)) {
            requireReady();
            boolean unverified = legacy.getBoolean("pending_unverified", false);
            if (!LegacyNfcDiagnostics.isRawNciApiAvailable(context) || (!unverified
                    && !RawNfcDeviceCatalog.isSupported(Build.MANUFACTURER, Build.MODEL))) {
                throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.UNSUPPORTED);
            }
            rawChange(null, RawOperationReport.Operation.STARTUP_PENDING_RESTORE, unverified, false);
            return true;
        }
        if (XiaomiNfcController.hasPendingRestore(context)) {
            requireReady();
            xiaomiChange(null, OemOperationReport.Operation.STARTUP_RESTORE);
            return true;
        }
        if (useXiaomi()) {
            // No startup reset without our own marker; another app may own a transit override.
            return adapter != null && adapter.isEnabled() && permission();
        }
        boolean vendorPending = OnePlusVendorNfcController.hasPendingRestore(context)
                || MotorolaStNfcController.hasPendingRestore(context);
        if (vendorPending) requireReady();
        if (adapter == null || !adapter.isEnabled() || !permission()
                || (!vendorPending && !OemNfcController.isUidEmulationSupported(context))) return false;
        try {
            OemNfcController.restoreDefaults(context, OemOperationReport.Operation.STARTUP_RESTORE);
            saveLegacy(false, false, null);
            return true;
        } catch (Throwable error) {
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, false, error);
        }
    }

    private void requireReady() throws PhoneNfcSession.Failure {
        if (adapter == null) throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.NO_NFC);
        if (!adapter.isEnabled()) throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.DISABLED);
        if (!permission()) throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.PERMISSION);
    }

    @Override public void change(SavedCard card, boolean confirmed) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        try { changeController(card, confirmed); }
        finally { AppLog.info("NFC change activating=" + (card != null) + " duration_ms="
                + (android.os.SystemClock.elapsedRealtime() - started)); }
    }

    private void changeController(SavedCard card, boolean confirmed) throws Exception {
        requireReady();
        // Restoration remains available even when an initial experimental activation failed
        // before firmware consent could be remembered.
        if (card == null && legacy.getBoolean("pending_restore", false)) {
            rawChange(null, RawOperationReport.Operation.DEACTIVATE,
                    legacy.getBoolean("pending_unverified", false), false);
            return;
        }
        if (useXiaomi()) {
            xiaomiChange(card, card == null ? OemOperationReport.Operation.DEACTIVATE
                    : OemOperationReport.Operation.ACTIVATE);
            return;
        }
        // A confirmed generic-raw fallback must not repeat the ST write/rollback
        // that led to its consent dialog. A pending ST snapshot always wins.
        if (MotorolaStNfcController.attemptRawMode(useMotorolaRaw(), confirmed,
                MotorolaStNfcController.hasPendingRestore(context))) {
            if (legacy.getBoolean("pending_restore", false)) {
                rawChange(null, RawOperationReport.Operation.STARTUP_PENDING_RESTORE,
                        legacy.getBoolean("pending_unverified", false), false);
            }
            if (motorolaRawChange(card)) return;
        }
        NfcActivationPolicy.Path path = path(card != null);
        if (path == NfcActivationPolicy.Path.UNSUPPORTED) {
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.UNSUPPORTED);
        }
        if (path == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW && !confirmed) {
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.CONSENT);
        }
        if (path == NfcActivationPolicy.Path.OEM && legacy.getBoolean("pending_restore", false)) {
            rawChange(null, RawOperationReport.Operation.STARTUP_PENDING_RESTORE,
                    legacy.getBoolean("pending_unverified", false), false);
        }
        if (path == NfcActivationPolicy.Path.CATALOG_RAW
                || path == NfcActivationPolicy.Path.EXPERIMENTAL_RAW
                || path == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW) {
            boolean unverified = path != NfcActivationPolicy.Path.CATALOG_RAW;
            rawChange(card, card == null ? RawOperationReport.Operation.DEACTIVATE
                    : RawOperationReport.Operation.ACTIVATE, unverified, unverified && card != null);
            return;
        }
        try {
            if (card == null) {
                OemNfcController.restoreDefaults(context);
                CardSession.clearActiveCard();
            } else {
                OemNfcController.setCardEnabled(context, card.uid, true);
                CardSession.setActiveCardId(card.id);
            }
            saveLegacy(false, false, null);
        } catch (Throwable error) {
            OemOperationReport report = OemNfcController.getLastOperationReport();
            OemOperationReport st = report == null ? null : report.stAttempt == null ? report : report.stAttempt;
            if (st != null && st.path == OemOperationReport.Path.MOTOROLA_ST_VENDOR_HCE
                    && Boolean.TRUE.equals(st.rollbackAttempted)) CardSession.clearActiveCard();
            if (card != null && error instanceof OemNfcController.MotorolaFallbackException) {
                NfcActivationPolicy.Path fallback = NfcActivationPolicy.choose(true, false,
                        LegacyNfcDiagnostics.isRawNciApiAvailable(context),
                        LegacyNfcController.isExperimentalAvailable(context), false,
                        Build.FINGERPRINT.equals(settings.getString("experimental_raw_fingerprint", null)));
                if (fallback == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW && !confirmed) {
                    throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.CONSENT, false, error);
                }
                if (fallback == NfcActivationPolicy.Path.CATALOG_RAW
                        || fallback == NfcActivationPolicy.Path.EXPERIMENTAL_RAW
                        || (fallback == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW && confirmed)) {
                    boolean unverified = fallback != NfcActivationPolicy.Path.CATALOG_RAW;
                    rawChange(card, RawOperationReport.Operation.ACTIVATE, unverified, unverified);
                    return;
                }
            }
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, false, error);
        }
    }

    private void xiaomiChange(SavedCard card, OemOperationReport.Operation operation) throws Exception {
        try {
            XiaomiNfcController.change(context, card == null ? null : card.uid, operation);
            CardSession.setActiveCardId(card == null ? null : card.id);
        } catch (Throwable error) {
            CardSession.clearActiveCard();
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, false, error);
        }
    }

    private boolean motorolaRawChange(SavedCard card) throws Exception {
        OemOperationReport report = new OemOperationReport(card == null
                ? OemOperationReport.Operation.DEACTIVATE : OemOperationReport.Operation.ACTIVATE);
        report.notes.add("Model-scoped ST raw compatibility selected; chip identity and reader behavior unverified.");
        OemNfcController.recordOperation(report);
        try {
            boolean applied = MotorolaStNfcController.tryFirst(card != null,
                    () -> MotorolaStNfcController.setRawCardEnabled(context,
                            card == null ? new byte[UidCodec.DEFAULT_UID_LENGTH] : card.uid,
                            card != null, report),
                    () -> MotorolaStNfcController.hasPendingRestore(context), report);
            if (applied) {
                saveLegacy(false, false, null);
                CardSession.setActiveCardId(card == null ? null : card.id);
            } else if (Boolean.TRUE.equals(report.rollbackAttempted)) {
                CardSession.clearActiveCard();
            }
            return applied;
        } catch (Throwable error) {
            if (report.vendorCallSucceeded != null || Boolean.TRUE.equals(report.rollbackAttempted)) {
                CardSession.clearActiveCard();
            }
            report.failure = error.getClass().getSimpleName() + ": " + error.getMessage();
            report.stage = "FAILED";
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, false, error);
        }
    }

    private void rawChange(SavedCard card, RawOperationReport.Operation operation,
            boolean unverified, boolean probeBeforeWrite) throws Exception {
        String previousId = activeId();
        SavedCard previous = QuickCardPolicy.find(cards(), previousId);
        Map<Integer, byte[]> original = null;
        Map<Integer, byte[]> sessionOriginal = null;
        boolean writeAttempted = false;
        try {
            if (unverified) {
                if (card != null && probeBeforeWrite) {
                    original = LegacyNfcDiagnostics.runReadOnlyProbe(context).configuration;
                    if (!LegacyNfcController.isCompleteExperimentalSnapshot(original)) {
                        throw new IllegalStateException("Could not snapshot every NFC parameter before writing");
                    }
                    sessionOriginal = previous == null ? original : loadOriginal();
                } else {
                    original = loadOriginal();
                    sessionOriginal = original;
                }
            }
            boolean alreadyRestored = false;
            if (card == null && unverified
                    && operation == RawOperationReport.Operation.STARTUP_PENDING_RESTORE) {
                LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(context);
                alreadyRestored = LegacyNfcController.configurationEquals(original, probe.configuration);
                if (alreadyRestored) LegacyNfcController.recordAlreadyRestored(operation, probe);
            }
            if (!alreadyRestored) {
                Context pollingContext = pollingContext(unverified);
                if (card != null) saveLegacy(true, unverified, sessionOriginal);
                writeAttempted = true;
                LegacyNfcController.setCardEnabled(pollingContext,
                        card == null ? new byte[UidCodec.DEFAULT_UID_LENGTH] : card.uid,
                        card != null, operation, unverified, original);
            }
            saveLegacy(card != null, unverified, card != null ? sessionOriginal : null);
            CardSession.setActiveCardId(card == null ? null : card.id);
            if (card != null && unverified) settings.edit()
                    .putString("experimental_raw_fingerprint", Build.FINGERPRINT).apply();
        } catch (Throwable primary) {
            if (!writeAttempted && primary instanceof PhoneNfcSession.Failure) {
                throw (PhoneNfcSession.Failure) primary;
            }
            // A failed write has no confirmed active UID until rollback/readback succeeds.
            if (writeAttempted) CardSession.clearActiveCard();
            RawOperationReport primaryReport = writeAttempted ? LegacyNfcController.getLastOperationReport() : null;
            RawOperationReport rollbackReport = null;
            Throwable rollbackFailure = null;
            if (card != null && writeAttempted) {
                try {
                    boolean restorePrevious = !unverified && previous != null;
                    LegacyNfcController.setCardEnabled(pollingContext(unverified),
                            restorePrevious ? previous.uid : new byte[UidCodec.DEFAULT_UID_LENGTH],
                            restorePrevious, RawOperationReport.Operation.ROLLBACK_AFTER_ACTIVATION_FAILURE,
                            unverified, original);
                    rollbackReport = LegacyNfcController.getLastOperationReport();
                    saveLegacy(previousId != null, unverified, previousId != null ? sessionOriginal : null);
                    CardSession.setActiveCardId(previousId);
                } catch (Throwable rollback) {
                    rollbackFailure = rollback;
                    rollbackReport = LegacyNfcController.getLastOperationReport();
                    AppLog.error("Raw NFC activation rollback failed", rollback);
                    try { saveLegacy(true, unverified, sessionOriginal); }
                    catch (Throwable persistence) { rollback.addSuppressed(persistence); }
                }
            }
            // Context acquisition can fail before a new controller report is created.
            if (rollbackReport == primaryReport) rollbackReport = null;
            LegacyNfcController.attachRollback(primaryReport, rollbackReport, rollbackFailure);
            String message = writeAttempted ? LegacyNfcController.lastFailureSummary(primary) : primary.getMessage();
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, true,
                    new IllegalStateException(message, primary));
        }
    }

    private void saveLegacy(boolean pending, boolean unverified, Map<Integer, byte[]> original) {
        SharedPreferences.Editor edit = legacy.edit().putBoolean("pending_restore", pending)
                .putBoolean("pending_unverified", pending && unverified);
        if (pending && unverified && original != null) {
            edit.putString("original_configuration", RawNciCodec.encodeConfiguration(original));
        } else edit.remove("original_configuration");
        if (!edit.commit()) throw new IllegalStateException("Could not retain NFC recovery state");
    }

    private Map<Integer, byte[]> loadOriginal() {
        Map<Integer, byte[]> original = RawNciCodec.decodeConfiguration(
                legacy.getString("original_configuration", null));
        if (!LegacyNfcController.isCompleteExperimentalSnapshot(original)) {
            throw new IllegalStateException("Original NFC configuration snapshot is incomplete");
        }
        return original;
    }
}
