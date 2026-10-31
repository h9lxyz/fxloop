package dev.fxloop.protocol

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Newline-delimited JSON framing with an enforced maximum frame size.
 *
 * Reads are buffered (frames can be megabytes — screenshots). Writes are serialized and chunked; [stalledForMs]
 * reports how long the current write has made no progress, so a peer that stops reading can be detected without
 * mistaking a large-but-moving frame for a stall.
 */
class FcpCodec @JvmOverloads constructor(
    input: InputStream,
    private val output: OutputStream,
    private val maxFrameBytes: Int = Fcp.MAX_FRAME_BYTES,
) {
    private val input = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
    private val writeLock = Any()

    /** 0 when idle, else the time the in-flight write last made progress. Only written under [writeLock]. */
    @Volatile
    private var lastProgressAt = 0L

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
            lastProgressAt = System.currentTimeMillis()
            try {
                var off = 0
                while (off < bytes.size) {
                    val n = minOf(CHUNK, bytes.size - off)
                    output.write(bytes, off, n)
                    off += n
                    lastProgressAt = System.currentTimeMillis()
                }
                output.write('\n'.code)
                output.flush()
            } finally {
                lastProgressAt = 0L
            }
        }
    }

    /** Milliseconds the in-flight write has been stuck without progress (0 when no write is in flight). */
    fun stalledForMs(): Long {
        val t = lastProgressAt
        return if (t == 0L) 0L else System.currentTimeMillis() - t
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
