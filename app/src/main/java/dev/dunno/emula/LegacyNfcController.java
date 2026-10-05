package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Build;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Experimental raw NCI fallback. The supported OEM API remains the primary path. */
@SuppressLint("PrivateApi")
final class LegacyNfcController {
    private static final int MESSAGE_TYPE_COMMAND = 1;
    private static final int CORE_GID = 0;
    private static final int CORE_SET_CONFIG_OID = 2;
    private static final int CORE_GET_CONFIG_OID = 3;
    private static final int TRANSPORT_OK = 0;
    private static final long RESPONSE_TIMEOUT_SECONDS = 4;
    private static final long ROUTING_SETTLE_MILLIS = 500;
    private static final long ACTIVITY_RESUME_TIMEOUT_MILLIS = 2_000;
    private static final int[] EXPERIMENTAL_SNAPSHOT_PARAMETERS = {
            0x85, 0x30, 0x31, 0x32, 0x33, 0x58
    };

    private static volatile RawOperationReport lastOperationReport;
    private static volatile RawOperationReport.Candidate activeCandidate;
    private static volatile byte[] uidOnlyBaseline;

    private LegacyNfcController() {}

    static boolean isExperimentalAvailable(Context context) {
        return RawNfcDeviceCatalog.isSupported(Build.MANUFACTURER, Build.MODEL)
                && LegacyNfcDiagnostics.isRawNciApiAvailable(context);
    }

