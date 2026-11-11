package org.lab59.fxloop.server.see

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Persisted frames under `<fxloopDir>/screens/<seq>-<label>.png` with retention and a regenerated gallery
 * `index.html` (PRD 04 §1). Thread-safe.
 */
class ScreenStore(val dir: Path, private val keep: Int) {
    private val frames = ArrayList<Frame>()
    private val listeners = CopyOnWriteArrayList<(Frame) -> Unit>()
    private var seq = 0L

    init {
        if (dir.isDirectory()) {
            for (f in dir.listDirectoryEntries("*.png")) {
                val m = NAME.matchEntire(f.name) ?: continue
                val s = m.groupValues[1].toLong()
                seq = maxOf(seq, s)
                val size = ImageOps.size(Files.readAllBytes(f))
                val label = m.groupValues[2]
                frames += Frame(s, label, f, size[0], size[1], f.toFile().lastModified(), kindOf(label), f.toFile().length())
            }
            frames.sortBy { it.seq }
        }
    }

    @Synchronized
    fun save(png: ByteArray, label: String?, kind: String): Frame {
        Files.createDirectories(dir)
        val s = ++seq
        val slug = slug(label)
        val file = dir.resolve("%04d-%s.png".format(s, slug))
        Files.write(file, png)
        val size = ImageOps.size(png)
        val frame = Frame(s, slug, file, size[0], size[1], System.currentTimeMillis(), kind, png.size.toLong())
        frames += frame
        while (frames.size > keep) Files.deleteIfExists(frames.removeAt(0).file)
        Files.writeString(dir.resolve("index.html"), Gallery.render(frames.toList(), ""))
        listeners.forEach { it(frame) }
        return frame
    }

    @Synchronized fun bySeq(s: Long): Frame? = frames.firstOrNull { it.seq == s }

    @Synchronized fun latest(): Frame? = frames.lastOrNull()

    /** Latest app-face frame (screenshot or annotated) for the live viewer. */
    @Synchronized fun latestScreenshot(): Frame? = frames.lastOrNull { it.kind == "screenshot" || it.kind == "annotated" }

    @Synchronized fun list(): List<Frame> = frames.toList()

    fun onNewFrame(listener: (Frame) -> Unit) {
        listeners += listener
    }

    companion object {
        private val NAME = Regex("""^(\d{4,})-(.*)\.png$""")

        fun slug(label: String?): String {
            if (label.isNullOrBlank()) return "capture"
            val s = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return if (s.isEmpty()) "capture" else s.take(48)
        }

        fun read(file: Path): ByteArray = Files.readAllBytes(file)

        private fun kindOf(label: String) = when {
            label.startsWith("diff") -> "diff"
            label.startsWith("states") -> "states"
            else -> "screenshot"
        }
    }
}
