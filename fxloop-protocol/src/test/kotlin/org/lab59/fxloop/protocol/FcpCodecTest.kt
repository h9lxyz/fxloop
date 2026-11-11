package org.lab59.fxloop.protocol

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class FcpCodecTest {
    @Test
    fun `round trips request, response, error and event`() {
        val out = ByteArrayOutputStream()
        val writer = FcpCodec(InputStream.nullInputStream(), out)
        writer.write(FcpFrame.request(1, Fcp.Method.STATUS, Json.obj { put("x", 1) }))
        writer.write(FcpFrame.result(1, Json.obj { put("ok", true) }))
        writer.write(FcpFrame.error(2, FcpError(Fcp.Error.FX_TIMEOUT, "blocked")))
        writer.write(FcpFrame.event(Fcp.Event.UI_ERROR, Json.obj { put("seq", 3) }))

        val reader = FcpCodec(ByteArrayInputStream(out.toByteArray()), OutputStream.nullOutputStream())
        val req = reader.read()
        assertThat(req.isRequest).isTrue()
        assertThat(req.params!!["x"].asInt()).isEqualTo(1)
        val res = reader.read()
        assertThat(res.isResponse).isTrue()
        assertThat(res.result!!["ok"].asBoolean()).isTrue()
        assertThat(reader.read().error!!.code).isEqualTo(Fcp.Error.FX_TIMEOUT)
        assertThat(reader.read().isEvent).isTrue()
        assertThatThrownBy { reader.read() }.isInstanceOf(EOFException::class.java)
    }

    @Test
    fun `rejects oversized frames`() {
        val reader = FcpCodec(ByteArrayInputStream(ByteArray(200) { 'a'.code.toByte() }), OutputStream.nullOutputStream(), 100)
        assertThatThrownBy { reader.read() }.hasMessageContaining("exceeds")
    }

    @Test
    fun `handshake is owner only and round trips`(@TempDir dir: Path) {
        val file = dir.resolve("hs.json")
        Handshake(2, 42, 1234, 1L, "javaagent", null).writeTo(file)
        assertThat(Handshake.readFrom(file).port).isEqualTo(1234)
        assertThat(Handshake.readFrom(file).inspectorEdit).isNull()
        assertThat(Files.readString(file)).doesNotContain("inspectorEdit")
        Handshake(2, 42, 1234, 1L, "embedded", "t", inspectorEdit = true).writeTo(file)
        assertThat(Handshake.readFrom(file).inspectorEdit).isTrue()
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------")
        }
    }

    @Test
    fun `tokens compare and redact`() {
        val t = Tokens.generate()
        assertThat(t).hasSize(32)
        assertThat(Tokens.constantTimeEquals(t, t)).isTrue()
        assertThat(Tokens.constantTimeEquals(t, "nope")).isFalse()
        assertThat(Tokens.redact("token=$t", t)).isEqualTo("token=<redacted>")
    }

    @Test
    fun `paging envelope`() {
        val page = Paging.page((0..2).map { i -> Json.obj { put("i", i) } }, 1, 1)
        assertThat(page["total"].asInt()).isEqualTo(3)
        assertThat(page["has_more"].asBoolean()).isTrue()
        assertThat(page["next_offset"].asInt()).isEqualTo(2)
    }
}
