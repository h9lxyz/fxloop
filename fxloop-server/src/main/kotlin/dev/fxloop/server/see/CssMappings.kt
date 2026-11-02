package dev.fxloop.server.see

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * The developer's stylesheet mappings (PRD 08 §5): a sheet the app loads from outside the project (a library jar,
 * another module's build output) → the local source file it is built from, so the inspector's *Apply to file* may write
 * there and `file:line` points at it. Kept per project in `<projectDir>/.fxloop/css-map.json` (the folder ignores
 * itself in git), so a `mvn clean` does not forget them; the files are machine-specific absolute paths.
 *
 * [outputDir] is the app's build output: a mapping must name a source file, not a build copy.
 */
class CssMappings(private val projectDir: Path, private val outputDir: () -> Path? = { null }) : SheetMap {
    class Mapping(val url: String, val file: Path)

    private val log = LoggerFactory.getLogger(CssMappings::class.java)
    private val store = projectDir.resolve(DIR).resolve(FILE)
    private var cache: List<Mapping>? = null

    @Synchronized
    fun list(): List<Mapping> = cache ?: load().also { cache = it }

    /** `[{url, file}]`, the `cssMappings` the agent's CSS engine takes. */
    fun toJson(): ArrayNode = Json.array().also { arr -> list().forEach { arr.addObject().put("url", it.url).put("file", it.file.toString()) } }

    /** Sheet URLs mapped to [file] (mappings hold real paths, so symlinked spellings of it match too). */
    fun urlsFor(file: Path): List<String> {
        val p = file.toAbsolutePath().normalize()
        val real = runCatching { p.toRealPath() }.getOrDefault(p)
        return list().filter { it.file == p || it.file == real }.map { it.url }
    }

    fun isMapped(file: Path): Boolean = urlsFor(file).isNotEmpty()

    /**
     * Maps the sheet [url] to [file] (absolute, project-relative or `~/…`); a blank [file] removes the mapping.
     * Returns `{url, file?}`; throws [IllegalArgumentException] with a message for the developer on bad input.
     */
    @Synchronized
    override fun set(url: String, file: String?): ObjectNode {
        val key = sheetKey(url)
        val all = list()
        val rest = all.filter { it.url != key }
        val source = file?.trim()?.takeIf { it.isNotEmpty() }?.let(::sourceFile)
        val next = if (source == null) rest else rest + Mapping(key, source)
        if (source != null || rest.size != all.size) save(next)
        cache = next
        return Json.obj {
            put("url", key)
            if (source != null) put("file", source.toString()) else putNull("file")
        }
    }

    /**
     * Existing source files [url] is likely built from, best first: next to an existing mapping's source root, in the
     * module behind a build-output directory, or under `src/main/resources` of this project, its parent and their
     * subprojects. `{url, resource?, suggestions:[file]}`.
     */
    override fun suggest(url: String): ObjectNode {
        val key = sheetKey(url)
        val (module, entry) = locate(key)
        val found = LinkedHashSet<Path>()
        fun offer(p: Path) {
            val f = p.toAbsolutePath().normalize()
            if (found.size < MAX_SUGGESTIONS && Files.isRegularFile(f) && !isBuildCopy(f)) found.add(f)
        }
        if (entry != null) {
            val rel = runCatching { Path.of(entry) }.getOrNull()
            if (rel != null) {
                for (m in list()) {
                    val e = locate(m.url).second?.let { runCatching { Path.of(it) }.getOrNull() } ?: continue
                    if (m.file.endsWith(e) && m.file.nameCount > e.nameCount) offer(root(m.file, e).resolve(rel))
                }
                module?.let { offer(it.resolve(RESOURCES).resolve(rel)) }
                for (dir in projectDirs()) offer(dir.resolve(RESOURCES).resolve(rel))
            }
        }
        return Json.obj {
            put("url", key)
            entry?.let { put("resource", it) }
            set<JsonNode>("suggestions", Json.strings(found.map { it.toString() }))
        }
    }

    /** The file a mapping may name: an existing `.css` file outside the app's build output. */
    private fun sourceFile(raw: String): Path {
        val expanded = when {
            raw == "~" -> System.getProperty("user.home")
            raw.startsWith("~/") || raw.startsWith("~\\") -> System.getProperty("user.home") + raw.substring(1)
            else -> raw
        }
        val p = try {
            projectDir.resolve(expanded).toAbsolutePath().normalize()
        } catch (e: InvalidPathException) {
            throw IllegalArgumentException("not a valid path: $raw")
        }
        if (!p.fileName.toString().lowercase().endsWith(".css")) throw IllegalArgumentException("$p is not a .css file")
        if (!Files.isRegularFile(p)) throw IllegalArgumentException("$p does not exist")
        val real = p.toRealPath()
        if (isBuildCopy(real)) throw IllegalArgumentException("$p is a build copy; map the stylesheet to its source file instead")
        return real
    }

