package dev.fxloop.server.loop

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
 * Secondary, human-facing watch mode (PRD 02 §3): `css-only` applies CSS on save, `all` also hot-swaps Java/FXML;
 * never restarts. Uses `io.methvin:directory-watcher` (native macOS events, JDK WatchService elsewhere) with a
 * 150 ms debounce.
 */
class WatchMode(private val apply: (List<String>) -> ObjectNode?) : AutoCloseable {
    private val log = LoggerFactory.getLogger(WatchMode::class.java)
    private val debounce = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fxloop-watch-debounce").apply { isDaemon = true } }
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
        close()
        mode = newMode
        if (newMode == "off") return
        val dirs = roots.filter { it.isDirectory() }
        if (dirs.isEmpty()) return
        watcher = DirectoryWatcher.builder().paths(dirs).listener(::onEvent).build().also { it.watchAsync() }
        log.info("watch mode {} on {} roots", newMode, dirs.size)
    }

    private fun onEvent(e: DirectoryChangeEvent) {
        val path = e.path() ?: return
        if (e.eventType() != DirectoryChangeEvent.EventType.CREATE && e.eventType() != DirectoryChangeEvent.EventType.MODIFY || e.isDirectory) return
        val name = path.name.lowercase()
        val css = name.endsWith(".css")
        val code = name.endsWith(".java") || name.endsWith(".fxml")
        synchronized(this) {
            if (!(css || mode == "all" && code)) return
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

    companion object {
        val MODES = listOf("css-only", "all", "off")
    }
}
