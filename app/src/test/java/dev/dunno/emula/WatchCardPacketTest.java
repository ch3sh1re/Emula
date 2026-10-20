package dev.dunno.emula;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class WatchCardPacketTest {
    private static SavedCard card(String name, int length, int last) {
        byte[] uid = new byte[length]; uid[length - 1] = (byte) last;
        return new SavedCard("phone-" + name, name, uid);
    }
    private static List<WatchCardPacket.Entry> decode(byte[] packet) throws IOException {
        return WatchCardPacket.read(new ByteArrayInputStream(packet));
    }
    private static void rejected(byte[] packet) {
        try { decode(packet); fail("Malformed packet accepted"); }
        catch (IOException expected) { /* Whole batch rejected. */ }
    }

    @Test public void roundTripUnicodeAndAllUidLengths() throws Exception {
        List<SavedCard> cards = List.of(card("Вход 🪪", 4, 255), card("Gate\nB", 7, 2), card("10", 10, 3));
        List<WatchCardPacket.Entry> received = decode(WatchCardPacket.encode(cards));
        assertEquals(3, received.size());
        for (int i = 0; i < cards.size(); i++) {
            assertEquals(cards.get(i).name, received.get(i).name);
            assertArrayEquals(cards.get(i).uid, received.get(i).uid);
        }
    }

    @Test public void mergePreservesWatchIdentityNameAndUid() {
        SavedCard local = card("Watch name", 4, 1);
        WatchCardPacket.Merge plan = WatchCardPacket.append(List.of(local), List.of(
                new WatchCardPacket.Entry("Phone name", local.uid),
                new WatchCardPacket.Entry("New", new byte[]{1,2,3,4})), "Card");
        assertEquals(1, plan.result.added);
        assertEquals(1, plan.result.skipped);
        assertSame(local, plan.cards.get(0));
        assertNotEquals(local.id, plan.cards.get(1).id);
    }

    @Test public void deduplicatesWithinBatchAndRepeatedDelivery() throws Exception {
        List<WatchCardPacket.Entry> entries = decode(WatchCardPacket.encode(List.of(
                card("First", 7, 1), card("Duplicate", 7, 1), card("Other length", 4, 1))));
        WatchCardPacket.Merge first = WatchCardPacket.append(List.of(), entries, "Card");
        assertEquals(2, first.result.added); assertEquals(1, first.result.skipped);
        WatchCardPacket.Merge retry = WatchCardPacket.append(first.cards, entries, "Card");
        assertEquals(0, retry.result.added); assertEquals(3, retry.result.skipped);
        assertSame(first.cards.get(0), retry.cards.get(0));
        assertSame(first.cards.get(1), retry.cards.get(1));
    }

    @Test public void emptyIncomingNeverDeletesExisting() {
        SavedCard existing = card("Keep", 4, 1);
        WatchCardPacket.Merge merge = WatchCardPacket.append(List.of(existing), List.of(), "Card");
        assertEquals(1, merge.cards.size()); assertSame(existing, merge.cards.get(0));
        assertEquals(0, merge.result.added); assertEquals(0, merge.result.skipped);
    }

    @Test public void defaultNameAndUidCopy() {
        byte[] uid = {1,2,3,4};
        WatchCardPacket.Entry entry = new WatchCardPacket.Entry("  ", uid); uid[0] = 9;
        WatchCardPacket.Merge merge = WatchCardPacket.append(List.of(), List.of(entry), "Card");
        assertEquals("Card", merge.cards.get(0).name);
        assertArrayEquals(new byte[]{1,2,3,4}, merge.cards.get(0).uid);
        entry.uid[0] = 8;
        assertEquals(1, merge.cards.get(0).uid[0]);
    }

    @Test public void everyTruncationIsRejected() throws Exception {
        byte[] frame = WatchCardPacket.encode(List.of(card("Office", 4, 1), card("Gate", 10, 2)));
        for (int i = 0; i < frame.length; i++) rejected(Arrays.copyOf(frame, i));
    }

    @Test public void wrongMagicAndVersionAreRejected() throws Exception {
        byte[] packet = WatchCardPacket.encode(List.of(card("A", 4, 1)));
        byte[] wrong = packet.clone(); wrong[0] ^= 1; rejected(wrong);
        wrong = packet.clone(); wrong[4]++; rejected(wrong);
    }

    @Test public void oversizedNegativeAndEmptyFramesAreRejectedBeforeAllocation() throws Exception {
        byte[] packet = WatchCardPacket.encode(List.of(card("A", 4, 1)));
        for (int size : new int[]{-1, 0, Integer.MAX_VALUE, WatchCardPacket.MAX_BYTES + 1}) {
            byte[] wrong = packet.clone(); ByteBuffer.wrap(wrong).putInt(5, size); rejected(wrong);
        }
        for (int count : new int[]{-1, 0, WatchCardPacket.MAX_CARDS + 1}) {
            byte[] wrong = packet.clone(); ByteBuffer.wrap(wrong).putInt(9, count); rejected(wrong);
        }
    }

    @Test public void invalidUidLengthAndUtf8AreRejected() throws Exception {
        byte[] packet = WatchCardPacket.encode(List.of(card("A", 4, 1)));
        byte[] wrong = packet.clone(); wrong[16] = 5; rejected(wrong);
        wrong = packet.clone(); wrong[15] = (byte) 0xff; rejected(wrong);
    }

    @Test public void trailingPayloadIsRejected() throws Exception {
        byte[] packet = WatchCardPacket.encode(List.of(card("A", 4, 1)));
        byte[] wrong = Arrays.copyOf(packet, packet.length + 1);
        ByteBuffer.wrap(wrong).putInt(5, ByteBuffer.wrap(packet).getInt(5) + 1);
        rejected(wrong);
    }

    @Test public void nameAndBatchSizeLimitsAreEnforced() throws Exception {
        assertEquals(1, decode(WatchCardPacket.encode(List.of(card("A".repeat(1024), 4, 1)))).size());
        for (List<SavedCard> cards : List.of(List.<SavedCard>of(),
                List.of(card("A".repeat(1025), 4, 1)),
                java.util.Collections.nCopies(2001, card("A", 4, 1)),
                java.util.Collections.nCopies(1000, card("A".repeat(500), 4, 1)))) {
            try { WatchCardPacket.encode(cards); fail("Oversized batch accepted"); }
            catch (IOException expected) { /* Bounded transfer. */ }
        }
    }

    @Test public void handshakeAndReceiptValidatePeerAndCounts() throws Exception {
        ByteArrayOutputStream hello = new ByteArrayOutputStream(); WatchCardPacket.hello(hello);
        WatchCardPacket.checkHello(new ByteArrayInputStream(hello.toByteArray()));
        try { WatchCardPacket.checkHello(new ByteArrayInputStream(new byte[]{0,0,0,0,1})); fail(); }
        catch (IOException expected) { /* No cards sent to another service. */ }
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        WatchCardPacket.reply(reply, new WatchCardPacket.Result(2, 3));
        WatchCardPacket.Result result = WatchCardPacket.readReply(new ByteArrayInputStream(reply.toByteArray()), 5);
        assertEquals(2, result.added); assertEquals(3, result.skipped);
        try { WatchCardPacket.readReply(new ByteArrayInputStream(reply.toByteArray()), 4); fail(); }
        catch (IOException expected) { /* Do not claim an unrelated receipt. */ }
    }
}
