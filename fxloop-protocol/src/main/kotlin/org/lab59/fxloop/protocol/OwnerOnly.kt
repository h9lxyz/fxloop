package org.lab59.fxloop.protocol

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Writes files readable only by the current user (0600) where the file system supports POSIX permissions. */
object OwnerOnly {
    @JvmStatic
    fun write(file: Path, content: String): Path {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.deleteIfExists(file)
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(file)
        }
        Files.writeString(file, content)
        return file
    }
}
