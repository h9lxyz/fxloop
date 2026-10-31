package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.NodePath
import dev.fxloop.protocol.Json
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javafx.application.Application
import javafx.css.CssParser
import javafx.css.Rule
import javafx.css.StyleOrigin
import javafx.css.Stylesheet
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Control
import javafx.scene.layout.Region

/** Where a rule came from in the source text (PRD 08 §3.2). */
internal class RuleSource(val line: Int, val declarations: List<ScannedDecl>)

/** A parsed stylesheet plus its source map. Immutable once built; cached by URL. */
internal class ParsedSheet(
    val url: String,
    val stylesheet: Stylesheet?,
    val sources: Map<Rule, RuleSource>,
    val sourceMap: String,
    val reason: String?,
    val parseErrors: List<String>,
    val absFile: Path?,
    val stamp: Long,
)

/**
 * One stylesheet as it applies to a node: the sheet, its origin, where it is installed, and its precedence slot.
 * [scratch] marks the inspector's scratch stylesheet ([Scratch]).
 */
internal class SheetRef(
    val id: String,
    val parsed: ParsedSheet,
    val origin: StyleOrigin,
    val on: String,
    val order: Int,
    val scratch: Boolean = false,
) {
    val url: String get() = parsed.url
}

/** Project layout hints the server passes along so `file` points at the source, not the build copy. */
internal class SourceRoots(val projectDir: Path?, val outputDirs: List<Path>, val resourceRoots: List<Path>) {
    /** The source file behind a build copy in an output dir (reverse of `Reloader.copyResources`), else [abs] itself. */
    fun sourceFile(abs: Path?): Path? {
        if (abs == null) return null
        val p = abs.toAbsolutePath().normalize()
        for (out in outputDirs) {
            if (p.startsWith(out)) {
                val rel = out.relativize(p)
                return resourceRoots.map { it.resolve(rel) }.firstOrNull { Files.isRegularFile(it) } ?: p
            }
        }
        return p
    }

    /** Project-relative path of the file to edit, or `null` when the sheet lives outside the project. */
    fun projectFile(abs: Path?): String? {
        val p = sourceFile(abs) ?: return null
        val project = projectDir ?: return p.toString()
        return if (p.startsWith(project)) project.relativize(p).toString().replace('\\', '/') else p.toString()
    }

    /** True for sheets the project owns (inside a resource root or the project, outside build output). */
    fun owns(abs: Path?): Boolean {
        val f = projectFile(abs) ?: return false
        val p = Path.of(f)
        if (p.isAbsolute) return resourceRoots.any { p.startsWith(it) }
        return outputDirs.none { projectDir?.resolve(p)?.normalize()?.startsWith(it) == true }
    }

    companion object {
        val NONE = SourceRoots(null, emptyList(), emptyList())

        fun from(p: ObjectNode): SourceRoots {
            fun paths(k: String) = p.path(k).map { Path.of(it.asText()).toAbsolutePath().normalize() }
            val project = p.path("projectDir").asText(null)?.let { Path.of(it).toAbsolutePath().normalize() }
            return SourceRoots(project, paths("outputDirs"), paths("resourceRoots"))
        }
    }
}

/** Collects the stylesheets that apply to a node in effective order and parses them (cached). FX thread. */
internal object Sheets {
    private val cache = ConcurrentHashMap<String, ParsedSheet>()

    const val MODENA = "/com/sun/javafx/scene/control/skin/modena/modena.css"
    const val CASPIAN = "/com/sun/javafx/scene/control/skin/caspian/caspian.css"

