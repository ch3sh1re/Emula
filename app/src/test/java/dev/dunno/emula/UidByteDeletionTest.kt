package dev.dunno.emula

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UidByteDeletionTest {
    @Test
    fun `repeated backspace clears every supported length and stays at the first byte`() {
        for (length in listOf(4, 7, 10)) {
            var bytes = Array(10) { "AB" }
            var index = length - 1
            var cursor = 2
            repeat(length * 2 + 5) {
                val result = UidByteDeletion.delete(bytes, length, index, cursor, cursor, 1)
                bytes = result.bytes
                index = result.activeByte
                cursor = result.cursor
            }
            assertArrayEquals(Array(length) { "" }, bytes.copyOfRange(0, length))
            assertArrayEquals(Array(10 - length) { "AB" }, bytes.copyOfRange(length, 10))
            assertEquals(0, index)
            assertEquals(0, cursor)
        }
    }

    @Test
    fun `clearing a byte moves left with a collapsed cursor`() {
        val result = UidByteDeletion.delete(arrayOf("AB", "CD", "E", ""), 4, 2, 1, 1, 1)
        assertArrayEquals(arrayOf("AB", "CD", "", ""), result.bytes)
        assertEquals(1, result.activeByte)
        assertEquals(2, result.cursor)
        val next = UidByteDeletion.delete(result.bytes, 4, result.activeByte,
            result.cursor, result.cursor, 1)
        assertArrayEquals(arrayOf("AB", "C", "", ""), next.bytes)
        assertEquals(1, next.activeByte)
        assertEquals(1, next.cursor)
    }

    @Test
    fun `deleting from an empty byte skips gaps and preserves bytes to the right`() {
        val result = UidByteDeletion.delete(arrayOf("AB", "", "", "CD"), 4, 2, 0, 0, 1)
        assertArrayEquals(arrayOf("A", "", "", "CD"), result.bytes)
        assertEquals(0, result.activeByte)
        assertEquals(1, result.cursor)
    }

    @Test
    fun `an accelerated delete crosses multiple byte boundaries`() {
        val result = UidByteDeletion.delete(arrayOf("AB", "CD", "EF", "01"), 4, 3, 2, 2, 5)
        assertArrayEquals(arrayOf("AB", "C", "", ""), result.bytes)
        assertEquals(1, result.activeByte)
        assertEquals(1, result.cursor)
    }

    @Test
    fun `a reversed whole byte selection clears only that byte`() {
        val result = UidByteDeletion.delete(arrayOf("AB", "CD", "EF", "01"), 4, 2, 2, 0, 1)
        assertArrayEquals(arrayOf("AB", "CD", "", "01"), result.bytes)
        assertEquals(1, result.activeByte)
        assertEquals(2, result.cursor)
    }

    @Test
    fun `deleting before a cursor preserves the suffix`() {
        val result = UidByteDeletion.delete(arrayOf("AB", "CD", "EF", "01"), 4, 1, 1, 1, 1)
        assertArrayEquals(arrayOf("AB", "D", "EF", "01"), result.bytes)
        assertEquals(1, result.activeByte)
        assertEquals(0, result.cursor)
    }

    @Test
    fun `deleting at the start crosses left without touching the current byte`() {
        val result = UidByteDeletion.delete(arrayOf("AB", "CD", "EF", "01"), 4, 1, 0, 0, 1)
        assertArrayEquals(arrayOf("A", "CD", "EF", "01"), result.bytes)
        assertEquals(0, result.activeByte)
        assertEquals(1, result.cursor)
    }

    @Test
    fun `deleting at the first byte boundary leaves the draft unchanged`() {
        val bytes = arrayOf("AB", "CD", "EF", "01")
        val result = UidByteDeletion.delete(bytes, 4, 0, 0, 0, 100)
        assertArrayEquals(bytes, result.bytes)
        assertArrayEquals(arrayOf("AB", "CD", "EF", "01"), bytes)
        assertEquals(0, result.activeByte)
        assertEquals(0, result.cursor)
    }
}
