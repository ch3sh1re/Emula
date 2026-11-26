package dev.dunno.emula;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Versioned, bounded card transfer. Contains no NFC operations or activation state. */
final class WatchCardPacket {
    static final int MAX_CARDS = 2_000;
    static final int MAX_NAME_BYTES = 1_024;
    static final int MAX_BYTES = 256 * 1_024;
    private static final int VERSION = 1;
    private static final int HELLO = 0x454D5748;
    private static final int REQUEST = 0x454D5743;
    private static final int REPLY = 0x454D5741;
    // Old peers finish at the v1 receipt. New peers then exchange colors in batch order.
    private static final int COLOR_OFFER = 0x454D574F;
    private static final int COLORS = 0x454D5750;
    private static final int COLORS_SAVED = 0x454D5753;

    static final class Entry {
        final String name;
        final byte[] uid;
        Entry(String name, byte[] uid) {
            this.name = name;
            this.uid = uid.clone();
        }
    }

    static final class Result {
        final int added;
        final int skipped;
        Result(int added, int skipped) { this.added = added; this.skipped = skipped; }
    }

    static final class Merge {
        final List<SavedCard> cards;
        final Result result;
        Merge(List<SavedCard> cards, Result result) { this.cards = cards; this.result = result; }
    }

    static void hello(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(HELLO);
        out.writeByte(VERSION);
        out.flush();
    }

    static void checkHello(InputStream stream) throws IOException {
        header(new DataInputStream(stream), HELLO);
    }

    static byte[] encode(List<SavedCard> cards) throws IOException {
        if (cards.isEmpty() || cards.size() > MAX_CARDS) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(bytes);
        payload.writeInt(cards.size());
        for (SavedCard card : cards) {
            byte[] name;
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(card.name));
                name = new byte[encoded.remaining()];
                encoded.get(name);
            } catch (CharacterCodingException error) { throw invalid(); }
            if (name.length > MAX_NAME_BYTES || !UidCodec.isSupportedLength(card.uid.length)) {
                throw invalid();
            }
            payload.writeShort(name.length);
            payload.write(name);
            payload.writeByte(card.uid.length);
            payload.write(card.uid);
            if (bytes.size() > MAX_BYTES) throw invalid();
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeInt(REQUEST);
        out.writeByte(VERSION);
        out.writeInt(bytes.size());
        bytes.writeTo(out);
        return frame.toByteArray();
    }

    static List<Entry> read(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        header(input, REQUEST);
        int size = input.readInt();
        if (size < 4 || size > MAX_BYTES) throw invalid();
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(bytes));
        int count = payload.readInt();
        if (count < 1 || count > MAX_CARDS) throw invalid();
        List<Entry> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int length = payload.readUnsignedShort();
            if (length > MAX_NAME_BYTES) throw invalid();
            byte[] nameBytes = new byte[length];
            payload.readFully(nameBytes);
            String name;
            try {
                name = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(nameBytes)).toString();
            } catch (CharacterCodingException error) { throw invalid(); }
            int uidLength = payload.readUnsignedByte();
            if (!UidCodec.isSupportedLength(uidLength)) throw invalid();
            byte[] uid = new byte[uidLength];
            payload.readFully(uid);
            cards.add(new Entry(name, uid));
        }
        if (payload.available() != 0) throw invalid();
        return cards;
    }

    static Merge append(List<SavedCard> existing, List<Entry> incoming, String defaultName) {
        List<SavedCard> cards = new ArrayList<>(existing);
        Set<String> seen = new HashSet<>();
        for (SavedCard card : existing) seen.add(key(card.uid));
        int skipped = 0;
        for (int i = 0; i < incoming.size(); i++) {
            Entry entry = incoming.get(i);
            if (!UidCodec.isSupportedLength(entry.uid.length)) {
                throw new IllegalArgumentException("Unsupported UID length");
            }
            if (!seen.add(key(entry.uid))) { skipped++; continue; }
            String name = entry.name.trim();
            cards.add(new SavedCard(UUID.randomUUID().toString(),
                    name.isEmpty() ? defaultName : name, entry.uid, CardPalette.defaultHue(i)));
        }
        return new Merge(cards, new Result(cards.size() - existing.size(), skipped));
    }

    static void reply(OutputStream stream, Result result) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(REPLY);
        out.writeByte(VERSION);
        out.writeInt(result.added);
        out.writeInt(result.skipped);
        out.flush();
    }

    static Result readReply(InputStream stream, int expected) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        header(in, REPLY);
        int added = in.readInt(), skipped = in.readInt();
        if (added < 0 || skipped < 0 || added > MAX_CARDS || skipped > MAX_CARDS
                || added + skipped != expected) throw invalid();
        return new Result(added, skipped);
    }

    static void sendColors(InputStream stream, OutputStream output, List<SavedCard> cards) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (!optionalHeader(in, COLOR_OFFER)) return;
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(COLORS);
        out.writeByte(VERSION);
        out.writeInt(cards.size());
        for (int i = 0; i < cards.size(); i++) out.writeFloat(cards.get(i).hueOrDefault(i));
        out.flush();
        header(in, COLORS_SAVED);
    }

    static float[] receiveColors(InputStream stream, OutputStream output, int count) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        try {
            out.writeInt(COLOR_OFFER);
            out.writeByte(VERSION);
            out.flush();
        } catch (IOException closedAfterReceipt) {
            return null;
        }
        DataInputStream in = new DataInputStream(stream);
        if (!optionalHeader(in, COLORS)) return null;
        if (count < 1 || count > MAX_CARDS || in.readInt() != count) throw invalid();
        float[] hues = new float[count];
        for (int i = 0; i < count; i++) {
            hues[i] = in.readFloat();
            if (!CardPalette.valid(hues[i])) throw invalid();
        }
        return hues;
    }

    static void colorsSaved(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(COLORS_SAVED);
        out.writeByte(VERSION);
        out.flush();
    }

    private static boolean optionalHeader(DataInputStream in, int magic) throws IOException {
        // BluetoothSocket may throw instead of returning EOF when a v1 peer closes.
        int first;
        try { first = in.read(); }
        catch (IOException closedAfterReceipt) { return false; }
        if (first < 0) return false;
        int actual = (first << 24) | (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (actual != magic || in.readUnsignedByte() != VERSION) throw invalid();
        return true;
    }

    private static void header(DataInputStream in, int magic) throws IOException {
        if (in.readInt() != magic || in.readUnsignedByte() != VERSION) throw invalid();
    }

    private static String key(byte[] uid) {
        return java.util.Arrays.toString(uid);
    }

    private static IOException invalid() {
        return new IOException("Unsupported or oversized card transfer");
    }

    private WatchCardPacket() {}
}
