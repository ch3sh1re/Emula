package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class NfcFileEvidenceTest {
    private fun collector(limit: Long = 100, total: Long = 200) =
        NfcFileEvidence(limit, total, 10) { 0 }

    @Test fun hashesCompleteBytesAndDistinguishesFilesWithTheSameSize() {
        val a = collector().digest(ByteArrayInputStream("abc".toByteArray()), 3)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", a)
        assertNotEquals(a, collector().digest(ByteArrayInputStream("abd".toByteArray()), 3))
    }

    @Test fun oversizedInputIsNotOpenedOrReadAndTotalBudgetIsShared() {
        val denied = object : InputStream() { override fun read(): Int = error("must not read") }
        assertThrows(IOException::class.java) { collector(2).digest(denied, 3) }
        val c = collector(3, 4)
        c.digest(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        assertThrows(IOException::class.java) { c.digest(denied, 2) }
    }

    @Test fun earlyEofOrGrowthNeverProducesAFingerprint() {
        assertThrows(IOException::class.java) { collector().digest(ByteArrayInputStream(byteArrayOf(1)), 2) }
        assertThrows(IOException::class.java) { collector().digest(ByteArrayInputStream(byteArrayOf(1, 2)), 1) }
    }

    @Test fun deadlineAndInterruptionNeverReturnAPartialHash() {
        var now = 0L
        val c = NfcFileEvidence(10, 20, 10) { now }
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val result = super.read(b, off, len)
                now = 10
                return result
            }
        }
        assertThrows(IOException::class.java) { c.digest(input, 2) }
        Thread.currentThread().interrupt()
        try {
            assertThrows(IOException::class.java) { collector().digest(ByteArrayInputStream(byteArrayOf(1)), 1) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun unreadableAndDuplicateFilesAreExplicit() {
        val dir = Files.createTempDirectory("nfc-file-evidence").toFile()
        try {
            val c = collector()
            assertEquals("unreadable or absent", c.describe(dir.resolve("missing")))
            val file = dir.resolve("NfcNci.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertTrue(c.describe(file).startsWith("SHA-256 "))
            assertEquals("already listed (same file)", c.describe(dir.resolve("./NfcNci.apk")))
        } finally { dir.deleteRecursively() }
    }
}
