package dev.fxloop.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Newline-delimited JSON framing with an enforced maximum frame size. */
public final class FcpCodec {
    private final InputStream in;
    private final OutputStream out;
    private final int maxFrameBytes;
    private final Object writeLock = new Object();

    public FcpCodec(InputStream in, OutputStream out) {
        this(in, out, Fcp.MAX_FRAME_BYTES);
    }

    public FcpCodec(InputStream in, OutputStream out, int maxFrameBytes) {
        this.in = in;
        this.out = out;
        this.maxFrameBytes = maxFrameBytes;
    }

    /** Blocks for the next frame; throws {@link EOFException} when the peer closed the stream. */
    public FcpFrame read() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("FCP stream closed");
            }
            if (b == '\n') {
                if (buf.size() == 0) {
                    continue;
                }
                return Json.MAPPER.readValue(buf.toByteArray(), FcpFrame.class);
            }
            if (buf.size() >= maxFrameBytes) {
                throw new IOException("FCP frame exceeds " + maxFrameBytes + " bytes");
            }
            buf.write(b);
        }
    }

    public void write(FcpFrame frame) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsString(frame).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFrameBytes) {
            throw new IOException("FCP frame exceeds " + maxFrameBytes + " bytes");
        }
        synchronized (writeLock) {
            out.write(bytes);
            out.write('\n');
            out.flush();
        }
    }
}
