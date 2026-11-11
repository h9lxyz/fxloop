package org.lab59.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.agent.NodePath
import org.lab59.fxloop.protocol.Json
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

/**
 * A parsed stylesheet plus its source map. Immutable once built; cached by URL. [digest] and [ruleKeys] describe the
 * loaded source text (null / empty without one), to compare it with a mapped source file ([Sheets.mappingMatch]).
 */
internal class ParsedSheet(
    val url: String,
    val stylesheet: Stylesheet?,
    val sources: Map<Rule, RuleSource>,
    val sourceMap: String,
    val reason: String?,
    val parseErrors: List<String>,
    val absFile: Path?,
    val stamp: Long,
    val digest: String? = null,
    val ruleKeys: List<String> = emptyList(),
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

/**
 * Project layout hints the server passes along so `file` points at the source, not the build copy. [mappings] are the
 * developer's stylesheet mappings (sheet URL → local source file) for sheets outside the project, e.g. a library's.
 */
internal class SourceRoots(
    val projectDir: Path?,
    val outputDirs: List<Path>,
    val resourceRoots: List<Path>,
    val mappings: Map<String, Path> = emptyMap(),
) {
    /**
     * The source file the developer mapped [url] to: an exact URL match, else (jar/jrt sheets) one with the same entry
     * path, so a library version bump keeps its mapping.
     */
    fun mapped(url: String): Path? {
        val bare = url.substringBefore('?')
        mappings[bare]?.let { return it }
        val entry = Sheets.entryOf(bare) ?: return null
        return mappings.entries.firstOrNull { Sheets.entryOf(it.key) == entry }?.value
    }

    /** The file to edit for a sheet: its project source, else its mapping, else the sheet's own file (null for jars). */
    fun editFile(url: String, abs: Path?): Path? = if (owns(abs)) sourceFile(abs) else mapped(url) ?: sourceFile(abs)

    /** `file` of a sheet as reported to tools and the inspector: project-relative, absolute, or the bare sheet name. */
    fun displayFile(url: String, abs: Path?): String = projectFile(editFile(url, abs)) ?: Sheets.shortName(url)

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
            val mappings = p.path("cssMappings").mapNotNull { m ->
                val url = m.path("url").asText("")
                val file = m.path("file").asText("")
                if (url.isEmpty() || file.isEmpty()) null else url.substringBefore('?') to Path.of(file).toAbsolutePath().normalize()
            }.toMap()
            return SourceRoots(project, paths("outputDirs"), paths("resourceRoots"), mappings)
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

    internal fun platformSheet(ua: String?): String = when {
        ua == null || ua.equals(Application.STYLESHEET_MODENA, ignoreCase = true) -> "fxloop-builtin:$MODENA"
        ua.equals(Application.STYLESHEET_CASPIAN, ignoreCase = true) -> "fxloop-builtin:$CASPIAN"
        else -> ua
    }

    /** Stylesheet strings are URLs or classpath-relative paths (as JavaFX accepts them). */
    internal fun resolveUrl(raw: String): String? {
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
        val scanned = CssSource.scan(text)
        val (map, status, reason) = align(sheet.rules, scanned)
        return ParsedSheet(url, sheet, map, status, reason, ours, abs, stamp, digest(text), ruleKeys(scanned))
    }

    /** One comparable key per rule, in source order: its selectors through [key], comma-joined. */
    fun ruleKeys(scanned: ScannedSheet): List<String> = scanned.rules.map { ruleKey(it) }

    fun ruleKey(rule: ScannedRule): String = rule.selectors.joinToString(",", transform = ::key)

    /** Content hash that ignores line-ending style (a Windows checkout of the same file is identical). */
    fun digest(text: String): String = ScratchApply.sha256(text.replace("\r\n", "\n"))

    private class SourceCheck(val stamp: Long, val size: Long, val digest: String, val ruleKeys: List<String>)

    private val sourceChecks = ConcurrentHashMap<Path, SourceCheck>()

    /**
     * How a mapped source file compares with the stylesheet the app loaded: `identical`, `differs` (with the reason),
     * `missing`, or `unknown` (no loaded text to compare, e.g. a binary sheet).
     */
    fun mappingMatch(parsed: ParsedSheet, file: Path): Pair<String, String?> {
        val attrs = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull()
        if (attrs == null || !attrs.isRegularFile) return "missing" to "the mapped file does not exist"
        val loaded = parsed.digest ?: return "unknown" to "the loaded stylesheet has no source text to compare with"
        val stamp = attrs.lastModifiedTime().toMillis()
        val check = sourceChecks[file]?.takeIf { it.stamp == stamp && it.size == attrs.size() } ?: run {
            val text = try {
                Files.readString(file)
            } catch (e: Exception) {
                return "missing" to "cannot read the mapped file: ${e.message}"
            }
            SourceCheck(stamp, attrs.size(), digest(text), ruleKeys(CssSource.scan(text))).also { sourceChecks[file] = it }
        }
        if (check.digest == loaded) return "identical" to null
        val have = check.ruleKeys.toHashSet()
        val missing = parsed.ruleKeys.count { it !in have }
        return "differs" to if (missing == 0) "the source differs from the copy the app loaded (edited since the library was built?): lines may be off"
        else "$missing of ${parsed.ruleKeys.size} loaded rules are not in this file: is it the right one?"
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

    /** Path of a sheet inside its jar or runtime module (`com/acme/theme.css`), null for other URLs. */
    fun entryOf(url: String): String? {
        val bare = url.substringBefore('?')
        return when {
            bare.startsWith("jar:", ignoreCase = true) -> bare.substringAfter("!/", "").ifEmpty { null }
            bare.startsWith("jrt:/", ignoreCase = true) -> bare.removePrefix("jrt:/").substringAfter('/', "").ifEmpty { null }
            else -> null
        }
    }

    /** Where a sheet outside the project was loaded from: the jar's file name, the runtime module, or the directory. */
    fun containerOf(url: String): String? {
        val bare = url.substringBefore('?')
        return when {
            bare.startsWith("jar:", ignoreCase = true) -> bare.substringBefore("!/").substringAfterLast('/')
            bare.startsWith("jrt:/", ignoreCase = true) -> bare.removePrefix("jrt:/").substringBefore('/')
            else -> fileOf(bare)?.parent?.toString()
        }
    }

    /** JavaFX's own stylesheets: Modena, Caspian and their platform additions. */
    fun isBuiltin(url: String): Boolean = url.contains("/com/sun/javafx/scene/control/skin/")

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
        put("file", if (ref.scratch) "scratch CSS" else roots.displayFile(ref.url, ref.parsed.absFile))
        if (ref.scratch) put("scratch", true) else if (!roots.owns(ref.parsed.absFile)) external(this, ref, roots)
        put("sourceMap", ref.parsed.sourceMap)
        ref.parsed.reason?.let { put("reason", it) }
        set<com.fasterxml.jackson.databind.JsonNode>("parseErrors", Json.strings(ref.parsed.parseErrors))
    }

    /**
     * A sheet outside the project (a library's, JavaFX's own, one set from code): where it was loaded from and, once
     * the developer mapped it to its source, that file and how well it matches what the app loaded.
     */
    private fun external(o: ObjectNode, ref: SheetRef, roots: SourceRoots) {
        o.put("external", true)
        if (isBuiltin(ref.url)) o.put("builtin", true)
        containerOf(ref.url)?.let { o.put("from", it) }
        entryOf(ref.url)?.let { o.put("resource", it) }
        val mapped = roots.mapped(ref.url)
        if (mapped == null) {
            o.put("mappable", !ref.url.startsWith("data:", ignoreCase = true))
            return
        }
        val (match, why) = mappingMatch(ref.parsed, mapped)
        val m = o.putObject("mapped").put("file", roots.projectFile(mapped)).put("absFile", mapped.toString()).put("match", match)
        why?.let { m.put("reason", it) }
    }
}
