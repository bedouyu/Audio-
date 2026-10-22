package com.bedouyu.audiorag.tools.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WavVerifierTest {
    @TempDir
    Path directory;

    @Test
    void acceptsMinimalPcmWave() throws IOException {
        byte[] wave = wave(chunk("fmt ", pcmFormat()), chunk("data", new byte[8]));
        assertEquals(52, wave.length);
        assertEquals(44, buffer(wave).getInt(4));
        assertDoesNotThrow(() -> WavVerifier.validate(write(wave)));
    }

    @Test
    void acceptsPcmWithAnEmptyFormatExtension() {
        byte[] format = Arrays.copyOf(pcmFormat(), 18);
        assertDoesNotThrow(() -> WavVerifier.validate(write(wave(
                chunk("fmt ", format), chunk("data", new byte[8])))));
    }

    @Test
    void acceptsEightBitPcmWithOddDataLengthAndPadding() {
        byte[] format = format(1, 1, 8_000, 8_000, 1, 8);
        assertDoesNotThrow(() -> WavVerifier.validate(write(wave(
                chunk("fmt ", format), chunk("data", new byte[] {(byte) 128})))));
    }

    @Test
    void acceptsFloatingPointWave() {
        byte[] format = format(3, 2, 48_000, 384_000, 8, 32);
        assertDoesNotThrow(() -> WavVerifier.validate(write(wave(
                chunk("fmt ", format), chunk("data", new byte[16])))));
    }

    @Test
    void acceptsExtensiblePcmWave() {
        assertDoesNotThrow(() -> WavVerifier.validate(write(wave(
                chunk("fmt ", extensibleFormat()), chunk("data", new byte[8])))));
    }

    @Test
    void acceptsExtensibleFloatingPointWave() {
        byte[] format = extensibleFormat();
        buffer(format).putInt(8, 64_000).putShort(12, (short) 4).putShort(14, (short) 32)
                .putShort(18, (short) 32).putInt(24, 3);
        assertDoesNotThrow(() -> WavVerifier.validate(write(wave(
                chunk("fmt ", format), chunk("data", new byte[8])))));
    }

    @Test
    void acceptsOddAncillaryChunksWithPadding() {
        byte[] ancillary = chunk("JUNK", new byte[] {1, 2, 3});
        ancillary[ancillary.length - 1] = 42;
        assertDoesNotThrow(() -> WavVerifier.validate(write(wave(
                chunk("fmt ", pcmFormat()), ancillary, chunk("data", new byte[8])))));
    }

    @Test
    void checksAllDataChunksEvenWhenFormatFollowsData() {
        assertDoesNotThrow(() -> WavVerifier.validate(write(wave(
                chunk("data", new byte[4]), chunk("fmt ", pcmFormat()), chunk("data", new byte[8])))));
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("data", new byte[3]), chunk("fmt ", pcmFormat()), chunk("data", new byte[5])))));
    }

    @Test
    void rejectsBadRiffAndWaveSignatures() throws IOException {
        for (int offset : new int[] {0, 8}) {
            byte[] wave = validWave();
            wave[offset] = 'X';
            assertThrows(IOException.class, () -> WavVerifier.validate(write(wave)));
        }
    }

    @Test
    void rejectsTruncatedRiffHeader() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(new byte[11])));
    }

    @Test
    void rejectsWrongRiffLengthIncludingUnsignedValues() throws IOException {
        for (int declaredSize : new int[] {0, 43, 45, -8, -1}) {
            byte[] wave = validWave();
            buffer(wave).putInt(4, declaredSize);
            assertThrows(IOException.class, () -> WavVerifier.validate(write(wave)));
        }
    }

    @Test
    void rejectsMissingFormat() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(chunk("data", new byte[8])))));
    }

    @Test
    void rejectsMissingData() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(chunk("fmt ", pcmFormat())))));
    }

    @Test
    void rejectsEmptyAndShortFormatChunks() {
        for (int length : new int[] {0, 1, 15}) {
            assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                    chunk("fmt ", new byte[length]), chunk("data", new byte[8])))));
        }
    }

    @Test
    void rejectsEmptyData() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", pcmFormat()), chunk("data", new byte[0])))));
    }

    @Test
    void rejectsTruncatedChunkHeader() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", pcmFormat()), chunk("data", new byte[8]), new byte[7]))));
    }

    @Test
    void rejectsTruncatedChunkBodyAndUnsignedOversizedChunks() throws IOException {
        for (int length : new int[] {10, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            byte[] wave = validWave();
            buffer(wave).putInt(40, length);
            assertThrows(IOException.class, () -> WavVerifier.validate(write(wave)));
        }
    }

    @Test
    void rejectsMissingOddChunkPadding() {
        byte[] ancillary = chunk("JUNK", new byte[] {1, 2, 3});
        byte[] withoutPadding = Arrays.copyOf(ancillary, ancillary.length - 1);
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", pcmFormat()), chunk("data", new byte[8]), withoutPadding))));
    }

    @Test
    void rejectsIncompleteAudioFrames() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", pcmFormat()), chunk("data", new byte[3])))));
    }

    @Test
    void rejectsUnsupportedAudioFormat() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", format(6, 1, 16_000, 32_000, 2, 16)), chunk("data", new byte[8])))));
    }

    @Test
    void rejectsInvalidAudioGeometry() {
        byte[][] invalidFormats = {
                format(1, 0, 16_000, 32_000, 2, 16),
                format(1, 65, 16_000, 2_080_000, 130, 16),
                format(1, 1, 0, 0, 2, 16),
                format(1, 1, 0xffffffffL, 32_000, 2, 16),
                format(1, 1, 16_000, 32_000, 2, 0),
                format(1, 1, 16_000, 144_000, 9, 72),
                format(1, 1, 16_000, 32_000, 0, 16),
                format(1, 1, 16_000, 16_000, 1, 16),
                format(1, 1, 16_000, 32_001, 2, 16),
                format(1, 1, 16_000, 0, 2, 16)
        };
        for (byte[] format : invalidFormats) {
            assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                    chunk("fmt ", format), chunk("data", new byte[8])))));
        }
    }

    @Test
    void rejectsInvalidFloatingPointWidth() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", format(3, 1, 16_000, 32_000, 2, 16)), chunk("data", new byte[8])))));
    }

    @Test
    void rejectsTruncatedFormatExtension() {
        byte[] format = Arrays.copyOf(pcmFormat(), 17);
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", format), chunk("data", new byte[8])))));
        byte[] overlongExtension = Arrays.copyOf(pcmFormat(), 18);
        buffer(overlongExtension).putShort(16, (short) 1);
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", overlongExtension), chunk("data", new byte[8])))));
    }

    @Test
    void rejectsMalformedExtensibleFormats() {
        byte[] truncated = Arrays.copyOf(extensibleFormat(), 38);
        byte[] missingExtension = extensibleFormat();
        buffer(missingExtension).putShort(16, (short) 0);
        byte[] invalidWidth = extensibleFormat();
        buffer(invalidWidth).putShort(18, (short) 17);
        byte[] invalidSubtype = extensibleFormat();
        buffer(invalidSubtype).putInt(24, 6);
        byte[] invalidGuid = extensibleFormat();
        invalidGuid[39] = 0;
        for (byte[] format : new byte[][] {truncated, missingExtension, invalidWidth, invalidSubtype, invalidGuid}) {
            assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                    chunk("fmt ", format), chunk("data", new byte[8])))));
        }
    }

    @Test
    void rejectsDuplicateFormatChunks() {
        assertThrows(IOException.class, () -> WavVerifier.validate(write(wave(
                chunk("fmt ", pcmFormat()), chunk("fmt ", pcmFormat()), chunk("data", new byte[8])))));
    }

    private Path write(byte[] bytes) throws IOException {
        Path file = Files.createTempFile(directory, "wave-", ".wav");
        Files.write(file, bytes);
        return file;
    }

    private static byte[] validWave() {
        return wave(chunk("fmt ", pcmFormat()), chunk("data", new byte[8]));
    }

    private static byte[] pcmFormat() {
        return format(1, 1, 16_000, 32_000, 2, 16);
    }

    private static byte[] format(int code, int channels, long sampleRate, long byteRate, int alignment, int bits) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) code).putShort((short) channels)
                .putInt((int) sampleRate).putInt((int) byteRate)
                .putShort((short) alignment).putShort((short) bits).array();
    }

    private static byte[] extensibleFormat() {
        byte[] bytes = Arrays.copyOf(format(0xfffe, 1, 16_000, 32_000, 2, 16), 40);
        buffer(bytes).putShort(16, (short) 22).putShort(18, (short) 16).putInt(20, 0)
                .putInt(24, 1).putInt(28, 0x00100000).putLong(32, 0x719b3800aa000080L);
        return bytes;
    }

    private static byte[] chunk(String id, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length + (payload.length & 1)).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.getBytes(StandardCharsets.US_ASCII)).putInt(payload.length).put(payload).array();
    }

    private static byte[] wave(byte[]... chunks) {
        int length = 12;
        for (byte[] chunk : chunks) {
            length += chunk.length;
        }
        ByteBuffer result = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(length - 8)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        for (byte[] chunk : chunks) {
            result.put(chunk);
        }
        return result.array();
    }

    private static ByteBuffer buffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
