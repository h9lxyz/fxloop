package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.agent.AgentRuntime
import dev.fxloop.agent.Fx
import dev.fxloop.agent.Overlays
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.FcpException
import dev.fxloop.protocol.Json
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javafx.collections.ListChangeListener
import javafx.css.CssParser
import javafx.stage.Window

/**
 * The inspector's scratch stylesheet (PRD 08 §5, "Try it live"): one sheet holding the developer's experiments,
 * installed as the last stylesheet of every app scene. Nothing is written to source; it survives CSS reloads and is
 * gone after a restart. Installed through a `data:` URL (JavaFX 17+), or a temp file on older runtimes.
 */
internal object Scratch {
    const val MAX_CHARS = 64 * 1024

    @Volatile private var css = ""

    /** URL of the installed sheet, or null when no scratch CSS is active. */
    @Volatile private var url: String? = null
    private var tempFile: Path? = null
    private var generation = 0L
    private var listening = false

    val active: Boolean get() = url != null

    /** True for the currently installed scratch sheet (as it appears in `Scene.getStylesheets()`). */
    fun isScratch(sheetUrl: String?): Boolean = sheetUrl != null && sheetUrl == url

    /** FCP `cssScratchSet {css}`: validates, then replaces the scratch sheet; blank text clears it. */
    fun set(p: ObjectNode): ObjectNode {
        val text = p.path("css").asText("")
        if (text.isBlank()) return clear(p)
        validate(text)
        val r = Fx.call {
            install(text)
            describe()
        }
        changed()
        return r
    }

    /** FCP `cssScratchClear`: removes the scratch sheet from every scene. */
    @Suppress("UNUSED_PARAMETER")
    fun clear(p: ObjectNode = Json.obj()): ObjectNode {
        val r = Fx.call {
            val old = url
            if (old != null) for (w in appWindows()) w.scene.stylesheets.remove(old)
            url = null
            css = ""
            deleteTemp()
            describe()
        }
        changed()
        return r
    }

    /** `{active, rules, css, items:[{n, selector, declarations:[{property, value, important}]}]}`. */
    fun describe(): ObjectNode = Json.obj {
        put("active", active)
        val scanned = CssSource.scan(css)
        put("rules", if (active) scanned.rules.size else 0)
        if (!active) return@obj
        put("css", css)
        val items = putArray("items")
        scanned.rules.forEachIndexed { i, r ->
            val o = items.addObject().put("n", i + 1).put("selector", r.selectorText.trim())
            val decls = o.putArray("declarations")
            r.declarations.forEach { d ->
                decls.addObject().put("property", d.property).put("value", valueOf(d.text)).put("important", d.important)
            }
        }
    }

    /** Number of scratch rules, 0 when inactive. */
    fun ruleCount(): Int = if (active) CssSource.scan(css).rules.size else 0

    /** Moves the scratch sheet back to the end of each scene's list (apps may add sheets after it). FX thread. */
    fun reassert() {
        val u = url ?: return
        for (w in appWindows()) {
            val ss = w.scene.stylesheets
            if (ss.lastOrNull() != u) {
                ss.remove(u)
                ss.add(u)
            }
        }
    }

    /** Text of a `data:` stylesheet URL (base64 or percent-encoded), or null for other URLs. */
    fun dataUrlText(sheetUrl: String): String? {
        if (!sheetUrl.startsWith("data:", ignoreCase = true)) return null
        val comma = sheetUrl.indexOf(',')
        if (comma < 0) return null
        val header = sheetUrl.substring(5, comma)
        val body = sheetUrl.substring(comma + 1)
        return if (header.contains(";base64", ignoreCase = true)) {
            String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8)
        } else {
            URLDecoder.decode(body.replace("+", "%2B"), StandardCharsets.UTF_8)
        }
    }

    /** Throws BAD_REQUEST unless [text] is plain style rules that `CssParser` accepts without errors. */
    internal fun validate(text: String) {
        if (text.length > MAX_CHARS) bad("scratch CSS is larger than ${MAX_CHARS / 1024} KB")
        val scanned = CssSource.scan(text)
        if (scanned.imports.isNotEmpty() || scanned.fontFaceCount > 0 || AT_RULE.containsMatchIn(stripComments(text))) {
            bad("scratch CSS accepts style rules only (no @import, @font-face or other at-rules)")
        }
        if (scanned.rules.isEmpty()) bad("scratch CSS has no style rule (selector { property: value; })")
        val errors = CssParser.errorsProperty()
        val before = errors.size
        val sheet = try {
            CssParser().parse(text)
        } catch (e: RuntimeException) {
            bad("scratch CSS does not parse: ${e.message}")
        }
        val ours = if (errors.size > before) errors.subList(before, errors.size).map { it.message ?: it.toString() } else emptyList()
        if (errors.size > before) errors.remove(before, errors.size)
        if (ours.isNotEmpty()) bad("scratch CSS has parse errors: ${ours.joinToString("; ")}")
        if (sheet == null || sheet.rules.size != scanned.rules.size) {
            bad("scratch CSS has a rule the parser dropped; check selectors and braces")
        }
    }

    private fun install(text: String) {
        ensureListening()
        val old = url
        val fresh = toUrl(text)
        for (w in appWindows()) {
            val ss = w.scene.stylesheets
            if (old != null) ss.remove(old)
            ss.add(fresh)
        }
        val oldTemp = tempFile.takeIf { old != null && !fresh.startsWith("data:") }
        url = fresh
        css = text
        if (oldTemp != null && oldTemp.toUri().toString() != fresh) runCatching { Files.deleteIfExists(oldTemp) }
    }

    private fun toUrl(text: String): String {
        if (fxMajor() >= 17) return "data:text/css;base64," + Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))
        val dir = AgentRuntime.get()?.options?.stateFile?.toAbsolutePath()?.parent ?: Path.of(System.getProperty("java.io.tmpdir"))
        val f = dir.resolve("fxloop-scratch-${++generation}.css")
        Files.writeString(f, text)
        tempFile = f
        return f.toUri().toString()
    }

    private fun deleteTemp() {
        tempFile?.let { runCatching { Files.deleteIfExists(it) } }
        tempFile = null
    }

    /** Windows shown later (dialogs, new stages) get the scratch sheet too. FX thread. */
    private fun ensureListening() {
        if (listening) return
        listening = true
        Window.getWindows().addListener(ListChangeListener { c ->
            val u = url ?: return@ListChangeListener
            while (c.next()) {
                for (w in c.addedSubList) {
                    val scene = w.scene ?: continue
                    if (!Overlays.isOverlay(w) && u !in scene.stylesheets) scene.stylesheets.add(u)
                }
            }
        })
    }

    private fun appWindows(): List<Window> = Window.getWindows().filter { it.scene != null && !Overlays.isOverlay(it) }

    private fun changed() {
        AgentRuntime.get()?.bar?.scratchChanged()
    }

    private fun fxMajor(): Int =
        System.getProperty("javafx.runtime.version")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 17

    private fun valueOf(declText: String): String =
        declText.substringAfter(':').trim().removeSuffix(";").trim().replace(IMPORTANT, "").trim()

    private fun stripComments(text: String): String = text.replace(COMMENT, " ")

    private fun bad(message: String): Nothing = throw FcpException(Fcp.Error.BAD_REQUEST, message)

    private val AT_RULE = Regex("(^|[;{}\\s])@[a-zA-Z-]")
    private val COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
    private val IMPORTANT = Regex("\\s*!\\s*important\\s*$", RegexOption.IGNORE_CASE)
}
