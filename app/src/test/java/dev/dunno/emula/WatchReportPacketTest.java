package dev.dunno.emula;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class WatchReportPacketTest {
    @Test public void unicodeReportRoundTripsWithoutChangingEvidence() throws Exception {
        String report = "EMULA WEAR\nМодель часов ⌚\nUID: 01 02 03 04\nPrimary: failed\nSuppressed: restore failed\n";
        assertEquals(report, WatchReportPacket.readReport(new ByteArrayInputStream(frame(report))));
    }

    @Test public void largestAllowedReportRoundTrips() throws Exception {
        String report = "x".repeat(WatchReportPacket.MAX_BYTES);
        assertEquals(report, WatchReportPacket.readReport(new ByteArrayInputStream(frame(report))));
    }

    @Test public void byteLimitAppliesToUtf8RatherThanCharacterCount() throws Exception {
        assertThrows(IOException.class, () -> WatchReportPacket.encode("я".repeat(WatchReportPacket.MAX_BYTES / 2 + 1)));
        assertThrows(IOException.class, () -> WatchReportPacket.encode("x".repeat(WatchReportPacket.MAX_BYTES + 1)));
        assertThrows(IOException.class, () -> WatchReportPacket.encode("\uD800"));
        assertThrows(IOException.class, () -> WatchReportPacket.encode(" \n\t"));
    }

    @Test public void declaredLengthIsValidatedBeforeReadingOrAllocating() throws Exception {
        byte[] report = frame("x");
        for (int length : new int[]{0, -1, WatchReportPacket.MAX_BYTES + 1, Integer.MAX_VALUE}) {
            byte[] invalid = report.clone();
            java.nio.ByteBuffer.wrap(invalid, 5, 4).putInt(length);
            assertThrows(IOException.class, () -> WatchReportPacket.readReport(new ByteArrayInputStream(invalid)));
        }
    }

    @Test public void malformedAndTruncatedReportsAreRejected() throws Exception {
        byte[] report = frame("hello");
        for (int size = 0; size < report.length; size++) {
            byte[] truncated = Arrays.copyOf(report, size);
            assertThrows(IOException.class, () -> WatchReportPacket.readReport(new ByteArrayInputStream(truncated)));
        }
        byte[] invalidUtf8 = frame("xx");
        invalidUtf8[9] = (byte) 0xc3;
        invalidUtf8[10] = (byte) 0x28;
        assertThrows(IOException.class, () -> WatchReportPacket.readReport(new ByteArrayInputStream(invalidUtf8)));
        byte[] blank = frame("xx");
        blank[9] = ' ';
        blank[10] = '\n';
        assertThrows(IOException.class, () -> WatchReportPacket.readReport(new ByteArrayInputStream(blank)));
    }

    @Test public void eachProtocolStageRequiresItsOwnMagicAndVersion() throws Exception {
        ByteArrayOutputStream hello = new ByteArrayOutputStream();
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        ByteArrayOutputStream receipt = new ByteArrayOutputStream();
        WatchReportPacket.hello(hello);
        WatchReportPacket.request(request);
        WatchReportPacket.receipt(receipt);
        WatchReportPacket.checkHello(new ByteArrayInputStream(hello.toByteArray()));
        WatchReportPacket.checkRequest(new ByteArrayInputStream(request.toByteArray()));
        WatchReportPacket.checkReceipt(new ByteArrayInputStream(receipt.toByteArray()));
        assertThrows(IOException.class, () -> WatchReportPacket.checkHello(new ByteArrayInputStream(request.toByteArray())));
        assertThrows(IOException.class, () -> WatchReportPacket.checkRequest(new ByteArrayInputStream(receipt.toByteArray())));
        assertThrows(IOException.class, () -> WatchReportPacket.checkReceipt(new ByteArrayInputStream(hello.toByteArray())));
        byte[] newerVersion = hello.toByteArray();
        newerVersion[4] = 2;
        assertThrows(IOException.class, () -> WatchReportPacket.checkHello(new ByteArrayInputStream(newerVersion)));
        byte[] newerReport = frame("report");
        newerReport[4] = 2;
        assertThrows(IOException.class, () -> WatchReportPacket.readReport(new ByteArrayInputStream(newerReport)));
    }

    private static byte[] frame(String report) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WatchReportPacket.report(bytes, WatchReportPacket.encode(report));
        return bytes.toByteArray();
    }
}
