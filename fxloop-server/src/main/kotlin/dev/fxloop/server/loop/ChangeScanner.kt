package dev.fxloop.server.loop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * Deterministic change detection for `fx_reload`: compares file modification times under the watched roots against
 * the last checkpoint (taken at launch and after every reload).
 */
class ChangeScanner {
    data class Change(val file: Path, val deleted: Boolean)

    private var baseline: Map<Path, Long> = emptyMap()

    @Synchronized
    fun checkpoint(roots: List<Path>) {
        baseline = scan(roots)
    }

    @Synchronized
    fun changes(roots: List<Path>): List<Change> {
        val now = scan(roots)
        return now.filter { (p, t) -> baseline[p] != t }.map { Change(it.key, false) } +
            (baseline.keys - now.keys).map { Change(it, true) }
    }

    private fun scan(roots: List<Path>): Map<Path, Long> = buildMap {
        for (r in roots) {
            when {
                r.isRegularFile() -> put(r, r.toFile().lastModified())
                r.isDirectory() -> runCatching {
                    Files.walk(r).use { s -> s.asSequence().filter { it.isRegularFile() }.forEach { put(it, it.toFile().lastModified()) } }
                }
            }
        }
    }
}
