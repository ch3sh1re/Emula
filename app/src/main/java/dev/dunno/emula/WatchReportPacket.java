package dev.dunno.emula;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded, versioned diagnostics protocol. It cannot carry card commands or NFC requests. */
final class WatchReportPacket {
    static final int MAX_BYTES = 256 * 1024;
    private static final int HELLO = 0x45574448; // EWDH
    private static final int REQUEST = 0x45574451; // EWDQ
    private static final int REPORT = 0x45574452; // EWDR
    private static final int RECEIPT = 0x45574441; // EWDA
    private static final int VERSION = 1;

    static byte[] encode(String report) throws IOException {
        if (report == null || report.isBlank() || report.length() > MAX_BYTES) {
            throw new IOException("Empty or oversized watch report");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(report));
        if (encoded.remaining() > MAX_BYTES) throw new IOException("Oversized watch report");
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    static String readReport(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        check(data, REPORT);
        int length = data.readInt();
        if (length <= 0 || length > MAX_BYTES) throw new IOException("Invalid watch report length");
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        String report = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (report.isBlank()) throw new IOException("Empty watch report");
        return report;
    }

    static void report(OutputStream output, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid watch report length");
        DataOutputStream data = new DataOutputStream(output);
        header(data, REPORT);
        data.writeInt(bytes.length);
        data.write(bytes);
        data.flush();
    }

    static void hello(OutputStream output) throws IOException { writeHeader(output, HELLO); }
    static void request(OutputStream output) throws IOException { writeHeader(output, REQUEST); }
    static void receipt(OutputStream output) throws IOException { writeHeader(output, RECEIPT); }
    static void checkHello(InputStream input) throws IOException { check(new DataInputStream(input), HELLO); }
    static void checkRequest(InputStream input) throws IOException { check(new DataInputStream(input), REQUEST); }
    static void checkReceipt(InputStream input) throws IOException { check(new DataInputStream(input), RECEIPT); }

    private static void writeHeader(OutputStream output, int magic) throws IOException {
        DataOutputStream data = new DataOutputStream(output);
        header(data, magic);
        data.flush();
    }

    private static void header(DataOutputStream output, int magic) throws IOException {
        output.writeInt(magic);
        output.writeByte(VERSION);
    }

    private static void check(DataInputStream input, int magic) throws IOException {
        if (input.readInt() != magic || input.readUnsignedByte() != VERSION) {
            throw new IOException("Incompatible watch diagnostics protocol");
        }
    }
}