    static String pollingMethod(Context context) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        if (adapter == null) return "UNAVAILABLE";
        PollingControl control = PollingControl.resolve(context, adapter, true);
        return control == null ? "UNAVAILABLE" : control.method;
    }

    static boolean isCompleteExperimentalSnapshot(Map<Integer, byte[]> configuration) {
        if (configuration == null) return false;
        for (int id : EXPERIMENTAL_SNAPSHOT_PARAMETERS) {
            if (configuration.get(id) == null) return false;
        }
        return true;
    }

    static RawOperationReport getLastOperationReport() {
        return lastOperationReport;
    }

    static String lastOperationForDisplay() {
        RawOperationReport report = lastOperationReport;
        return report == null ? "LAST NFC OPERATION\n\nNone yet" : report.formatForDisplay();
    }

    static String lastOperationDetailed() {
        RawOperationReport report = lastOperationReport;
        return report == null ? "LAST NFC OPERATION\n\nNone yet" : report.formatDetailed();
    }

    static String lastFailureSummary(Throwable fallback) {
        RawOperationReport report = lastOperationReport;
        if (report != null && report.primaryFailure != null) {
            return report.shortFailureSummary();
        }
        return describeFailure(fallback);
    }

    static void attachRollback(RawOperationReport primary, RawOperationReport rollback,
            Throwable rollbackFailure) {
        if (primary == null) return;
        primary.rollback = rollback;
        if (rollbackFailure != null) {
            primary.rollbackFailure = describeFailure(rollbackFailure);
        } else if (rollback != null && rollback.primaryFailure != null) {
            primary.rollbackFailure = rollback.primaryFailure;
        }
        lastOperationReport = primary;
    }

    static ActivationResult setCardEnabled(Context context, byte[] uid, boolean enable)
            throws Exception {
        return setCardEnabled(context, uid, enable,
                enable ? RawOperationReport.Operation.ACTIVATE
                        : RawOperationReport.Operation.DEACTIVATE);
    }

    static ActivationResult setCardEnabled(Context context, byte[] uid, boolean enable,
            RawOperationReport.Operation operation) throws Exception {
        return setCardEnabled(context, uid, enable, operation, false, null);
    }

    static ActivationResult setCardEnabled(Context context, byte[] uid, boolean enable,
            RawOperationReport.Operation operation, boolean allowUnverified,
            Map<Integer, byte[]> originalConfiguration) throws Exception {
        RawOperationReport report = new RawOperationReport(operation);
        lastOperationReport = report;
        try {
            if (uid == null || uid.length != CardStore.UID_LENGTH) {
                throw new IllegalArgumentException("UID must contain exactly four bytes");
            }
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
            if (adapter == null) throw new IllegalStateException("NFC is unavailable");
            if (!adapter.isEnabled()) throw new IllegalStateException("NFC is disabled");

            boolean catalogued = RawNfcDeviceCatalog.isSupported(
                    Build.MANUFACTURER, Build.MODEL);
            if (!catalogued && !allowUnverified) {
                throw new IllegalStateException("Raw NCI device is not supported");
            }
            if (allowUnverified && !isCompleteExperimentalSnapshot(originalConfiguration)) {
                throw new IllegalStateException(
                        "Original NFC configuration snapshot is incomplete");
            }
            if (allowUnverified) {
                report.notes.add("Unverified device opt-in for "
                        + Build.MANUFACTURER + " " + Build.MODEL);
            }

            EmulationProfile profile = resolveProfile(allowUnverified);
            PollingControl pollingControl = PollingControl.resolve(
                    context, adapter, allowUnverified);
            report.pollingMethod = pollingControl == null ? "UNAVAILABLE" : pollingControl.method;

            try (RawNciSession session = RawNciSession.open(adapter)) {
                ActivationResult result;
                if (!enable) {
                    result = restoreConfiguration(
                            context, session, pollingControl, operation, report,
                            allowUnverified, originalConfiguration);
                } else if (RawNfcDeviceCatalog.prefersUidOnly(
                        Build.MANUFACTURER, Build.MODEL)) {
                    result = activateUidOnlyThenLegacy(
                            context, session, pollingControl, uid, profile, report);
                } else {
                    report.candidate = RawOperationReport.Candidate.LEGACY_PROFILE;
                    result = activateLegacy(
                            context, session, pollingControl, uid, profile, report);
                }
                report.stage = "COMPLETE";
                return result;
            }
        } catch (Throwable failure) {
            if (report.primaryFailure == null) {
                report.primaryFailure = describeFailure(failure);
            }
            report.stage = "FAILED";
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(report.shortFailureSummary(), failure);
        }
    }

    private static ActivationResult activateUidOnlyThenLegacy(Context context,
            RawNciSession session, PollingControl pollingControl, byte[] uid,
            EmulationProfile profile, RawOperationReport report) throws Exception {
        report.candidate = RawOperationReport.Candidate.UID_ONLY;
        try {
            return activateUidOnly(context, session, pollingControl, uid, report);
        } catch (Throwable uidOnlyFailure) {
            if (uidOnlyFailure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (report.result == null) report.result = classifyUidOnlyFailure(report);
            report.notes.add("UID_ONLY failed: " + describeFailure(uidOnlyFailure));
            if (Boolean.FALSE.equals(report.pauseSucceeded)
                    || Boolean.FALSE.equals(report.resumeSucceeded)) {
                throw asException(uidOnlyFailure);
            }
            if (Boolean.TRUE.equals(report.resumeSucceeded)) {
                Thread.sleep(ROUTING_SETTLE_MILLIS);
            }

            // This is the existing OnePlus behavior, retained as a final compatibility fallback.
            report.candidate = RawOperationReport.Candidate.LEGACY_PROFILE;
            try {
                return activateLegacy(context, session, pollingControl, uid, profile, report);
            } catch (Throwable legacyFailure) {
                if (legacyFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                legacyFailure.addSuppressed(uidOnlyFailure);
                throw asException(legacyFailure);
            }
        }
    }

    private static ActivationResult activateUidOnly(Context context, RawNciSession session,
            PollingControl pollingControl, byte[] uid, RawOperationReport report) throws Exception {
        report.stage = "UID_BEFORE";
        byte[] currentUid = session.readNfcid1(
                report, report.operation,
                RawOperationReport.Candidate.UID_ONLY, "UID_BEFORE");
        report.uidBefore = copy(currentUid);
        if (uidOnlyBaseline == null) uidOnlyBaseline = copy(currentUid);

        boolean paused = executeWithPollingPaused(pollingControl, report, () -> {
            report.stage = "SET_CONFIG";
            session.setConfig(RawNciCodec.uidOnlyPayload(uid), report,
                    report.operation,
                    RawOperationReport.Candidate.UID_ONLY, "SET_WHILE_PAUSED");
            activeCandidate = RawOperationReport.Candidate.UID_ONLY;
            report.stage = "UID_AFTER_SET";
            report.uidAfterSet = session.readNfcid1(
                    report, report.operation,
                    RawOperationReport.Candidate.UID_ONLY, "UID_AFTER_SET");
        });
        if (paused) Thread.sleep(ROUTING_SETTLE_MILLIS);

        report.stage = "UID_AFTER_RESUME";
        report.uidAfterResume = session.readNfcid1(
                report, report.operation,
                RawOperationReport.Candidate.UID_ONLY, "UID_AFTER_RESUME");
        if (!Arrays.equals(uid, report.uidAfterSet)) {
            report.result = "UID_READBACK_MISMATCH";
            throw new IllegalStateException("UID readback after SET did not match the target");
        }

        if (!Arrays.equals(uid, report.uidAfterResume)) {
            report.result = "UID_OVERWRITTEN_AFTER_RESUME";
            report.stage = "POST_RESUME_REAPPLY";
            session.setConfig(RawNciCodec.uidOnlyPayload(uid), report,
                    RawOperationReport.Operation.POST_RESUME_REAPPLY,
                    RawOperationReport.Candidate.UID_ONLY, "POST_RESUME_REAPPLY");
            Thread.sleep(120);
            report.uidAfterReapply = session.readNfcid1(
                    report, RawOperationReport.Operation.POST_RESUME_REAPPLY,
                    RawOperationReport.Candidate.UID_ONLY, "UID_AFTER_REAPPLY");
            if (!Arrays.equals(uid, report.uidAfterReapply)) {
                throw new IllegalStateException(
                        "UID was overwritten after resume and reapply did not retain it");
            }
            report.result = "UID_ONLY_VERIFIED_AFTER_REAPPLY";
        } else {
            report.result = "UID_ONLY_VERIFIED";
        }
        activeCandidate = RawOperationReport.Candidate.UID_ONLY;
        return new ActivationResult(true, context.getString(R.string.legacy_uid_verified));
    }

    private static ActivationResult activateLegacy(Context context, RawNciSession session,
            PollingControl pollingControl, byte[] uid, EmulationProfile profile,
            RawOperationReport report) throws Exception {
        report.candidate = RawOperationReport.Candidate.LEGACY_PROFILE;
        boolean paused = executeWithPollingPaused(pollingControl, report, () -> {
            report.stage = "SET_CONFIG";
            session.setConfig(enabledConfig(uid, profile), report,
                    report.operation,
                    RawOperationReport.Candidate.LEGACY_PROFILE, "SET_WHILE_PAUSED");
            activeCandidate = RawOperationReport.Candidate.LEGACY_PROFILE;
        });
        if (paused) Thread.sleep(ROUTING_SETTLE_MILLIS);

        report.stage = "VERIFY_AFTER_RESUME";
        Verification verification = session.verifyEnabled(uid, profile, report,
                report.operation,
                RawOperationReport.Candidate.LEGACY_PROFILE, "VERIFY_AFTER_RESUME");
        if (!verification.exactProfile) {
            // Preserve the existing single live re-apply, but keep its failure in diagnostics.
            try {
                report.stage = "POST_RESUME_REAPPLY";
                session.setConfig(enabledConfig(uid, profile), report,
                        RawOperationReport.Operation.POST_RESUME_REAPPLY,
                        RawOperationReport.Candidate.LEGACY_PROFILE,
                        "POST_RESUME_REAPPLY");
                Thread.sleep(120);
                verification = session.verifyEnabled(uid, profile, report,
                        RawOperationReport.Operation.POST_RESUME_REAPPLY,
                        RawOperationReport.Candidate.LEGACY_PROFILE,
                        "VERIFY_AFTER_REAPPLY");
            } catch (Throwable liveSetFailure) {
                if (liveSetFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                report.notes.add("Legacy post-resume reapply failed: "
                        + describeFailure(liveSetFailure));
                AppLog.warning("Legacy live profile re-apply was rejected", liveSetFailure);
            }
        }

        if (!verification.uidMatches) {
            report.result = "UID_READBACK_MISMATCH";
            throw new IllegalStateException(
                    "The controller accepted the command but did not retain the UID");
        }
        activeCandidate = RawOperationReport.Candidate.LEGACY_PROFILE;
        if (verification.exactProfile) {
            report.result = "LEGACY_PROFILE_VERIFIED";
            return new ActivationResult(true,
                    context.getString(R.string.legacy_exact_profile_verified));
        }
        report.result = "LEGACY_UID_VERIFIED";
        return new ActivationResult(true, context.getString(R.string.legacy_uid_verified));
    }

    private static ActivationResult restoreConfiguration(Context context, RawNciSession session,
            PollingControl pollingControl, RawOperationReport.Operation operation,
            RawOperationReport report, boolean allowUnverified,
            Map<Integer, byte[]> originalConfiguration) throws Exception {
        if (allowUnverified) {
            report.candidate = RawOperationReport.Candidate.LEGACY_PROFILE;
            boolean paused = executeWithPollingPaused(pollingControl, report, () -> {
                report.stage = "RESTORE_ORIGINAL_CONFIG";
                session.setConfig(RawNciCodec.configPayload(
                                originalConfiguration, EXPERIMENTAL_SNAPSHOT_PARAMETERS),
                        report, operation, RawOperationReport.Candidate.LEGACY_PROFILE,
                        "RESTORE_ORIGINAL_CONFIG");
            });
            if (paused) Thread.sleep(ROUTING_SETTLE_MILLIS);

            report.stage = "VERIFY_ORIGINAL_CONFIG";
            Map<Integer, byte[]> restored = session.readConfiguration(
                    EXPERIMENTAL_SNAPSHOT_PARAMETERS, report, operation,
                    RawOperationReport.Candidate.LEGACY_PROFILE,
                    "VERIFY_ORIGINAL_CONFIG");
            if (!configurationEquals(originalConfiguration, restored)) {
                report.result = "ORIGINAL_CONFIG_RESTORE_MISMATCH";
                throw new IllegalStateException(
                        "Original NFC configuration was not restored exactly");
            }
            report.result = "ORIGINAL_CONFIG_RESTORED";
            activeCandidate = null;
            uidOnlyBaseline = null;
            return new ActivationResult(false,
                    context.getString(R.string.legacy_standard_nfc_restored));
        }

        RawOperationReport.Candidate candidate = activeCandidate;
        if (candidate == null) {
            candidate = RawNfcDeviceCatalog.prefersUidOnly(Build.MANUFACTURER, Build.MODEL)
                    ? RawOperationReport.Candidate.UID_ONLY
                    : RawOperationReport.Candidate.LEGACY_PROFILE;
        }
        report.candidate = candidate;

        if (candidate == RawOperationReport.Candidate.UID_ONLY) {
            try {
                report.uidBefore = session.readNfcid1(
                        report, operation, candidate, "UID_BEFORE_RESTORE");
            } catch (Throwable readFailure) {
                report.notes.add("UID before restore unavailable: "
                        + describeFailure(readFailure));
            }
            byte[] baseline = copy(uidOnlyBaseline);
            byte[] restoreValue = baseline == null ? new byte[0] : baseline;
            boolean paused = executeWithPollingPaused(pollingControl, report, () -> {
                report.stage = "RESTORE_NFCID1";
                session.setConfig(RawNciCodec.nfcid1Payload(restoreValue), report,
                        operation, RawOperationReport.Candidate.UID_ONLY,
                        baseline == null ? "RESET_NFCID1" : "RESTORE_ORIGINAL_NFCID1");
            });
            if (paused) Thread.sleep(ROUTING_SETTLE_MILLIS);

            if (baseline != null) {
                report.uidAfterResume = session.readNfcid1(
                        report, operation, candidate, "UID_AFTER_RESTORE");
                if (!Arrays.equals(baseline, report.uidAfterResume)) {
                    report.result = "UID_RESTORE_MISMATCH";
                    throw new IllegalStateException("Original UID was not restored");
                }
                report.result = "ORIGINAL_UID_RESTORED";
            } else {
                report.result = "UID_ONLY_RESET_ACCEPTED";
                report.notes.add("Original UID was unavailable after process restart; "
                        + "sent only LA_NFCID1 with an empty value");
            }
        } else {
            executeWithPollingPaused(pollingControl, report, () -> {
                report.stage = "RESTORE_DEFAULT_CONFIG";
                session.setConfig(defaultConfig(), report, operation,
                        RawOperationReport.Candidate.LEGACY_PROFILE,
                        "RESTORE_DEFAULT_CONFIG");
            });
            report.result = "LEGACY_DEFAULTS_ACCEPTED";
        }

        activeCandidate = null;
        uidOnlyBaseline = null;
        return new ActivationResult(false,
                context.getString(report.pauseSucceeded != null && report.pauseSucceeded
                        ? R.string.legacy_standard_nfc_restored
                        : R.string.legacy_reset_accepted));
    }

    private static boolean executeWithPollingPaused(PollingControl pollingControl,
            RawOperationReport report, CheckedAction action) throws Exception {
        boolean paused = false;
        Throwable primaryFailure = null;
        try {
            if (pollingControl != null) {
                report.stage = "PAUSE_POLLING";
                try {
                    pollingControl.pause();
                    report.pauseSucceeded = true;
                    paused = true;
                } catch (Throwable pauseFailure) {
                    report.pauseSucceeded = false;
                    report.pauseFailure = describeFailure(pauseFailure);
                    throw pauseFailure;
                }
            } else {
                AppLog.warning("Legacy raw UID test: polling pause API is unavailable; trying live");
            }
            action.run();
        } catch (Throwable failure) {
            primaryFailure = failure;
        }

        if (paused) {
            report.stage = "RESUME_POLLING";
            try {
                pollingControl.resume();
                report.resumeSucceeded = true;
            } catch (Throwable resumeFailure) {
                report.resumeSucceeded = false;
                report.resumeFailure = describeFailure(resumeFailure);
                if (primaryFailure == null) {
                    primaryFailure = resumeFailure;
                } else {
                    primaryFailure.addSuppressed(resumeFailure);
                }
            }
        }
        if (primaryFailure != null) throw asException(primaryFailure);
        return paused;
    }

    private static byte[] enabledConfig(byte[] uid, EmulationProfile profile) {
        return new byte[] {
                6,
                (byte) 0x85, 1, 0,
                0x30, 1, profile.bitFrameSdd,
                0x31, 1, profile.platformConfig,
                0x32, 1, profile.selInfo,
                0x33, 4, uid[0], uid[1], uid[2], uid[3],
                0x58, 1, profile.rats
        };
    }

    private static EmulationProfile resolveProfile(boolean allowUnverified) {
        RawNfcDeviceCatalog.Profile profile = allowUnverified
                ? RawNfcDeviceCatalog.profileForExperimental(
                        Build.MANUFACTURER, Build.MODEL)
                : RawNfcDeviceCatalog.profileFor(Build.MANUFACTURER, Build.MODEL);
        if (profile == RawNfcDeviceCatalog.Profile.PIXEL) {
            return new EmulationProfile((byte) 0x04, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00);
        }
        return new EmulationProfile((byte) 0x06, (byte) 0x0C,
                (byte) 0x20, (byte) 0x40);
    }

    private static byte[] defaultConfig() {
        return new byte[] {
                4,
                (byte) 0x85, 1, 1,
                0x33, 0,
                0x58, 1, 0,
                0x59, 0
        };
    }

    static final class ActivationResult {
        final boolean enabled;
        final String summary;

        ActivationResult(boolean enabled, String summary) {
            this.enabled = enabled;
            this.summary = summary;
        }
    }

    private static final class Verification {
        final boolean uidMatches;
        final boolean exactProfile;

        Verification(boolean uidMatches, boolean exactProfile) {
            this.uidMatches = uidMatches;
            this.exactProfile = exactProfile;
        }
    }

    private static final class EmulationProfile {
        final byte bitFrameSdd;
        final byte platformConfig;
        final byte selInfo;
        final byte rats;

        EmulationProfile(byte bitFrameSdd, byte platformConfig, byte selInfo, byte rats) {
            this.bitFrameSdd = bitFrameSdd;
            this.platformConfig = platformConfig;
            this.selInfo = selInfo;
            this.rats = rats;
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private abstract static class PollingControl {
        final String method;

        PollingControl(String method) {
            this.method = method;
        }

        static PollingControl resolve(Context context, NfcAdapter adapter) {
            return resolve(context, adapter, false);
        }

        static PollingControl resolve(Context context, NfcAdapter adapter,
                boolean allowUnverified) {
            if (RawNfcDeviceCatalog.prefersDiscoveryTechnologyControl(
                    Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL)
                    && context instanceof Activity) {
                return new DiscoveryTechnologyPollingControl(adapter, (Activity) context);
            }

            try {
                Method extensionMethod = findMethod(adapter.getClass(), "getNfcOemExtension");
                Object extension = invoke(adapter, extensionMethod);
                if (extension != null) {
                    Method pause = findMethod(extension.getClass(), "pausePolling", long.class);
                    Method resume = findMethod(extension.getClass(), "resumePolling");
                    return new MethodPollingControl("OEM_EXTENSION",
                            extension, pause, resume, 40_000L);
                }
            } catch (Throwable ignored) {
                // Continue to the public or legacy adapter fallback.
            }

            if (RawNfcDeviceCatalog.shouldUseDiscoveryTechnologyFallback(
                    Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL,
                    allowUnverified)
                    && context instanceof Activity) {
                return new DiscoveryTechnologyPollingControl(adapter, (Activity) context);
            }

            try {
                Method pause = findMethod(adapter.getClass(), "pausePolling", int.class);
                Method resume = findMethod(adapter.getClass(), "resumePolling");
                return new MethodPollingControl("HIDDEN_ADAPTER",
                        adapter, pause, resume, 40_000);
            } catch (Throwable ignored) {
                return null;
            }
        }

        abstract void pause() throws Exception;

        abstract void resume() throws Exception;
    }

    private static final class MethodPollingControl extends PollingControl {
        final Object receiver;
        final Method pauseMethod;
        final Method resumeMethod;
        final Object pauseArgument;

        MethodPollingControl(String method, Object receiver, Method pauseMethod,
                Method resumeMethod, Object pauseArgument) {
            super(method);
            this.receiver = receiver;
            this.pauseMethod = pauseMethod;
            this.resumeMethod = resumeMethod;
            this.pauseArgument = pauseArgument;
        }

        @Override
        void pause() throws Exception {
            Object result = invoke(receiver, pauseMethod, pauseArgument);
            checkStateChangeResult("pause", result);
        }

        @Override
        void resume() throws Exception {
            Object result = invoke(receiver, resumeMethod);
            checkStateChangeResult("resume", result);
        }

        private static void checkStateChangeResult(String operation, Object result) {
            if (result instanceof Integer && (Integer) result != 1 && (Integer) result != 2) {
                throw new IllegalStateException(
                        "Could not " + operation + " NFC polling: " + result);
            }
        }
    }

    private static final class DiscoveryTechnologyPollingControl extends PollingControl {
        final NfcAdapter adapter;
        final Activity activity;

        DiscoveryTechnologyPollingControl(NfcAdapter adapter, Activity activity) {
            super("DISCOVERY_TECHNOLOGY");
            this.adapter = adapter;
            this.activity = activity;
        }

        @Override
        void pause() throws Exception {
            waitUntilResumed();
            adapter.setDiscoveryTechnology(activity,
                    NfcAdapter.FLAG_READER_DISABLE,
                    NfcAdapter.FLAG_LISTEN_DISABLE);
            // updateDiscoveryTechnology applies routing asynchronously on some OEM stacks.
            // Do not race the following CORE_SET_CONFIG against that transition.
            Thread.sleep(ROUTING_SETTLE_MILLIS);
            AppLog.info("NFC polling paused with the Android 15 discovery API");
        }

        @Override
        void resume() {
            adapter.resetDiscoveryTechnology(activity);
            AppLog.info("NFC discovery restored with the Android 15 discovery API");
        }

        private void waitUntilResumed() throws Exception {
            long deadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(ACTIVITY_RESUME_TIMEOUT_MILLIS);
            while (!activity.hasWindowFocus()) {
                if (activity.isFinishing() || activity.isDestroyed()
                        || System.nanoTime() >= deadlineNanos) {
                    throw new IllegalStateException(
                            "The app must be in the foreground to pause NFC discovery");
                }
                Thread.sleep(20);
            }
        }
    }

    private static final class RawNciSession implements AutoCloseable {
        final NfcAdapter adapter;
        final Method sendMethod;
        final Method unregisterMethod;
        final Object callback;

        volatile int expectedGid = -1;
        volatile int expectedOid = -1;
        volatile byte[] responsePayload;
        volatile CountDownLatch responseLatch;
        volatile RawOperationReport.RequestRecord activeRequest;

        private RawNciSession(NfcAdapter adapter, Method sendMethod,
                Method unregisterMethod, Object callback) {
            this.adapter = adapter;
            this.sendMethod = sendMethod;
            this.unregisterMethod = unregisterMethod;
            this.callback = callback;
        }

        static RawNciSession open(NfcAdapter adapter) throws Exception {
            Class<?> callbackClass = Class.forName(
                    "android.nfc.NfcAdapter$NfcVendorNciCallback");
            Method sendMethod = findMethod(adapter.getClass(), "sendVendorNciMessage",
                    int.class, int.class, int.class, byte[].class);
            Method registerMethod = findMethod(adapter.getClass(),
                    "registerNfcVendorNciCallback", Executor.class, callbackClass);
            Method unregisterMethod = findMethod(adapter.getClass(),
                    "unregisterNfcVendorNciCallback", callbackClass);

            RawNciSession[] holder = new RawNciSession[1];
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(method.getName())) return "LegacyRawNciCallback";
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return arguments != null && arguments.length == 1
                                && proxy == arguments[0];
                    }
                }
                RawNciSession session = holder[0];
                if (session != null && "onVendorNciResponse".equals(method.getName())
                        && arguments != null && arguments.length == 3) {
                    int gid = (Integer) arguments[0];
                    int oid = (Integer) arguments[1];
                    byte[] payload = (byte[]) arguments[2];
                    CountDownLatch latch = session.responseLatch;
                    if (latch != null && gid == session.expectedGid && oid == session.expectedOid) {
                        session.responsePayload = payload == null
                                ? new byte[0] : Arrays.copyOf(payload, payload.length);
                        latch.countDown();
                    } else if (latch != null && session.activeRequest != null) {
                        session.activeRequest.unexpectedResponse = String.format(Locale.US,
                                "GID 0x%02X / OID 0x%02X / RX %s",
                                gid, oid, RawNciCodec.hex(payload));
                    }
                }
                return null;
            };
            Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                    new Class<?>[] {callbackClass}, handler);
            RawNciSession session = new RawNciSession(
                    adapter, sendMethod, unregisterMethod, callback);
            holder[0] = session;
            invoke(adapter, registerMethod, (Executor) Runnable::run, callback);
            return session;
        }

        synchronized byte[] send(int oid, byte[] payload,
                RawOperationReport.RequestRecord request) throws Exception {
            expectedGid = CORE_GID;
            expectedOid = oid;
            responsePayload = null;
            responseLatch = new CountDownLatch(1);
            activeRequest = request;
            long startNanos = System.nanoTime();
            try {
                Object result = invoke(adapter, sendMethod,
                        MESSAGE_TYPE_COMMAND, CORE_GID, oid,
                        Arrays.copyOf(payload, payload.length));
                if (!(result instanceof Integer)) {
                    request.failure = "unexpected framework return: " + result;
                    throw new IllegalStateException(request.failure);
                }
                request.frameworkStatus = (Integer) result;
                if (request.frameworkStatus != TRANSPORT_OK) {
                    request.failure = "framework "
                            + RawNciCodec.frameworkStatusName(request.frameworkStatus);
                    throw new IllegalStateException(request.failure);
                }
                if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    request.failure = "timeout waiting for controller response";
                    throw new IllegalStateException(request.failure);
                }
                request.rx = responsePayload == null
                        ? new byte[0] : Arrays.copyOf(responsePayload, responsePayload.length);
                if (request.rx.length > 0) {
                    request.controllerStatus = request.rx[0] & 0xFF;
                    if (oid == CORE_SET_CONFIG_OID && request.controllerStatus != 0) {
                        request.reportedParameterIds =
                                RawNciCodec.parseRejectedParameterIds(request.rx);
                    }
                }
                return Arrays.copyOf(request.rx, request.rx.length);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (request.failure == null) request.failure = "interrupted";
                throw interrupted;
            } catch (Exception failure) {
                if (request.failure == null) request.failure = describeFailure(failure);
                throw failure;
            } finally {
                request.durationMillis = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startNanos);
                expectedGid = -1;
                expectedOid = -1;
                responseLatch = null;
                activeRequest = null;
            }
        }

        void setConfig(byte[] payload, RawOperationReport report,
                RawOperationReport.Operation operation,
                RawOperationReport.Candidate candidate, String stage) throws Exception {
            RawOperationReport.RequestRecord request = report.addRequest(
                    operation, candidate, stage, "SET_CONFIG", payload);
            byte[] response = send(CORE_SET_CONFIG_OID, payload, request);
            requireControllerSuccess("CORE_SET_CONFIG", response, request);
        }

        byte[] readNfcid1(RawOperationReport report,
                RawOperationReport.Operation operation,
                RawOperationReport.Candidate candidate, String stage) throws Exception {
            Map<Integer, byte[]> values = getConfig(
                    new byte[] {1, 0x33}, report, operation, candidate, stage);
            byte[] value = values.get(0x33);
            if (value == null) throw new IllegalStateException("GET_CONFIG omitted LA_NFCID1");
            return copy(value);
        }

        Verification verifyEnabled(byte[] uid, EmulationProfile profile,
                RawOperationReport report, RawOperationReport.Operation operation,
                RawOperationReport.Candidate candidate, String stage) throws Exception {
            Map<Integer, byte[]> values = getConfig(
                    new byte[] {5, 0x30, 0x31, 0x32, 0x33, 0x58},
                    report, operation, candidate, stage);
            boolean uidMatches = Arrays.equals(uid, values.get(0x33));
            boolean exactProfile = valueEquals(values, 0x30, profile.bitFrameSdd)
                    && valueEquals(values, 0x31, profile.platformConfig)
                    && valueEquals(values, 0x32, profile.selInfo)
                    && valueEquals(values, 0x58, profile.rats);
            AppLog.info("Legacy raw UID verification: uidMatches=" + uidMatches
                    + ", exactProfile=" + exactProfile);
            return new Verification(uidMatches, exactProfile);
        }

        Map<Integer, byte[]> readConfiguration(int[] parameterIds,
                RawOperationReport report, RawOperationReport.Operation operation,
                RawOperationReport.Candidate candidate, String stage) throws Exception {
            byte[] payload = new byte[parameterIds.length + 1];
            payload[0] = (byte) parameterIds.length;
            for (int i = 0; i < parameterIds.length; i++) {
                payload[i + 1] = (byte) parameterIds[i];
            }
            return getConfig(payload, report, operation, candidate, stage);
        }

        private Map<Integer, byte[]> getConfig(byte[] payload, RawOperationReport report,
                RawOperationReport.Operation operation,
                RawOperationReport.Candidate candidate, String stage) throws Exception {
            RawOperationReport.RequestRecord request = report.addRequest(
                    operation, candidate, stage, "GET_CONFIG", payload);
            byte[] response = send(CORE_GET_CONFIG_OID, payload, request);
            requireControllerSuccess("CORE_GET_CONFIG", response, request);
            try {
                return RawNciCodec.parseGetConfigResponse(response);
            } catch (IllegalArgumentException malformed) {
                request.failure = "malformed GET_CONFIG response: " + malformed.getMessage();
                throw new IllegalStateException(request.failure, malformed);
            }
        }

        @Override
        public void close() {
            try {
                invoke(adapter, unregisterMethod, callback);
            } catch (Throwable t) {
                AppLog.warning("Unable to unregister legacy raw NCI callback", t);
            }
        }

        private static void requireControllerSuccess(String operation, byte[] response,
                RawOperationReport.RequestRecord request) {
            if (response == null || response.length == 0) {
                request.failure = "empty controller response";
                throw new IllegalStateException(operation + ": " + request.failure);
            }
            int status = response[0] & 0xFF;
            if (status != 0) {
                request.failure = String.format(Locale.US, "%s (0x%02X)",
                        RawNciCodec.nciStatusName(status), status);
                throw new IllegalStateException(operation + " rejected by controller: "
                        + request.failure);
            }
        }

        private static boolean valueEquals(Map<Integer, byte[]> values, int id, byte expected) {
            byte[] value = values.get(id);
            return value != null && value.length == 1 && value[0] == expected;
        }
    }

    private static String classifyUidOnlyFailure(RawOperationReport report) {
        for (int i = report.requests.size() - 1; i >= 0; i--) {
            RawOperationReport.RequestRecord request = report.requests.get(i);
            if (request.candidate == RawOperationReport.Candidate.UID_ONLY
                    && "SET_CONFIG".equals(request.command) && request.failure != null) {
                return "SET_REJECTED";
            }
        }
        return "UID_READBACK_MISMATCH";
    }

    private static boolean configurationEquals(
            Map<Integer, byte[]> expected, Map<Integer, byte[]> actual) {
        for (int id : EXPERIMENTAL_SNAPSHOT_PARAMETERS) {
            if (!Arrays.equals(expected.get(id), actual.get(id))) return false;
        }
        return true;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static Exception asException(Throwable failure) {
        return failure instanceof Exception
                ? (Exception) failure : new IllegalStateException(describeFailure(failure), failure);
    }

    private static String describeFailure(Throwable failure) {
        if (failure == null) return "unknown failure";
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException publicLookupFailed) {
            return owner.getDeclaredMethod(name, parameterTypes);
        }
    }

    private static Object invoke(Object receiver, Method method, Object... arguments)
            throws Exception {
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
            // Public System APIs remain invokable even if hidden API policy rejects this call.
        }
        return method.invoke(receiver, arguments);
    }
}
