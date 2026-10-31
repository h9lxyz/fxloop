package dev.fxloop.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Handshake file written by the agent once its FCP socket is listening.
 *
 * @param token only present when the agent generated its own token (embedded / attach-less modes);
 *              in {@code -javaagent} mode the server already owns the token and it is never written.
 * @param mode  {@code javaagent}, {@code embedded} or {@code attach}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Handshake(int fcpVersion, long pid, int port, long startedAt, String mode, String token) {

    /** Atomic write (temp + rename) with owner-only permissions where the file system supports POSIX. */
    public void writeTo(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, Json.write(this));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Handshake readFrom(Path file) throws IOException {
        return Json.MAPPER.readValue(Files.readString(file), Handshake.class);
    }
}
