package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class NfcStackDiagnosticsTest {
    companion object { var fixtureInitialized = false }

    class UninitializedAdapter {
        companion object { init { fixtureInitialized = true } }
        init { throw AssertionError("A diagnostic must not acquire an adapter") }
        fun configure(enabled: Boolean, uid: ByteArray) {
            throw AssertionError("A diagnostic must not invoke a setter: $enabled ${uid.size}")
        }
    }

    private val loader = javaClass.classLoader!!
    private val fixture = javaClass.name + "\$UninitializedAdapter"

    @Test
    fun unrelatedSystemLibrariesDoNotConsumeTheNfcCollectionBudget() {
        for (name in listOf("libsn100nfc_nci.so", "libnfc_nci_jni.so", "libnci.so")) {
            assertTrue(NfcStackDiagnostics.isNativeNfcLibraryName(name))
        }
        for (name in listOf("libasyncio.so", "libincident.so", "libincidentpriv.so", "libnfc.so.fsv_meta")) {
            assertFalse(NfcStackDiagnostics.isNativeNfcLibraryName(name))
        }
    }

    @Test
    fun reflectionResolvesExactSignatureWithoutInitializationConstructionOrInvocation() {
        assertFalse(fixtureInitialized)
        val result = NfcStackDiagnostics.inspectMethod(loader, fixture,
            "configure", Boolean::class.javaPrimitiveType, ByteArray::class.java)
        assertEquals("present; not invoked", result)
        assertFalse(fixtureInitialized)
    }

    @Test
    fun methodMismatchIsDifferentFromMissingClass() {
        val missingMethod = NfcStackDiagnostics.inspectMethod(loader, fixture, "configure", String::class.java)
        assertTrue(missingMethod.startsWith("class present; method unavailable"))
        val missingClass = NfcStackDiagnostics.inspectMethod(loader, "missing.vendor.NfcAdapter", "configure")
        assertTrue(missingClass.startsWith("class unavailable"))
        assertTrue(missingClass.contains("ClassNotFoundException"))
    }

    @Test
    fun missingVendorDependencyIsReportedWithoutCrashingOtherDiagnostics() {
        val broken = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "broken.vendor.NfcAdapter") throw NoClassDefFoundError("vendor dependency")
                return super.loadClass(name, resolve)
            }
        }
        assertTrue(NfcStackDiagnostics.inspectMethod(broken, "broken.vendor.NfcAdapter", "configure")
            .contains("NoClassDefFoundError"))
        assertEquals("present; not invoked", NfcStackDiagnostics.inspectMethod(loader, "java.lang.String", "length"))
    }
}
