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
    private final SharedPreferences legacy;
    private final SharedPreferences settings;
    private final NfcUiContext<Activity> pollingUi;

    PhoneNfcBackend(Context context, NfcUiContext<Activity> pollingUi) {
        this.context = context.getApplicationContext();
        this.pollingUi = pollingUi;
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
                || OppoNxpRawController.hasPendingRestore(context)
                || OnePlusRawRecovery.hasPendingRestore(context)
                || OnePlusVendorNfcController.hasPendingRestore(context)
                || XiaomiNfcController.hasPendingRestore(context)
                || StPhoneNfcController.hasPendingRestore(context)
                || HonorStNfcController.hasPendingRestore(context);
    }

    private boolean permission() {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private NfcActivationPolicy.Path path(boolean activating) { return path(activating, false); }

    private NfcActivationPolicy.Path path(boolean activating, boolean confirmedRawFallback) {
        // In normal mode a missing ST baseline must end the attempt before any writes.
        if (StPhoneNfcController.hasPendingRestore(context)
                || (StPhoneNfcController.targets() && !settings.getBoolean("force_raw_mode", false))
                || HonorStNfcController.hasPendingRestore(context)
                || MotorolaStNfcController.hasPendingRestore(context)
                || OnePlusVendorNfcController.hasPendingRestore(context)) {
            return NfcActivationPolicy.Path.OEM;
        }
        return NfcActivationPolicy.choose(activating,
                !requiresRawTrial()
                        && OemNfcController.isUidEmulationSupported(context, !confirmedRawFallback),
                LegacyNfcDiagnostics.isRawNciApiAvailable(context),
                LegacyNfcController.isExperimentalAvailable(context),
                settings.getBoolean("force_raw_mode", false),
                Build.FINGERPRINT.equals(settings.getString("experimental_raw_fingerprint", null)));
    }

    private boolean requiresRawTrial() {
        return NfcActivationPolicy.requiresRawTrial(Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT);
    }

    private boolean useXiaomi() {
        return XiaomiNfcController.hasPendingRestore(context)
                || (XiaomiNfcController.isTargetDevice()
                        && (settings.getBoolean("force_raw_mode", false)
                            || !OemNfcController.isUidEmulationSupported(context)));
    }

    private boolean useMotorolaRaw() {
        if (OnePlusVendorNfcController.hasPendingRestore(context)) return false;
        boolean pending = MotorolaStNfcController.hasPendingRestore(context);
        boolean targeted = MotorolaStNfcController.isRawModeTarget();
        if (!pending && !targeted) return false;
        boolean forced = settings.getBoolean("force_raw_mode", false);
        return MotorolaStNfcController.useRawMode(targeted, forced,
                !pending && !forced && OemNfcController.isUidEmulationSupported(context), pending);
    }

    private boolean useOnePlusRawRecovery() {
        if (!OnePlusRawRecovery.isTarget()) return false;
        if (legacy.getBoolean("pending_restore", false) || XiaomiNfcController.hasPendingRestore(context)
                || MotorolaStNfcController.hasPendingRestore(context)
                || HonorStNfcController.hasPendingRestore(context)) return false;
        return OnePlusRawRecovery.selected(context, settings.getBoolean("force_raw_mode", false));
    }

    @Override public PhoneNfcSession.Reason preflight(SavedCard card) {
        PhoneNfcSession.Reason unavailable = readiness();
        if (unavailable != null) return unavailable;
        if (useOnePlusRawRecovery() || OnePlusRawRecovery.hasPendingRestore(context)) {
            return pollingUi.isResumed() ? null : PhoneNfcSession.Reason.FOREGROUND_REQUIRED;
        }
        if (card == null && recoveryPending()) {
            boolean foregroundRequired = (legacy.getBoolean("uid_only_session", false) && !pollingUi.isResumed())
                    || (legacy.getBoolean("pending_unverified", false) && needsForeground());
            return foregroundRequired ? PhoneNfcSession.Reason.FOREGROUND_REQUIRED : null;
        }
        if (useXiaomi()) return null;
        if (useMotorolaRaw()) return null;
        NfcActivationPolicy.Path path = path(card != null);
        if (path == NfcActivationPolicy.Path.UNSUPPORTED) return PhoneNfcSession.Reason.UNSUPPORTED;
        if (path == NfcActivationPolicy.Path.CATALOG_RAW && useOnePlus12UidSnapshot(legacy) && !pollingUi.isResumed()) {
            return PhoneNfcSession.Reason.FOREGROUND_REQUIRED;
        }
        if ((path == NfcActivationPolicy.Path.EXPERIMENTAL_RAW
                || path == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW) && needsForeground()) {
            return PhoneNfcSession.Reason.FOREGROUND_REQUIRED;
        }
        if (path == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW) return PhoneNfcSession.Reason.CONSENT;
        return null;
    }

    static boolean useOnePlus12UidSnapshot(SharedPreferences legacy) {
        return OnePlusLegacyRecovery.useUidSnapshot(Build.VERSION.SDK_INT, Build.FINGERPRINT,
                legacy.getBoolean("pending_restore", false), legacy.getBoolean("uid_only_session", false));
    }

    private boolean needsForeground() {
        return !pollingUi.isResumed()
                && "UNAVAILABLE".equals(LegacyNfcController.pollingMethod(context));
    }

    private Context pollingContext(boolean unverified, boolean uidOnly) throws Exception {
        Activity activity = pollingUi.acquire(2_000);
        if (uidOnly && activity == null) throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FOREGROUND_REQUIRED);
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
        if (OppoNxpRawController.hasPendingRestore(context)) {
            requireReady();
            oppoRawChange(null, RawOperationReport.Operation.STARTUP_PENDING_RESTORE);
            return true;
        }
        if (OnePlusRawRecovery.hasPendingRestore(context)) {
            requireReady();
            onePlusRawChange(null);
            return true;
        }
        if (useOnePlusRawRecovery()) {
            requireReady();
            OnePlusRawRecovery.prepare(new OnePlusRawRecovery.AndroidPlatform(context, context));
            return true;
        }
        if (XiaomiNfcController.hasPendingRestore(context)) {
            requireReady();
            xiaomiChange(null, OemOperationReport.Operation.STARTUP_RESTORE);
            return true;
        }
        if (useXiaomi()) {
            // No startup reset without our own marker; another app may own a transit override.
            return readiness() == null;
        }
        if (OnePlusAndroidRecovery.selected(context)) {
            requireReady();
            OnePlusAndroidRecovery.prepare(context);
            // Archiving sends no NFC writes. The next card action selects its normal route.
            return true;
        }
        boolean vendorPending = StPhoneNfcController.hasPendingRestore(context)
                || OnePlusVendorNfcController.hasPendingRestore(context)
                || MotorolaStNfcController.hasPendingRestore(context)
                || HonorStNfcController.hasPendingRestore(context);
        if (vendorPending) requireReady();
        if (readiness() != null) return false;
        if (!NfcActivationPolicy.shouldRestoreOemAtStartup(vendorPending,
                OemNfcController.isUidEmulationSupported(context), requiresRawTrial())) {
            // A ready raw-only session needs no OEM reset. Completing initialization
            // prevents the next toggle from clearing its live active-card state.
            return true;
        }
        try {
            OemNfcController.restoreDefaults(context, OemOperationReport.Operation.STARTUP_RESTORE);
            saveLegacy(false, false, null, false);
            return true;
        } catch (Throwable error) {
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, false, error);
        }
    }

    private PhoneNfcSession.Reason readiness() {
        // The process can start before the NFC service returns an adapter after boot.
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        if (adapter == null) return PhoneNfcSession.Reason.NO_NFC;
        if (!adapter.isEnabled()) return PhoneNfcSession.Reason.DISABLED;
        return permission() ? null : PhoneNfcSession.Reason.PERMISSION;
    }

    private void requireReady() throws PhoneNfcSession.Failure {
        PhoneNfcSession.Reason unavailable = readiness();
        if (unavailable != null) throw new PhoneNfcSession.Failure(unavailable);
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
        if (OppoNxpRawController.hasPendingRestore(context)
                && (card == null || !OppoNxpRawController.targets())) {
            oppoRawChange(null, RawOperationReport.Operation.DEACTIVATE);
            if (card == null) return;
        }
        if (OnePlusRawRecovery.hasPendingRestore(context) && !useOnePlusRawRecovery()) {
            onePlusRawChange(null);
            if (card == null) return;
        }
        if (useOnePlusRawRecovery()) {
            onePlusRawChange(card);
            return;
        }
        if (useXiaomi()) {
            if (xiaomiChange(card, card == null ? OemOperationReport.Operation.DEACTIVATE
                    : OemOperationReport.Operation.ACTIVATE)) return;
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
        NfcActivationPolicy.Path path = path(card != null, confirmed);
        if (path == NfcActivationPolicy.Path.OEM && OppoNxpRawController.hasPendingRestore(context)) {
            oppoRawChange(null, RawOperationReport.Operation.DEACTIVATE);
        }
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
            OnePlusAndroidRecovery.observe(context, OemNfcController.getLastOperationReport());
            saveLegacy(false, false, null, false);
        } catch (Throwable error) {
            OemOperationReport report = OemNfcController.getLastOperationReport();
            OemOperationReport st = report == null ? null : report.stAttempt == null ? report : report.stAttempt;
            if (st != null && st.path == OemOperationReport.Path.ST_VENDOR_HCE) CardSession.clearActiveCard();
            if (st != null && (st.path == OemOperationReport.Path.MOTOROLA_ST_VENDOR_HCE
                    || st.path == OemOperationReport.Path.HONOR_ST_VENDOR_HCE)
                    && Boolean.TRUE.equals(st.rollbackAttempted)) CardSession.clearActiveCard();
            if (card != null && (error instanceof OemNfcController.MotorolaFallbackException
                    || error instanceof OemNfcController.HonorFallbackException
                    || error instanceof OemNfcController.OnePlusFallbackException)) {
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

    private void onePlusRawChange(SavedCard card) throws Exception {
        Activity foreground = pollingUi.acquire(2_000);
        if (foreground == null) throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FOREGROUND_REQUIRED);
        OnePlusRawRecovery.AndroidPlatform platform = new OnePlusRawRecovery.AndroidPlatform(context, foreground);
        try {
            OnePlusRawRecovery.change(platform, card == null ? null : card.uid);
            CardSession.setActiveCardId(card == null ? null : card.id);
        } catch (Throwable error) {
            if (platform.writeAttempted) CardSession.clearActiveCard();
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, true, error);
        }
    }

    private boolean xiaomiChange(SavedCard card, OemOperationReport.Operation operation) throws Exception {
        try {
            XiaomiNfcController.change(context, card == null ? null : card.uid, operation);
            CardSession.setActiveCardId(card == null ? null : card.id);
            return true;
        } catch (Throwable error) {
            OemOperationReport report = OemNfcController.getLastOperationReport();
            if (XiaomiNfcController.permitsRawFallback(context, card != null, report)) {
                report.notes.add("Tanzanite NXP candidate unavailable before any setter; normal raw consent policy applies.");
                return false;
            }
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
                saveLegacy(false, false, null, false);
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
        if (card == null && OnePlusLegacyRecovery.oldRecord(Build.MODEL, Build.FINGERPRINT,
                legacy.getBoolean("pending_restore", false), unverified,
                legacy.contains("catalog_snapshot_firmware"))) {
            recoverOnePlusLegacyRecord(operation);
            return;
        }
        if (card != null && unverified && OppoNxpRawController.targets()
                && (OppoNxpRawController.hasPendingRestore(context) || OppoNxpRawController.available(context))) {
            // The existing per-firmware raw consent has already been checked by changeController.
            // Finish any older generic transaction before creating a separate RF recovery record.
            if (legacy.getBoolean("pending_restore", false)) {
                rawChange(null, RawOperationReport.Operation.STARTUP_PENDING_RESTORE,
                        legacy.getBoolean("pending_unverified", false), false);
            }
            oppoRawChange(card, operation);
            return;
        }
        boolean uidOnly = !unverified && useOnePlus12UidSnapshot(legacy);
        if (uidOnly && card == null && !legacy.getBoolean("pending_restore", false)) return;
        String previousId = activeId();
        SavedCard previous = QuickCardPolicy.find(cards(), previousId);
        Map<Integer, byte[]> original = null;
        Map<Integer, byte[]> sessionOriginal = null;
        boolean writeAttempted = false;
        try {
            if (!unverified) {
                try {
                    original = OnePlusLegacyRecovery.baseline(Build.MODEL, Build.FINGERPRINT,
                            legacy.getString("catalog_snapshot_firmware", null),
                            legacy.getBoolean("pending_restore", false), card != null, uidOnly, this::loadOriginal,
                            () -> LegacyNfcDiagnostics.runReadOnlyProbe(context).configuration);
                    if (uidOnly) {
                        if (original == null || original.get(0x59) == null) {
                            throw new IllegalStateException("The complete seven-field NFC snapshot is required");
                        }
                        if (card != null) OnePlusVendorNfcController.requireIdleAccessCard(context);
                    } else if (card != null && OnePlusLegacyRecovery.isOnePlus12Android16(Build.FINGERPRINT)) {
                        OnePlusLegacyRecovery.snapshotBitFrameSdd(original);
                    }
                } catch (Exception failure) {
                    RawOperationReport report = new RawOperationReport(operation);
                    if (uidOnly) report.candidate = RawOperationReport.Candidate.UID_ONLY;
                    report.stage = "CHECK_RECOVERY_SNAPSHOT";
                    LegacyNfcController.recordPreparationFailure(report, failure);
                    throw failure;
                }
                sessionOriginal = original;
            } else {
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
                Context pollingContext = pollingContext(unverified, uidOnly);
                if (card != null) saveLegacy(true, unverified, sessionOriginal, uidOnly);
                writeAttempted = true;
                LegacyNfcController.setCardEnabled(pollingContext,
                        card == null ? new byte[UidCodec.DEFAULT_UID_LENGTH] : card.uid,
                        card != null, operation, unverified, original, uidOnly);
            }
            if (card == null || !uidOnly) saveLegacy(card != null, unverified, sessionOriginal, uidOnly);
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
                    boolean restorePrevious = !uidOnly && !unverified && previous != null;
                    LegacyNfcController.setCardEnabled(pollingContext(unverified, uidOnly),
                            restorePrevious ? previous.uid : new byte[UidCodec.DEFAULT_UID_LENGTH],
                            restorePrevious, RawOperationReport.Operation.ROLLBACK_AFTER_ACTIVATION_FAILURE,
                            unverified, original, uidOnly);
                    rollbackReport = LegacyNfcController.getLastOperationReport();
                    String restoredId = uidOnly ? null : previousId;
                    saveLegacy(restoredId != null, unverified, sessionOriginal, uidOnly);
                    CardSession.setActiveCardId(restoredId);
                } catch (Throwable rollback) {
                    rollbackFailure = rollback;
                    rollbackReport = LegacyNfcController.getLastOperationReport();
                    AppLog.error("Raw NFC activation rollback failed", rollback);
                    try { saveLegacy(true, unverified, sessionOriginal, uidOnly); }
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

    @Override public boolean flagOnlyBootCheckPending() {
        return OnePlusLegacyRecovery.oldRecord(Build.MODEL, Build.FINGERPRINT,
                legacy.getBoolean("pending_restore", false), legacy.getBoolean("pending_unverified", false),
                legacy.contains("catalog_snapshot_firmware")) && !legacy.contains("original_configuration");
    }

    @Override public void checkFlagOnlyBootRecord() throws Exception {
        try {
            requireReady();
        } catch (PhoneNfcSession.Failure failure) {
            RawOperationReport report = new RawOperationReport(RawOperationReport.Operation.STARTUP_PENDING_RESTORE);
            report.stage = "CHECK_FOREGROUND_PREREQUISITES";
            LegacyNfcController.recordPreparationFailure(report, failure);
            throw failure;
        }
        recoverOnePlusLegacyRecord(RawOperationReport.Operation.STARTUP_PENDING_RESTORE);
    }

    private void recoverOnePlusLegacyRecord(RawOperationReport.Operation operation) throws Exception {
        RawOperationReport report = new RawOperationReport(operation);
        report.candidate = RawOperationReport.Candidate.LEGACY_PROFILE;
        report.stage = "CHECK_LEGACY_RECORD";
        try {
            if (legacy.contains("original_configuration")) {
                throw new IllegalStateException("Unidentified saved NFC configuration. Recovery was left pending.");
            }
            OnePlusLegacyRecovery profile = OnePlusLegacyRecovery.forDevice(Build.MODEL, Build.FINGERPRINT);
            if (profile == null) throw new IllegalStateException("Unrecognized recovery firmware");
            int boot = android.provider.Settings.Global.getInt(context.getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT, -1);
            int firstBoot = legacy.getInt(profile.checkpointKey, -1);
            if (firstBoot == -1 && boot >= 0) {
                if (!legacy.edit().putInt(profile.checkpointKey, boot).commit()) {
                    throw new IllegalStateException("Could not save the restart checkpoint.");
                }
                firstBoot = boot;
            }
            OnePlusLegacyRecovery.checkAfterRestart(firstBoot, boot, report, () -> {
                LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(context);
                if (probe.report != null) report.requests.addAll(probe.report.requests);
                if (probe.report == null || !"OK".equals(probe.report.result)) {
                    throw new IllegalStateException("Could not read every NFC setting. Recovery was left pending.");
                }
                return probe.configuration;
            });
            // The old format stored only the pending flag. Preserve it as an archive and
            // retire it atomically. It cannot establish the user's pre-activation values.
            if (!legacy.edit().putString(profile.archiveKey, Build.FINGERPRINT)
                    .putBoolean("pending_restore", false).remove(profile.checkpointKey).commit()) {
                legacy.edit().putBoolean("pending_restore", true)
                        .putInt(profile.checkpointKey, firstBoot).apply();
                throw new IllegalStateException("Could not archive the pending NFC record.");
            }
            report.result = "OLD_RECORD_ARCHIVED_AFTER_RESTART";
            report.stage = "COMPLETE";
            report.notes.add("The seven observed settings match the reviewed post-restart check. "
                    + "Original pre-activation values were unavailable. No controller write was issued.");
            CardSession.clearActiveCard();
        } catch (Exception failure) {
            report.primaryFailure = failure.getMessage();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, true, failure);
        } finally {
            LegacyNfcController.recordOperation(report);
        }
    }

    private void saveLegacy(boolean pending, boolean unverified, Map<Integer, byte[]> original, boolean uidOnly) {
        SharedPreferences.Editor edit = legacy.edit()
                .putBoolean("pending_restore", pending)
                .putBoolean("pending_unverified", pending && unverified);
        if (pending && uidOnly) edit.putBoolean("uid_only_session", true);
        else edit.remove("uid_only_session");
        OnePlusLegacyRecovery profile = OnePlusLegacyRecovery.forDevice(Build.MODEL, Build.FINGERPRINT);
        if (profile != null) edit.remove(profile.checkpointKey);
        if (pending && original != null) {
            edit.putString("original_configuration", RawNciCodec.encodeConfiguration(original));
        } else edit.remove("original_configuration");
        if (pending && !unverified && original != null) {
            edit.putString("catalog_snapshot_firmware", Build.FINGERPRINT);
        } else edit.remove("catalog_snapshot_firmware");
        if (!edit.commit()) {
            // SharedPreferences can change its in-memory map even when a disk commit fails.
            if (!pending && uidOnly && original != null) saveLegacy(true, false, original, true);
            throw new IllegalStateException("Could not retain NFC recovery state");
        }
    }

    private void oppoRawChange(SavedCard card, RawOperationReport.Operation operation) throws Exception {
        try {
            OppoNxpRawController.change(context, card == null ? null : card.uid, operation);
            CardSession.setActiveCardId(card == null ? null : card.id);
            if (card != null) settings.edit().putString("experimental_raw_fingerprint", Build.FINGERPRINT).apply();
        } catch (Exception failure) {
            RawOperationReport report = LegacyNfcController.getLastOperationReport();
            if (report != null && report.requests.stream().anyMatch(r -> "SET_CONFIG".equals(r.command))) {
                CardSession.clearActiveCard();
            }
            throw new PhoneNfcSession.Failure(PhoneNfcSession.Reason.FAILED, true, failure);
        }
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
