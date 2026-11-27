package dev.dunno.emula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/** Shared-library client. Capability inspection does not call configuration methods. */
@SuppressLint("PrivateApi")
class StVendorSdk implements MotorolaStNfcController.Api {
    private static final String ADAPTER = "com.st.android.nfc_extensions.NfcAdapterStExtensions";
    private static final String SERVICE = "com.st.android.nfc_extensions.INfcAdapterStExtensions";
    final Object receiver;
    private final Class<?> type;
    private final Method program;
    private final Method set;
    private final Context context;

    StVendorSdk(Context context) throws Exception {
        this.context = context;
        type = Class.forName(ADAPTER, false, context.getClassLoader());
        program = method("programHceParameters", void.class, boolean.class, byte.class, byte.class,
                byte.class, byte[].class, byte.class, byte[].class);
        set = method("setNciConfig", void.class, int.class, byte[].class);
        Object service = invoke(type.getMethod("getNfcAdapterStExtensionsInterface"), null);
        if (!(service instanceof IInterface)) throw new IllegalStateException("ST service is unavailable");
        IBinder binder = ((IInterface) service).asBinder();
        if (binder == null || !binder.isBinderAlive() || !SERVICE.equals(binder.getInterfaceDescriptor())) {
            throw new IllegalStateException("ST service is unavailable or has an unexpected interface");
        }
        receiver = type.getConstructor().newInstance();
        if (!LegacyNfcDiagnostics.isRawNciApiAvailable(context)) {
            throw new IllegalStateException("Read-only GET_CONFIG is required for ST verification");
        }
    }

    final Method method(String name, Class<?> result, Class<?>... args) throws NoSuchMethodException {
        Method m = type.getMethod(name, args);
        if (m.getReturnType() != result) throw new NoSuchMethodException("Unexpected signature for " + name);
        return m;
    }

    static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("ST SDK call failed", cause);
        }
    }

    @Override public void program(boolean enabled, byte[] uid) throws Exception {
        invoke(program, receiver, enabled, (byte) 0x06, (byte) 0x0C, (byte) 0x20, uid.clone(),
                (byte) 0x40, new byte[0]);
    }

    @Override public void setConfiguration(int id, byte[] value) throws Exception {
        invoke(set, receiver, id, value.clone());
    }

    @Override public Map<Integer, byte[]> readConfiguration(OemOperationReport report) throws Exception {
        LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(context);
        report.notes.add(report.stage + "\n" + probe.detailedSummary);
        return probe.configuration;
    }
}