    /** User agent → author (scene, then Parent sheets outermost-first). Inline styles are handled by the cascade. */
    fun forNode(node: Node): List<SheetRef> {
        val out = ArrayList<SheetRef>()
        fun add(url: String?, origin: StyleOrigin, on: String) {
            if (url.isNullOrBlank()) return
            val resolved = resolveUrl(url) ?: return
            val scratch = Scratch.isScratch(url)
            out += SheetRef("s${out.size}", load(resolved), origin, if (scratch) "Scratch CSS" else on, out.size, scratch)
        }
        val scene = node.scene
        val ua = scene?.userAgentStylesheet ?: Application.getUserAgentStylesheet()
        add(platformSheet(ua), StyleOrigin.USER_AGENT, if (scene?.userAgentStylesheet != null) "Scene (user agent)" else "Application")
        val chain = ancestry(node)
        for (n in chain) {
            val rua = (n as? Region)?.userAgentStylesheet
            if (rua != null) add(rua, StyleOrigin.USER_AGENT, NodePath.of(n) ?: n.javaClass.simpleName)
        }
        scene?.stylesheets?.forEach { add(it, StyleOrigin.AUTHOR, "Scene") }
        for (n in chain) {
            if (n is Parent) n.stylesheets.forEach { add(it, StyleOrigin.AUTHOR, NodePath.of(n) ?: n.javaClass.simpleName) }
        }
        return out
    }

    /** Outermost ancestor first, the node itself last. */
    fun ancestry(node: Node): List<Node> {
        val list = ArrayList<Node>()
        var cur: Node? = node
        while (cur != null) {
            list += cur
            cur = cur.parent
        }
        list.reverse()
        return list
    }

    private fun platformSheet(ua: String?): String = when {
        ua == null || ua.equals(Application.STYLESHEET_MODENA, ignoreCase = true) -> "fxloop-builtin:$MODENA"
        ua.equals(Application.STYLESHEET_CASPIAN, ignoreCase = true) -> "fxloop-builtin:$CASPIAN"
        else -> ua
    }

    /** Stylesheet strings are URLs or classpath-relative paths (as JavaFX accepts them). */
    private fun resolveUrl(raw: String): String? {
        if (raw.startsWith("fxloop-builtin:")) return builtin(raw.removePrefix("fxloop-builtin:"))
        if (raw.contains(":") && !raw.startsWith("/")) return raw
        val cl = Thread.currentThread().contextClassLoader ?: Sheets::class.java.classLoader
        return (cl.getResource(raw.removePrefix("/")) ?: Sheets::class.java.getResource(raw))?.toExternalForm() ?: raw
    }

    /** Modena/Caspian inside javafx.controls: a plain resource read, falling back to the jar when the package is encapsulated. */
    private fun builtin(name: String): String? {
        Control::class.java.getResource(name)?.let { return it.toExternalForm() }
        val loc = Control::class.java.protectionDomain?.codeSource?.location ?: return null
        val s = loc.toExternalForm()
        return when {
            s.startsWith("jrt:") -> "$s$name"
            s.endsWith(".jar") -> "jar:$s!$name"
            else -> null
        }
    }

