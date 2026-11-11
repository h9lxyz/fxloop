package org.lab59.fxloop.server.see

import java.nio.file.Path

/**
 * One persisted capture.
 *
 * @property seq monotonically increasing across the server lifetime (and restarts of the app)
 * @property label slugified label
 * @property kind `screenshot`, `annotated`, `diff`, `states` or `file`
 */
data class Frame(
    val seq: Long,
    val label: String,
    val file: Path,
    val w: Int,
    val h: Int,
    val ts: Long,
    val kind: String,
    val bytes: Long,
)
