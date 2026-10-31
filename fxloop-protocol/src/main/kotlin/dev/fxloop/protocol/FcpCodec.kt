package dev.fxloop.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Newline-delimited JSON framing with an enforced maximum frame size. */
class FcpCodec @JvmOverloads constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxFrameBytes: Int = Fcp.MAX_FRAME_BYTES,
) {
    private val writeLock = Any()

    /** Blocks for the next frame; throws [EOFException] when the peer closed the stream. */
    fun read(): FcpFrame {
        val buf = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("FCP stream closed")
            if (b == '\n'.code) {
                if (buf.size() == 0) continue
                return Json.MAPPER.readValue(buf.toByteArray(), FcpFrame::class.java)
            }
            if (buf.size() >= maxFrameBytes) throw IOException("FCP frame exceeds $maxFrameBytes bytes")
            buf.write(b)
        }
    }

    fun write(frame: FcpFrame) {
        val bytes = Json.MAPPER.writeValueAsBytes(frame)
        if (bytes.size > maxFrameBytes) throw IOException("FCP frame exceeds $maxFrameBytes bytes")
        synchronized(writeLock) {
            output.write(bytes)
            output.write('\n'.code)
            output.flush()
        }
    }
}
