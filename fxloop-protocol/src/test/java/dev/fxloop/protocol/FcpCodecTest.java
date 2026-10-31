package dev.fxloop.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FcpCodecTest {

    @Test
    void roundTripsRequestResponseAndEvent() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FcpCodec writer = new FcpCodec(InputStreamNull.INSTANCE, out);
        writer.write(FcpFrame.request(1, Fcp.Method.PING, Json.object().put("x", 1)));
        writer.write(FcpFrame.result(1, Json.object().put("ok", true)));
        writer.write(FcpFrame.error(2L, new FcpError(Fcp.Error.FX_TIMEOUT, "blocked")));
        writer.write(FcpFrame.event(Fcp.Event.UI_ERROR, Json.object().put("seq", 3)));

        FcpCodec reader = new FcpCodec(new ByteArrayInputStream(out.toByteArray()), OutputStreamNull.INSTANCE);
        FcpFrame req = reader.read();
        assertThat(req.isRequest()).isTrue();
        assertThat(req.params().get("x").asInt()).isEqualTo(1);
        FcpFrame res = reader.read();
        assertThat(res.isResponse()).isTrue();
        assertThat(res.result().get("ok").asBoolean()).isTrue();
        FcpFrame err = reader.read();
        assertThat(err.error().code()).isEqualTo(Fcp.Error.FX_TIMEOUT);
        FcpFrame evt = reader.read();
        assertThat(evt.isEvent()).isTrue();
        assertThatThrownBy(reader::read).isInstanceOf(EOFException.class);
    }

    @Test
    void rejectsOversizedFrames() {
        byte[] big = new byte[200];
        java.util.Arrays.fill(big, (byte) 'a');
        FcpCodec reader = new FcpCodec(new ByteArrayInputStream(big), OutputStreamNull.INSTANCE, 100);
        assertThatThrownBy(reader::read).hasMessageContaining("exceeds");
    }

    @Test
    void handshakeIsOwnerOnlyAndRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("hs.json");
        new Handshake(2, 42, 1234, 1L, "javaagent", null).writeTo(file);
        assertThat(Handshake.readFrom(file).port()).isEqualTo(1234);
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void launchDescriptorRoundTrips(@TempDir Path dir) throws IOException {
        LaunchDescriptor d = LaunchDescriptor.builder().buildTool("maven").mainClass("a.B")
                .classpath(List.of("/x.jar")).workingDir("/w").outputDir("/w/target/classes").build();
        Path file = dir.resolve("launch.json");
        d.writeTo(file);
        assertThat(LaunchDescriptor.readFrom(file)).isEqualTo(d);
    }

    @Test
    void tokensCompareAndRedact() {
        String t = Tokens.generate();
        assertThat(t).hasSize(32);
        assertThat(Tokens.constantTimeEquals(t, t)).isTrue();
        assertThat(Tokens.constantTimeEquals(t, "nope")).isFalse();
        assertThat(Tokens.redact("token=" + t, t)).isEqualTo("token=<redacted>");
    }

    @Test
    void pagingEnvelope() {
        var items = List.of(Json.object().put("i", 0), Json.object().put("i", 1), Json.object().put("i", 2));
        var page = Paging.page(items, 1, 1);
        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(page.get("has_more").asBoolean()).isTrue();
        assertThat(page.get("next_offset").asInt()).isEqualTo(2);
    }

    private static final class InputStreamNull extends java.io.InputStream {
        static final InputStreamNull INSTANCE = new InputStreamNull();
        @Override public int read() { return -1; }
    }

    private static final class OutputStreamNull extends java.io.OutputStream {
        static final OutputStreamNull INSTANCE = new OutputStreamNull();
        @Override public void write(int b) {}
    }
}
