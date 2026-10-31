package dev.fxloop.protocol

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Handshake file written by the agent once its FCP socket is listening.
 *
 * @property token only present when the agent generated its own token (embedded / attach-less modes);
 *   in `-javaagent` mode the server already owns the token and it is never written.
 * @property mode `javaagent`, `embedded` or `attach`
 * @property inspectorEdit `true` when the developer started the app with the inspector's "Try it live" on
 *   (`fxloop:run` / `fxloopRun`): a server connecting to it enables the scratch-CSS endpoints for this app (PRD 08 §5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Handshake @JsonCreator constructor(
    @param:JsonProperty("fcpVersion") val fcpVersion: Int,
    @param:JsonProperty("pid") val pid: Long,
    @param:JsonProperty("port") val port: Int,
    @param:JsonProperty("startedAt") val startedAt: Long,
    @param:JsonProperty("mode") val mode: String?,
    @param:JsonProperty("token") val token: String?,
    @param:JsonProperty("inspectorEdit") val inspectorEdit: Boolean? = null,
) {
    /** Atomic write (temp + rename) with owner-only permissions where the file system supports POSIX. */
    fun writeTo(file: Path) {
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.deleteIfExists(tmp)
        OwnerOnly.write(tmp, Json.write(this))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        @JvmStatic
        fun readFrom(file: Path): Handshake = Json.MAPPER.readValue(Files.readString(file), Handshake::class.java)
    }
}