    fun load(url: String): ParsedSheet {
        if (url.startsWith("data:", ignoreCase = true)) return parse(url, null, 0L) // content is in the URL: never stale, not cached
        val abs = fileOf(url)
        val stamp = abs?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() } ?: 0L
        cache[url]?.let { if (it.stamp == stamp) return it }
        val parsed = parse(url, abs, stamp)
        cache[url] = parsed
        return parsed
    }

    /** Drop everything; called on CSS and FXML reloads. */
    fun invalidate() = cache.clear()

    private fun parse(url: String, abs: Path?, stamp: Long): ParsedSheet {
        val bare = url.substringBefore('?')
        val errors = CssParser.errorsProperty()
        val before = errors.size
        val inline = runCatching { Scratch.dataUrlText(url) }.getOrNull()
        val sheet = try {
            when {
                inline != null -> CssParser().parse(inline)
                bare.endsWith(".bss") -> Stylesheet.loadBinary(URL(url))
                else -> CssParser().parse(URL(url))
            }
        } catch (e: Exception) {
            return ParsedSheet(url, null, emptyMap(), "unavailable", "cannot load: ${e.message}", emptyList(), abs, stamp)
        }
        val ours = if (errors.size > before) errors.subList(before, errors.size).map { it.toString() } else emptyList()
        if (errors.size > before) errors.remove(before, errors.size) // ours, not the app's
        if (sheet == null) return ParsedSheet(url, null, emptyMap(), "unavailable", "stylesheet not found", ours, abs, stamp)
        if (bare.endsWith(".bss")) return ParsedSheet(url, sheet, emptyMap(), "binary", "binary .bss stylesheet has no source", ours, abs, stamp)
        val text = inline ?: try {
            URL(url).openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        } catch (e: Exception) {
            return ParsedSheet(url, sheet, emptyMap(), "unavailable", "cannot read source: ${e.message}", ours, abs, stamp)
        }
        val (map, status, reason) = align(sheet.rules, CssSource.scan(text))
        return ParsedSheet(url, sheet, map, status, reason, ours, abs, stamp)
    }

    /**
     * Pairs parsed rules with scanned rules in source order, cross-checking the first selector. Rules the parser
     * dropped (syntax errors) are skipped on the scanned side; imported rules come first on the parsed side.
     */
    private fun align(rules: List<Rule>, scanned: ScannedSheet): Triple<Map<Rule, RuleSource>, String, String?> {
        val map = HashMap<Rule, RuleSource>()
        val src = scanned.rules
        var start = if (scanned.imports.isNotEmpty()) maxOf(0, rules.size - src.size) else 0
        var j = 0
        var skipped = 0
        for (i in start until rules.size) {
            val want = rules[i].selectors.firstOrNull()?.let { key(it.toString()) } ?: continue
            var k = j
            while (k < src.size && src[k].selectors.firstOrNull()?.let(::key) != want) k++
            if (k >= src.size) {
                skipped++
                continue
            }
            skipped += k - j
            map[rules[i]] = RuleSource(src[k].line, src[k].declarations)
            j = k + 1
        }
        start = map.size
        return when {
            start == 0 && rules.isNotEmpty() -> Triple(map, "unavailable", "rule selectors could not be paired with the source text")
            skipped > 0 || start < rules.size -> Triple(map, "partial", "${rules.size - start} rule(s) without a source line")
            else -> Triple(map, "ok", null)
        }
    }

    /** Comparable form of a selector: JavaFX prints `*.a:hover`, sources say `.a:hover`. */
    fun key(selector: String): String =
        CssSource.normalizeSelector(selector).replace(Regex("\\*(?=[.#:\\[])"), "").replace(" > ", ">")

    fun fileOf(url: String): Path? = try {
        val u = URI(url.substringBefore('?'))
        if (u.scheme == "file") Path.of(u) else null
    } catch (e: Exception) {
        null
    }

    /** Short display name for sheets outside the project (`modena.css`). */
    fun shortName(url: String): String =
        if (url.startsWith("data:", ignoreCase = true)) "data: stylesheet"
        else url.substringBefore('?').substringAfterLast('/').substringAfterLast('!')

    /** `scratch:<n>` for a rule of the scratch sheet (1-based, in the order the developer wrote them). */
    fun scratchLabel(ref: SheetRef, rule: Rule): String =
        "scratch:" + ((ref.parsed.stylesheet?.rules?.indexOf(rule) ?: -1) + 1)

    fun describe(ref: SheetRef, roots: SourceRoots): ObjectNode = Json.obj {
        put("id", ref.id)
        put("url", ref.url)
        put("origin", ref.origin.name)
        put("on", ref.on)
        put("file", if (ref.scratch) "scratch CSS" else roots.projectFile(ref.parsed.absFile) ?: shortName(ref.url))
        if (ref.scratch) put("scratch", true)
        put("sourceMap", ref.parsed.sourceMap)
        ref.parsed.reason?.let { put("reason", it) }
        set<com.fasterxml.jackson.databind.JsonNode>("parseErrors", Json.strings(ref.parsed.parseErrors))
    }
}
