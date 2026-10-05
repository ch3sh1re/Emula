package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.nfc.NfcAdapter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Read-only raw NCI checks. They remain available without device-catalog membership. */
@SuppressLint("PrivateApi")
final class LegacyNfcDiagnostics {
    private static final int MESSAGE_TYPE_COMMAND = 1;
    private static final int CORE_GID = 0;
    private static final int CORE_GET_CONFIG_OID = 3;
    private static final int TRANSPORT_OK = 0;
    private static final long RESPONSE_TIMEOUT_SECONDS = 4;
    private static final int[] PARAMETER_IDS = {
            0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85
    };

    private LegacyNfcDiagnostics() {}

    static boolean isRawNciApiAvailable(Context context) {
        try {
            resolveApi(context);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static ProbeResult runReadOnlyProbe(Context context) throws Exception {
        RawNciApi api = resolveApi(context);
        if (!api.adapter.isEnabled()) {
            throw new IllegalStateException("NFC is disabled");
        }

        RawOperationReport report = new RawOperationReport(
                RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG);
        report.stage = "GET_ALL_PARAMETERS";
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        List<Integer> missing = new ArrayList<>();
        for (int id : PARAMETER_IDS) missing.add(id);

        try (DiagnosticSession session = DiagnosticSession.open(api)) {
            boolean combinedTimedOut = false;
            try {
                Map<Integer, byte[]> combined = session.getConfig(
                        buildGetPayload(PARAMETER_IDS), report, "GET_ALL_PARAMETERS");
                values.putAll(combined);
                missing.removeAll(combined.keySet());
            } catch (Throwable combinedFailure) {
                if (combinedFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw (InterruptedException) combinedFailure;
                }
                report.notes.add("Combined GET_CONFIG failed: "
                        + describeFailure(combinedFailure));
                RawOperationReport.RequestRecord request =
                        report.requests.get(report.requests.size() - 1);
                combinedTimedOut = request.frameworkStatus != null
                        && request.frameworkStatus == TRANSPORT_OK
                        && request.rx == null;
            }

            // A rejected batch often succeeds as individual reads. A transport timeout is not
            // retried seven times because that would make the diagnostics dialog stall for 30s.
            if (!combinedTimedOut) {
                for (int id : new ArrayList<>(missing)) {
                    try {
                        Map<Integer, byte[]> single = session.getConfig(
                                buildGetPayload(new int[] {id}), report,
                                String.format(Locale.US, "GET_0x%02X", id));
                        if (single.containsKey(id)) {
                            values.put(id, single.get(id));
                            missing.remove((Integer) id);
                        } else {
                            report.notes.add(String.format(Locale.US,
                                    "GET_CONFIG omitted 0x%02X", id));
                        }
                    } catch (Throwable singleFailure) {
                        if (singleFailure instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            throw (InterruptedException) singleFailure;
                        }
                        report.notes.add(String.format(Locale.US,
                                "GET 0x%02X failed: %s", id,
                                describeFailure(singleFailure)));
                    }
                }
            }
        }

        report.stage = "COMPLETE";
        if (values.isEmpty()) {
            report.result = "FAILED";
            report.primaryFailure = "No requested parameters could be read";
        } else if (missing.isEmpty()) {
            report.result = "OK";
        } else {
            report.result = "PARTIAL_RESULT";
        }

        String userSummary = formatProbe(report, values, missing, false);
        String detailedSummary = formatProbe(report, values, missing, true);
        AppLog.info("Read-only raw NCI probe: result=" + report.result
                + ", parameters=" + values.size());
        return new ProbeResult(userSummary, detailedSummary,
                "result=" + report.result + ", parameters=" + values.size(), values);
    }

    private static String formatProbe(RawOperationReport report,
            Map<Integer, byte[]> values, List<Integer> missing, boolean detailed) {
        StringBuilder text = new StringBuilder("READ-ONLY PROBE\n\n");
        text.append("Operation: DIAGNOSTIC_GET_CONFIG\n");
        text.append("Result: ").append(report.result).append('\n');
        text.append("Parameters:\n");
        if (values.isEmpty()) {
            text.append("none\n");
        } else {
            for (int id : PARAMETER_IDS) {
                if (!values.containsKey(id)) continue;
                text.append(String.format(Locale.US, "0x%02X %s = %s\n",
                        id, RawNciCodec.parameterName(id),
                        RawNciCodec.hex(values.get(id))));
            }
            for (Map.Entry<Integer, byte[]> entry : values.entrySet()) {
                if (isRequestedParameter(entry.getKey())) continue;
                text.append(String.format(Locale.US, "0x%02X %s = %s\n",
                        entry.getKey(), RawNciCodec.parameterName(entry.getKey()),
                        RawNciCodec.hex(entry.getValue())));
            }
        }
        if (!missing.isEmpty()) {
            text.append("Not returned: ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) text.append(", ");
                text.append(String.format(Locale.US, "0x%02X", missing.get(i)));
            }
            text.append('\n');
        }

        text.append("\nRAW REQUESTS\n");
        for (RawOperationReport.RequestRecord request : report.requests) {
            appendRequest(text, request, detailed);
        }
        if (detailed) {
            for (String note : report.notes) text.append("Note: ").append(note).append('\n');
        }
        return text.toString().trim();
    }

    private static void appendRequest(StringBuilder text,
            RawOperationReport.RequestRecord request, boolean detailed) {
        text.append('\n').append(request.stage).append('\n');
        text.append("TX: ").append(RawNciCodec.hex(request.tx)).append('\n');
        text.append("Framework: ");
        if (request.frameworkStatus == null) {
            text.append("not sent\n");
        } else {
            text.append(RawNciCodec.frameworkStatusName(request.frameworkStatus))
                    .append(" (").append(request.frameworkStatus).append(")\n");
        }
        text.append("RX: ").append(RawNciCodec.hex(request.rx)).append('\n');
        text.append("Controller: ");
        if (request.controllerStatus == null) {
            text.append("no response\n");
        } else {
            text.append(RawNciCodec.nciStatusName(request.controllerStatus))
                    .append(String.format(Locale.US, " (0x%02X)\n",
                            request.controllerStatus));
        }
        if (detailed && request.durationMillis != null) {
            text.append("Duration: ").append(request.durationMillis).append(" ms\n");
        }
        if (request.failure != null) text.append("Failure: ").append(request.failure).append('\n');
    }

    private static byte[] buildGetPayload(int[] ids) {
        byte[] payload = new byte[ids.length + 1];
        payload[0] = (byte) ids.length;
        for (int i = 0; i < ids.length; i++) payload[i + 1] = (byte) ids[i];
        return payload;
    }

    private static boolean isRequestedParameter(int candidate) {
        for (int id : PARAMETER_IDS) {
            if (id == candidate) return true;
        }
        return false;
    }

    private static RawNciApi resolveApi(Context context) throws Exception {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        if (adapter == null) throw new IllegalStateException("NFC is unavailable");

        Class<?> callbackClass = Class.forName(
                "android.nfc.NfcAdapter$NfcVendorNciCallback");
        Method sendMethod = findMethod(adapter.getClass(), "sendVendorNciMessage",
                int.class, int.class, int.class, byte[].class);
        Method registerCallbackMethod = findMethod(adapter.getClass(),
                "registerNfcVendorNciCallback", Executor.class, callbackClass);
        Method unregisterCallbackMethod = findMethod(adapter.getClass(),
                "unregisterNfcVendorNciCallback", callbackClass);
        return new RawNciApi(adapter, callbackClass, sendMethod,
                registerCallbackMethod, unregisterCallbackMethod);
    }

    private static final class DiagnosticSession implements AutoCloseable {
        final RawNciApi api;
        final Object callback;
        volatile CountDownLatch responseReady;
        volatile byte[] responsePayload;

        private DiagnosticSession(RawNciApi api, Object callback) {
            this.api = api;
            this.callback = callback;
        }

        static DiagnosticSession open(RawNciApi api) throws Exception {
            DiagnosticSession[] holder = new DiagnosticSession[1];
            InvocationHandler callbackHandler = (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(method.getName())) return "RawNciDiagnosticCallback";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) {
                        return arguments != null && arguments.length == 1
                                && proxy == arguments[0];
                    }
                }
                DiagnosticSession session = holder[0];
                if (session != null && "onVendorNciResponse".equals(method.getName())
                        && arguments != null && arguments.length == 3
                        && (Integer) arguments[0] == CORE_GID
                        && (Integer) arguments[1] == CORE_GET_CONFIG_OID) {
                    byte[] payload = (byte[]) arguments[2];
                    session.responsePayload = payload == null
                            ? new byte[0] : Arrays.copyOf(payload, payload.length);
                    CountDownLatch latch = session.responseReady;
                    if (latch != null) latch.countDown();
                }
                return null;
            };
            Object callback = Proxy.newProxyInstance(
                    api.callbackClass.getClassLoader(),
                    new Class<?>[] {api.callbackClass}, callbackHandler);
            DiagnosticSession session = new DiagnosticSession(api, callback);
            holder[0] = session;
            invoke(api.adapter, api.registerCallbackMethod, (Executor) Runnable::run, callback);
            return session;
        }

