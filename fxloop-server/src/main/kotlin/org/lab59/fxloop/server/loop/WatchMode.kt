package org.lab59.fxloop.server.loop

import com.fasterxml.jackson.databind.node.ObjectNode
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Secondary, human-facing watch mode (PRD 02 §3): `css-only` applies CSS on save, `all` also swaps FXML and hot-swaps
 * Java/Kotlin; never restarts. Uses `io.methvin:directory-watcher` (native macOS events, JDK WatchService elsewhere) with a
 * 150 ms debounce.
 */
class WatchMode(private val apply: (List<String>) -> ObjectNode?) : AutoCloseable {
    private val log = LoggerFactory.getLogger(WatchMode::class.java)
    private val debounce = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fxloop-watch-debounce").apply { isDaemon = true } }
    private val events = Executors.newSingleThreadExecutor { r -> Thread(r, "fxloop-watch-events").apply { isDaemon = true } }
    private var roots: List<Path> = emptyList()
    private val pending = LinkedHashSet<Path>()
    private var watcher: DirectoryWatcher? = null
    private var scheduled: ScheduledFuture<*>? = null

    @get:Synchronized
    var mode = "off"
        private set

    @Volatile var lastResult: ObjectNode? = null
        private set

    @Synchronized
    fun set(newMode: String, roots: List<Path>) {
        require(newMode in MODES) { "watchMode must be one of $MODES" }
        if (newMode == mode && roots == this.roots && (watcher != null || newMode == "off")) return // relaunch: keep it
        close()
        mode = newMode
        this.roots = roots
        if (newMode == "off") return
        val dirs = roots.filter { it.isDirectory() }
        if (dirs.isEmpty()) return
        watcher = DirectoryWatcher.builder().paths(dirs).listener(::onEvent).build().also { it.watchAsync(events) }
        log.info("watch mode {} on {} roots", newMode, dirs.size)
    }

    private fun onEvent(e: DirectoryChangeEvent) {
        val path = e.path() ?: return
        if (e.eventType() != DirectoryChangeEvent.EventType.CREATE && e.eventType() != DirectoryChangeEvent.EventType.MODIFY || e.isDirectory) return
        synchronized(this) {
            if (!applies(mode, path.name)) return
            pending.add(path)
            scheduled?.cancel(false)
            scheduled = debounce.schedule(::flush, 150, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() {
        val files = synchronized(this) { pending.map(Path::toString).also { pending.clear() } }
        if (files.isEmpty()) return
        try {
            lastResult = apply(files)
        } catch (ex: RuntimeException) {
            log.warn("watch-mode reload failed: {}", ex.message)
        }
    }

    @Synchronized
    override fun close() {
        runCatching { watcher?.close() }
        watcher = null
    }

    /** Final teardown (server shutdown): stops the watcher and both executors. */
    fun dispose() {
        close()
        debounce.shutdownNow()
        events.shutdownNow()
    }

    companion object {
        val MODES = listOf("css-only", "all", "off")

        /** Whether a save of [fileName] is applied in [mode]: CSS always, FXML/Java/Kotlin only in `all`. */
        fun applies(mode: String, fileName: String): Boolean {
            val name = fileName.lowercase()
            return when (mode) {
                "off" -> false
                "all" -> name.endsWith(".css") || name.endsWith(".fxml") || name.endsWith(".java") || name.endsWith(".kt")
                else -> name.endsWith(".css")
            }
        }
    }
}
