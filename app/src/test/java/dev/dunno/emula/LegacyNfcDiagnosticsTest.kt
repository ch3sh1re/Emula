package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class LegacyNfcDiagnosticsTest {
    private val ids = listOf(0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85)

    @Test
    fun lateResponseIsDecodedForEvidenceButNeverBecomesAWriteSnapshot() {
        val response = "00 07 30 01 04 31 01 00 32 01 20 33 04 01 02 03 04 " +
            "58 01 41 59 02 11 22 85 01 01"
        val state = State()
        state.collect { _, record ->
            record.frameworkStatus = 3
            record.frameworkReturnElapsedNanos = 3_000_000_000
            record.callbackElapsedNanos = 3_001_100_000
            LegacyNfcDiagnostics.receiveGetConfig(state.report, record, { timeout ->
                assertEquals(1000L, timeout)
                response.split(' ').map { it.toInt(16).toByte() }.toByteArray()
            })
        }
        assertTrue(state.values.isEmpty())
        assertEquals(ids, state.missing)
        assertEquals(1, state.report.requests.size)
        state.report.result = "FAILED"
        val formatted = LegacyNfcDiagnostics.formatProbe(state.report, state.values, state.missing, true)
        assertTrue(formatted.contains("Not validated:"))
        assertTrue(formatted.contains("Observed parameters (framework FAILED; evidence only):"))
        assertTrue(formatted.contains("0x33 LA_NFCID1 = 01 02 03 04"))
        assertTrue(formatted.contains("Callback minus framework return: 1.100 ms"))
        val result = LegacyNfcDiagnostics.ProbeResult(formatted, formatted, "FAILED", state.values)
        assertFalse(LegacyNfcController.isCompleteExperimentalSnapshot(result.configuration))
    }

    @Test
    fun malformedFailedResponseKeepsOriginalFailureAndCannotBreakReportFormatting() {
        val state = State()
        state.collect { _, record ->
            record.frameworkStatus = 3
            LegacyNfcDiagnostics.receiveGetConfig(state.report, record, { byteArrayOf(0, 1, 0x33, 4, 8) })
        }
        val text = LegacyNfcDiagnostics.formatProbe(state.report, state.values, state.missing, true)
        assertTrue(text.contains("Observed response not decoded: truncated value"))
        assertTrue(text.contains("Failure: framework FAILED"))
        assertTrue(state.values.isEmpty())
        assertEquals(1, state.report.requests.size)
    }

    @Test
    fun callbackObservationKeepsFirstMatchingResponseAndCopiesBytes() {
        val pending = LegacyNfcDiagnostics.PendingResponse(10_000)
        val report = State().report
        val record = report.addRequest(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG,
            null, "GET", "GET_CONFIG", byteArrayOf(1, 0x33))
        pending.record(1, 3, byteArrayOf(1), 11_000)
        pending.record(0, 2, byteArrayOf(2), 12_000)
        assertNull(pending.await(0))
        val bytes = byteArrayOf(0, 0)
        pending.record(0, 3, bytes, 13_000)
        bytes[0] = 9
        pending.record(0, 3, byteArrayOf(9), 14_000)
        assertArrayEquals(byteArrayOf(0, 0), pending.await(0))
        pending.finish(record)
        assertEquals(3_000L, record.callbackElapsedNanos)
        pending.record(0, 3, byteArrayOf(8), 15_000)
        assertArrayEquals(byteArrayOf(0, 0), pending.await(0))
    }

    @Test
    fun closedObservationCannotReceiveLateCallbackAndFreshObservationStartsEmpty() {
        val pending = LegacyNfcDiagnostics.PendingResponse(100)
        val record = State().report.addRequest(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG,
            null, "GET", "GET_CONFIG", byteArrayOf(1, 0x33))
        pending.finish(record)
        pending.record(0, 3, byteArrayOf(0, 0), 200)
        assertNull(pending.await(0))
        assertNull(record.callbackElapsedNanos)
        assertNull(LegacyNfcDiagnostics.PendingResponse(300).await(0))
    }

    @Test
    fun callbackBeforeFrameworkReturnHasNegativeDelta() {
        val state = State()
        val record = state.report.addRequest(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG,
            null, "GET", "GET_CONFIG", byteArrayOf(1, 0x33))
        record.frameworkStatus = 0
        record.frameworkReturnElapsedNanos = 3_000_000
        val pending = LegacyNfcDiagnostics.PendingResponse(10_000_000)
        pending.record(0, 3, byteArrayOf(0, 0), 11_500_000)
        pending.finish(record)
        val formatted = LegacyNfcDiagnostics.formatProbe(state.report, state.values, state.missing, true)
        assertTrue(formatted.contains("Callback minus framework return: -1.500 ms"))
    }

    @Test
    fun serviceMonitorTimeoutCanDeliverResponseAfterFailedWithoutCreatingSnapshot() {
        // Reproduce the two synchronized methods and FutureTask from the service DEX.
        // Native success is simulated; this is not a hardware transport test.
        val response = byteArrayOf(0, ids.size.toByte()) + ids.flatMap {
            listOf(it.toByte(), 0.toByte())
        }.toByteArray()
        val arrived = CountDownLatch(1)
        val interruptedAtDelivery = AtomicBoolean()
        val executor = Executors.newSingleThreadExecutor()
        val service = object {
            @Synchronized fun deliver() {
                interruptedAtDelivery.set(Thread.currentThread().isInterrupted)
                arrived.countDown()
            }

            @Synchronized fun send(): Int {
                val nativeSucceeded = CountDownLatch(1)
                val task = FutureTask {
                    nativeSucceeded.countDown()
                    deliver()
                    0
                }
                executor.submit(task)
                assertTrue(nativeSucceeded.await(2, TimeUnit.SECONDS))
                return try {
                    task.get(100, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    assertEquals(1L, arrived.count)
                    executor.shutdownNow()
                    3
                }
            }
        }
        val state = State()
        try {
            state.collect { _, record ->
                record.frameworkStatus = service.send()
                LegacyNfcDiagnostics.receiveGetConfig(state.report, record, { timeout ->
                    assertEquals(1000L, timeout)
                    if (arrived.await(timeout, TimeUnit.MILLISECONDS)) response else null
                })
            }
            assertEquals(1, state.report.requests.size)
            val record = state.report.requests.single()
            assertEquals(3, record.frameworkStatus)
            assertEquals(0, record.controllerStatus)
            assertArrayEquals(response, record.rx)
            assertEquals("framework FAILED", record.failure)
            assertTrue(interruptedAtDelivery.get())
            assertTrue(state.values.isEmpty())
            assertEquals(ids, state.missing)
            assertFalse(LegacyNfcController.isCompleteExperimentalSnapshot(state.values))
            assertTrue(state.report.notes.any { it.contains("excluded from the configuration snapshot") })
            response[0] = 9
            assertEquals(0, record.rx[0].toInt())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun missingDelayedResponseStillStopsAfterSingleGet() {
        val state = State()
        state.collect { _, record ->
            record.frameworkStatus = 3
            LegacyNfcDiagnostics.receiveGetConfig(state.report, record, { timeout ->
                assertEquals(1000L, timeout)
                null
            })
        }
        assertEquals(1, state.report.requests.size)
        assertNull(state.report.requests.single().rx)
        assertTrue(state.values.isEmpty())
        assertTrue(state.report.notes.any { it.contains("No GET_CONFIG response arrived") })
    }

    @Test
    fun explicitRejectionsDoNotWaitAfterFailure() {
        for (status in listOf(1, 2)) {
            val report = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG)
            val record = report.addRequest(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG,
                null, "GET_ALL_PARAMETERS", "GET_CONFIG", byteArrayOf(1, 0x33))
            record.frameworkStatus = status
            assertThrows(IllegalStateException::class.java) {
                LegacyNfcDiagnostics.receiveGetConfig(report, record, {
                    throw AssertionError("Rejected request must not wait")
                })
            }
            assertNull(record.rx)
        }
    }

    @Test
    fun successfulTransportStillParsesResponseWithNormalTimeout() {
        val report = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG)
        val record = report.addRequest(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG,
            null, "GET_ALL_PARAMETERS", "GET_CONFIG", byteArrayOf(1, 0x33))
        record.frameworkStatus = 0
        val values = LegacyNfcDiagnostics.receiveGetConfig(report, record, { timeout ->
            assertEquals(4000L, timeout)
            byteArrayOf(0, 1, 0x33, 0)
        })
        assertArrayEquals(byteArrayOf(), values[0x33])
        assertEquals(0, record.controllerStatus)
        assertNull(record.failure)
        assertTrue(report.notes.isEmpty())
    }

    @Test
    fun interruptedObservationStopsCollectionAndRestoresInterruptFlag() {
        val state = State()
        try {
            assertThrows(InterruptedException::class.java) {
                state.collect { _, record ->
                    record.frameworkStatus = 3
                    LegacyNfcDiagnostics.receiveGetConfig(state.report, record, {
                        throw InterruptedException("cancelled observation")
                    })
                }
            }
            assertEquals(1, state.report.requests.size)
            assertTrue(state.values.isEmpty())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun honorFrameworkFailureStopsAfterOneRequestAndKeepsOriginalEvidence() {
        val state = State()
        state.collect { _, record ->
            record.frameworkStatus = 3
            record.durationMillis = 3001
            record.failure = "framework FAILED"
            throw IllegalStateException(record.failure)
        }
        assertEquals(1, state.report.requests.size)
        assertEquals("GET_ALL_PARAMETERS", state.report.requests.single().stage)
        assertEquals(3, state.report.requests.single().frameworkStatus)
        assertNull(state.report.requests.single().controllerStatus)
        assertTrue(state.values.isEmpty())
        assertEquals(ids, state.missing)
        assertTrue(state.report.notes.any { it.contains("individual GET_CONFIG requests were skipped") })
    }

    @Test
    fun acceptedCommandWithoutCallbackDoesNotRetryEveryParameter() {
        val state = State()
        state.collect { _, record ->
            record.frameworkStatus = 0
            record.failure = "timeout waiting for controller response"
            throw IllegalStateException(record.failure)
        }
        assertEquals(1, state.report.requests.size)
        assertEquals(ids, state.missing)
    }

    @Test
    fun controllerRejectedBatchStillFallsBackToSinglesIncludingEmptyValues() {
        val state = State()
        state.collect { payload, record ->
            record.frameworkStatus = 0
            if (payload[0].toInt() != 1) {
                record.rx = byteArrayOf(9, 1, 0x59)
                record.controllerStatus = 9
                throw IllegalStateException("NCI GET_CONFIG failed: INVALID_PARAM")
            }
            val id = payload[1].toInt() and 0xFF
            record.rx = byteArrayOf(0, 1, id.toByte(), 0)
            record.controllerStatus = 0
            mapOf(id to byteArrayOf())
        }
        assertEquals(8, state.report.requests.size)
        assertEquals(ids.toSet(), state.values.keys)
        assertTrue(state.missing.isEmpty())
        assertArrayEquals(byteArrayOf(), state.values[0x59])
    }

    @Test
    fun partialBatchOnlyRetriesMissingParametersAndPreservesSnapshotBytes() {
        val state = State()
        val uid = byteArrayOf(8, 0, 0, 0)
        state.collect { payload, record ->
            record.frameworkStatus = 0
            record.rx = byteArrayOf(0)
            if (payload[0].toInt() != 1) ids.filter { it != 0x59 }.associateWith {
                if (it == 0x33) uid else byteArrayOf(1)
            } else {
                assertEquals(0x59, payload[1].toInt())
                mapOf(0x59 to byteArrayOf())
            }
        }
        assertEquals(2, state.report.requests.size)
        assertArrayEquals(uid, state.values[0x33])
        assertTrue(state.missing.isEmpty())
    }

    @Test
    fun failedTransportDuringSinglesStopsAndRetainsAlreadyReadValues() {
        val state = State()
        state.collect { payload, record ->
            record.frameworkStatus = 0
            if (payload[0].toInt() != 1) {
                record.rx = byteArrayOf(9, 0)
                throw IllegalStateException("batch rejected")
            }
            if (payload[1].toInt() == 0x30) {
                record.rx = byteArrayOf(0, 1, 0x30, 1, 4)
                mapOf(0x30 to byteArrayOf(4))
            } else {
                record.frameworkStatus = 3
                throw IllegalStateException("framework FAILED")
            }
        }
        assertEquals(3, state.report.requests.size)
        assertArrayEquals(byteArrayOf(4), state.values[0x30])
        assertEquals(ids.drop(1), state.missing)
        assertFalse(LegacyNfcController.isCompleteExperimentalSnapshot(state.values))
    }

    @Test
    fun explicitFrameworkBatchRejectionRetainsSingleReadFallback() {
        for (status in listOf(1, 2)) {
            val state = State()
            state.collect { payload, record ->
                if (payload[0].toInt() != 1) {
                    record.frameworkStatus = status
                    throw IllegalStateException("framework rejected batch")
                }
                record.frameworkStatus = 0
                record.rx = byteArrayOf(0)
                mapOf((payload[1].toInt() and 0xFF) to byteArrayOf())
            }
            assertEquals(8, state.report.requests.size)
            assertTrue(state.missing.isEmpty())
        }
    }

    @Test
    fun interruptionStopsCollectionAndPreservesInterruptFlag() {
        val state = State()
        try {
            assertThrows(InterruptedException::class.java) {
                state.collect { _, _ -> throw InterruptedException("cancelled") }
            }
            assertEquals(1, state.report.requests.size)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(ids, state.missing)
        } finally {
            Thread.interrupted()
        }
    }

    private inner class State {
        val report = RawOperationReport(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG)
        val values = linkedMapOf<Int, ByteArray>()
        val missing = ids.toMutableList()

        fun collect(read: (ByteArray, RawOperationReport.RequestRecord) -> Map<Int, ByteArray>) {
            LegacyNfcDiagnostics.collectConfiguration({ payload, report, stage ->
                val record = report.addRequest(RawOperationReport.Operation.DIAGNOSTIC_GET_CONFIG,
                    null, stage, "GET_CONFIG", payload)
                read(payload, record)
            }, report, values, missing)
        }
    }
}
