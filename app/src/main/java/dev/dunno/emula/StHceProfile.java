package dev.dunno.emula;

import java.util.Arrays;

/** The fields owned by programHceParameters in the FP5 and sapphiren ST SDKs. */
final class StHceProfile {
    interface Access extends MotorolaStNfcController.Api {
        byte[] readHceProfile() throws Exception;
        void restoreHceProfile(byte[] profile) throws Exception;
    }

    private static final int UID_LENGTH = 20;
    private static final int SAK = 26;
    private static final int UID_START = 72;
    // ST's single-byte SDK payload length includes six bytes ahead of this record.
    private static final int MAX_RECORD_BYTES = 255 - 6;
    private static final byte TYPE_4A_SAK = 0x20;
    final byte[] original;
    final byte[] uid;

    StHceProfile(byte[] original, byte[] uid) {
        if (uid == null || !UidCodec.isSupportedLength(uid.length) || original == null
                || original.length < UID_START + uid.length || original.length > MAX_RECORD_BYTES) {
            throw new IllegalStateException("Incomplete or unsupported ST HCE profile");
        }
        if (original[UID_LENGTH] != 0) throw new IllegalStateException("An existing ST HCE profile is active");
        this.original = original.clone();
        this.uid = uid.clone();
    }

    static StHceProfile capture(Access api, byte[] uid) throws Exception {
        StHceProfile profile = new StHceProfile(api.readHceProfile(), uid);
        if (!Arrays.equals(profile.original, api.readHceProfile())) {
            throw new IllegalStateException("ST HCE profile changed during snapshot");
        }
        return profile;
    }

    void verifyActive(byte[] live) {
        requireLength(live);
        byte[] expected = original.clone();
        expected[UID_LENGTH] = (byte) uid.length;
        expected[SAK] = TYPE_4A_SAK;
        System.arraycopy(uid, 0, expected, UID_START, uid.length);
        if (!Arrays.equals(live, expected)) {
            throw new IllegalStateException("ST HCE profile activation readback mismatch");
        }
    }

    /** Refuse unexpected ownership before disabling the service or repairing any saved bytes. */
    void checkOwnership(byte[] live) {
        requireLength(live);
        checkByte(live, UID_LENGTH, (byte) uid.length);
        checkByte(live, SAK, TYPE_4A_SAK);
        for (int i = 0; i < uid.length; i++) checkByte(live, UID_START + i, uid[i]);
    }

    private void checkByte(byte[] live, int index, byte active) {
        if (live[index] != original[index] && live[index] != active) {
            throw new IllegalStateException("ST HCE fields changed outside this session");
        }
    }

    void restore(Access api, boolean activationVerified) throws Exception {
        byte[] live = api.readHceProfile();
        checkOwnership(live);
        byte[] restored = live.clone();
        restored[UID_LENGTH] = original[UID_LENGTH];
        restored[SAK] = original[SAK];
        System.arraycopy(original, UID_START, restored, UID_START, uid.length);
        if (!Arrays.equals(live, restored)) {
            if (!Arrays.equals(live, api.readHceProfile())) {
                throw new IllegalStateException("ST HCE profile changed during restore");
            }
            api.restoreHceProfile(restored);
        }
        if (!Arrays.equals(restored, api.readHceProfile())) {
            throw new IllegalStateException("ST HCE profile restoration readback mismatch");
        }
        // Before activation is verified an unexpected field could be an undocumented
        // service side effect. Keep the original for recovery without overwriting that field.
        if (!activationVerified && !Arrays.equals(original, restored)) {
            throw new IllegalStateException("ST HCE profile changed outside the verified fields during activation");
        }
    }

    private void requireLength(byte[] live) {
        if (live == null || live.length != original.length) {
            throw new IllegalStateException("ST HCE profile readback length changed");
        }
    }
}