        Map<Integer, byte[]> getConfig(byte[] payload, RawOperationReport report,
                String stage) throws Exception {
            RawOperationReport.RequestRecord request = report.addRequest(
                    RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG,
                    null, stage, "GET_CONFIG", payload);
            long startNanos = System.nanoTime();
            responsePayload = null;
            responseReady = new CountDownLatch(1);
            try {
                Object result = invoke(api.adapter, api.sendMethod,
                        MESSAGE_TYPE_COMMAND, CORE_GID, CORE_GET_CONFIG_OID,
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
                if (!responseReady.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    request.failure = "timeout waiting for controller response";
                    throw new IllegalStateException(request.failure);
                }
                request.rx = responsePayload == null
                        ? new byte[0] : Arrays.copyOf(responsePayload, responsePayload.length);
                if (request.rx.length > 0) request.controllerStatus = request.rx[0] & 0xFF;
                try {
                    return RawNciCodec.parseGetConfigResponse(request.rx);
                } catch (IllegalArgumentException malformed) {
                    request.failure = malformed.getMessage();
                    throw new IllegalStateException(request.failure, malformed);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (request.failure == null) request.failure = "interrupted";
                throw interrupted;
            } finally {
                request.durationMillis = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startNanos);
                responseReady = null;
            }
        }

        @Override
        public void close() {
            try {
                invoke(api.adapter, api.unregisterCallbackMethod, callback);
            } catch (Throwable failure) {
                AppLog.warning("Unable to unregister raw NCI diagnostic callback", failure);
            }
        }
    }

