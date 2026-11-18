package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OplusNfcDiagnosticsTest {
    @Test fun timeoutsDoNotAccumulateVendorCallsAndLateResultIsRetained() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = OplusNfcDiagnostics.Once()
        try {
            val pending = once.read({
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "Vendor getNfcc: SN220T"
            }, 10)
            assertTrue(pending.contains("still pending"))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(once.read({ error("duplicate vendor call") }, 10).contains("still pending"))
            release.countDown()
            assertEquals("Vendor getNfcc: SN220T", once.read({ error("duplicate vendor call") }, 1000))
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }

    @Test fun permissionFailuresAreEvidenceAndAreNotRetried() {
        val once = OplusNfcDiagnostics.Once()
        val result = once.read({ throw SecurityException("safe.NFC") }, 1000)
        assertTrue(result.contains("SecurityException: safe.NFC"))
        assertEquals(result, once.read({ error("duplicate vendor call") }, 1000))
    }

    @Test fun queryIsScopedToTheReviewedOplusFamilies() {
        assertTrue(OplusNfcDiagnostics.targets("OPPO"))
        assertTrue(OplusNfcDiagnostics.targets("OnePlus"))
        for (name in listOf("Xiaomi", "HONOR", "nubia", "Samsung", "Google", "realme", "")) {
            assertFalse(OplusNfcDiagnostics.targets(name))
        }
    }
}
