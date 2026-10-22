package com.bedouyu.audiorag.tools.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Checks the container and sample layout without loading the audio payload. */
public final class WavVerifier {
    private static final int RIFF = 0x46464952;
    private static final int WAVE = 0x45564157;
    private static final int FORMAT = 0x20746d66;
    private static final int DATA = 0x61746164;

    private WavVerifier() {}

    public static void validate(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 12) {
                throw invalid("truncated RIFF header");
            }
            ByteBuffer header = read(channel, 0, 12);
            if (header.getInt() != RIFF) {
                throw invalid("missing RIFF signature");
            }
            long riffSize = Integer.toUnsignedLong(header.getInt());
            if (header.getInt() != WAVE) {
                throw invalid("missing WAVE signature");
            }
            if (riffSize < 4 || riffSize + 8 != fileSize) {
                throw invalid("RIFF size does not match the file length");
            }

            int blockAlignment = 0;
            long dataLengthDivisor = 0;
            long position = 12;
            while (position < fileSize) {
                if (fileSize - position < 8) {
                    throw invalid("truncated chunk header");
                }
                ByteBuffer chunk = read(channel, position, 8);
                int type = chunk.getInt();
                long length = Integer.toUnsignedLong(chunk.getInt());
                long payloadPosition = position + 8;
                long paddedLength = length + (length & 1);
                if (paddedLength > fileSize - payloadPosition) {
                    throw invalid("chunk data or padding exceeds the file length");
                }

                if (type == FORMAT) {
                    if (blockAlignment != 0) {
                        throw invalid("multiple fmt chunks");
                    }
                    blockAlignment = validateFormat(channel, payloadPosition, length);
                } else if (type == DATA) {
                    if (length == 0) {
                        throw invalid("empty data chunk");
                    }
                    // The divisor checks every data chunk, including those before fmt,
                    // without retaining a list proportional to the number of chunks.
                    dataLengthDivisor = gcd(dataLengthDivisor, length);
                }
                position = payloadPosition + paddedLength;
            }

            if (blockAlignment == 0) {
                throw invalid("missing fmt chunk");
            }
            if (dataLengthDivisor == 0) {
                throw invalid("missing data chunk");
            }
            if (dataLengthDivisor % blockAlignment != 0) {
                throw invalid("data length is not a whole number of audio frames");
            }
            if (channel.size() != fileSize) {
                throw invalid("file length changed during validation");
            }
        }
    }

    private static int validateFormat(FileChannel channel, long position, long length) throws IOException {
        if (length < 16) {
            throw invalid("fmt chunk is shorter than 16 bytes");
        }
        ByteBuffer format = read(channel, position, (int) Math.min(length, 40));
        int code = Short.toUnsignedInt(format.getShort());
        int channels = Short.toUnsignedInt(format.getShort());
        long sampleRate = Integer.toUnsignedLong(format.getInt());
        long byteRate = Integer.toUnsignedLong(format.getInt());
        int blockAlignment = Short.toUnsignedInt(format.getShort());
        int bits = Short.toUnsignedInt(format.getShort());
        if (channels == 0 || channels > 64 || sampleRate == 0 || sampleRate > 768_000
                || bits == 0 || bits > 64) {
            throw invalid("invalid channel count, sample rate, or sample width");
        }

        int extensionSize = 0;
        if (length > 16) {
            if (length < 18) {
                throw invalid("truncated fmt extension length");
            }
            extensionSize = Short.toUnsignedInt(format.getShort(16));
            if (extensionSize > length - 18) {
                throw invalid("fmt extension exceeds the chunk length");
            }
        }
        if (code == 0xfffe) {
            if (length < 40 || extensionSize < 22) {
                throw invalid("truncated extensible fmt chunk");
            }
            int validBits = Short.toUnsignedInt(format.getShort(18));
            if (validBits == 0 || validBits > bits || bits % 8 != 0) {
                throw invalid("invalid extensible sample width");
            }
            // Subtypes use the standard KSDATAFORMAT_SUBTYPE_* GUID suffix.
            if (format.getInt(28) != 0x00100000 || format.getLong(32) != 0x719b3800aa000080L) {
                throw invalid("unsupported extensible audio subtype");
            }
            int subtype = format.getInt(24);
            if (subtype != 1 && subtype != 3) {
                throw invalid("unsupported extensible audio subtype");
            }
            code = subtype;
        }
        if (code != 1 && code != 3) {
            throw invalid("unsupported audio format");
        }
        if (code == 3 && bits != 32 && bits != 64) {
            throw invalid("floating point audio must use 32 or 64 bits");
        }
        int expectedAlignment = channels * ((bits + 7) / 8);
        if (blockAlignment != expectedAlignment || byteRate != sampleRate * blockAlignment) {
            throw invalid("inconsistent block alignment or byte rate");
        }
        return blockAlignment;
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position + buffer.position());
            if (count < 0) {
                throw invalid("file ended during validation");
            }
            if (count == 0) {
                throw invalid("unable to read WAV header");
            }
        }
        buffer.flip();
        return buffer;
    }

    private static long gcd(long first, long second) {
        while (second != 0) {
            long remainder = first % second;
            first = second;
            second = remainder;
        }
        return first;
    }

    private static IOException invalid(String reason) {
        return new IOException("Invalid WAV: " + reason);
    }
}