    private fun isBuildCopy(p: Path): Boolean {
        val out = outputDir()?.let { runCatching { it.toRealPath() }.getOrDefault(it.toAbsolutePath().normalize()) } ?: return false
        return p.startsWith(out)
    }

    /** This project, its parent folder, and their direct subfolders (plus one more level below build-tool projects). */
    private fun projectDirs(): List<Path> {
        val out = LinkedHashSet<Path>()
        val parent = projectDir.parent
        out.add(projectDir)
        if (parent != null) out.add(parent)
        for (base in listOfNotNull(projectDir, parent)) {
            for (child in children(base)) {
                out.add(child)
                if (BUILD_FILES.any { Files.exists(child.resolve(it)) }) out.addAll(children(child))
                if (out.size >= MAX_DIRS) return out.toList()
            }
        }
        return out.toList()
    }

    private fun children(dir: Path): List<Path> = runCatching {
        Files.list(dir).use { s -> s.filter { it.isDirectory() && !it.fileName.toString().startsWith(".") }.limit(MAX_CHILDREN).toList() }
    }.getOrDefault(emptyList())

    private fun load(): List<Mapping> = try {
        if (!Files.isRegularFile(store)) emptyList()
        else Json.parse(Files.readString(store)).path("mappings").mapNotNull { m ->
            val url = m.path("url").asText("")
            val file = m.path("file").asText("")
            if (url.isEmpty() || file.isEmpty()) null else Mapping(url, Path.of(file).toAbsolutePath().normalize())
        }
    } catch (e: Exception) {
        log.warn("ignoring unreadable {}: {}", store, e.toString())
        emptyList()
    }

    private fun save(list: List<Mapping>) {
        val dir = store.parent
        Files.createDirectories(dir)
        val ignore = dir.resolve(".gitignore")
        if (!Files.exists(ignore)) Files.writeString(ignore, "# local FXLoop state, created automatically\n*\n")
        val json = Json.obj {
            val arr = putArray("mappings")
            list.forEach { arr.addObject().put("url", it.url).put("file", it.file.toString()) }
        }
        val tmp = dir.resolve("$FILE.tmp")
        Files.writeString(tmp, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n")
        Files.move(tmp, store, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val DIR = ".fxloop"
        const val FILE = "css-map.json"
        private const val RESOURCES = "src/main/resources"
        private const val MAX_SUGGESTIONS = 5
        private const val MAX_DIRS = 400
        private const val MAX_CHILDREN = 200L
        private val BUILD_FILES = listOf("pom.xml", "build.gradle", "build.gradle.kts")

        /** Build-output folders below a module: what follows them is the resource path. IntelliJ's `out/production/<m>` has no module dir. */
        private val OUTPUT_MARKERS = listOf("/target/classes/", "/build/resources/main/", "/build/classes/java/main/",
            "/build/classes/kotlin/main/", "/bin/main/")

        /**
         * A sheet URL as mappings store it: `jar:`, `jrt:` and `file:` URLs only, without the reload query that
         * `fx_reload` appends.
         */
        fun sheetKey(url: String): String {
            val bare = url.trim().substringBefore('?')
            require(bare.isNotEmpty() && bare.length <= MAX_URL && bare.none { it.isISOControl() }) { "not a stylesheet URL" }
            val scheme = bare.substringBefore(':', "").lowercase()
            require(scheme in setOf("jar", "jrt", "file")) { "only stylesheets loaded from a jar:, jrt: or file: URL can be mapped" }
            return bare
        }

        private const val MAX_URL = 4096

        /**
         * The module folder (for a build-output directory) and the resource path of a sheet URL: the entry of a jar or
         * runtime-module sheet, or the path below `target/classes` and friends.
         */
        fun locate(url: String): Pair<Path?, String?> {
            val bare = url.substringBefore('?')
            return when {
                bare.startsWith("jar:", ignoreCase = true) -> null to bare.substringAfter("!/", "").ifEmpty { null }
                bare.startsWith("jrt:/", ignoreCase = true) -> null to bare.removePrefix("jrt:/").substringAfter('/', "").ifEmpty { null }
                bare.startsWith("file:", ignoreCase = true) -> {
                    val path = runCatching { Path.of(URI(bare)) }.getOrNull() ?: return null to null
                    val s = path.toString().replace('\\', '/')
                    for (m in OUTPUT_MARKERS) {
                        val i = s.indexOf(m)
                        if (i > 0) return Path.of(s.substring(0, i)) to s.substring(i + m.length)
                    }
                    val i = s.indexOf("/out/production/")
                    if (i > 0) return null to s.substring(i + "/out/production/".length).substringAfter('/', "").ifEmpty { null }
                    null to null
                }
                else -> null to null
            }
        }

        /** [file] without its trailing [entry] segments: the source root it sits in. */
        private fun root(file: Path, entry: Path): Path {
            val keep = file.nameCount - entry.nameCount
            return (file.root ?: Path.of("")).resolve(file.subpath(0, keep))
        }
    }
}