    static final class ProbeResult {
        final String userSummary;
        final String detailedSummary;
        final String logSummary;
        final Map<Integer, byte[]> configuration;

        ProbeResult(String userSummary, String detailedSummary, String logSummary,
                Map<Integer, byte[]> configuration) {
            this.userSummary = userSummary;
            this.detailedSummary = detailedSummary;
            this.logSummary = logSummary;
            Map<Integer, byte[]> snapshot = new LinkedHashMap<>();
            for (Map.Entry<Integer, byte[]> entry : configuration.entrySet()) {
                snapshot.put(entry.getKey(), Arrays.copyOf(
                        entry.getValue(), entry.getValue().length));
            }
            this.configuration = Collections.unmodifiableMap(snapshot);
        }
    }

    private static final class RawNciApi {
        final NfcAdapter adapter;
        final Class<?> callbackClass;
        final Method sendMethod;
        final Method registerCallbackMethod;
        final Method unregisterCallbackMethod;

        RawNciApi(NfcAdapter adapter, Class<?> callbackClass, Method sendMethod,
                Method registerCallbackMethod, Method unregisterCallbackMethod) {
            this.adapter = adapter;
            this.callbackClass = callbackClass;
            this.sendMethod = sendMethod;
            this.registerCallbackMethod = registerCallbackMethod;
            this.unregisterCallbackMethod = unregisterCallbackMethod;
        }
    }

    private static String describeFailure(Throwable failure) {
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
            // The methods are public System APIs when their feature flag is enabled.
        }
        return method.invoke(receiver, arguments);
    }
}
