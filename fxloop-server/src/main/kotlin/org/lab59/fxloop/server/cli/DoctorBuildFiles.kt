package org.lab59.fxloop.server.cli

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** A Maven dependency or plugin coordinate as written in a pom (versions may be unresolved placeholders). */
internal data class Coord(val groupId: String?, val artifactId: String, val version: String?)

/** A plugin with the raw configuration element, to read `mainClass` and friends. */
internal class PluginRef(val coord: Coord, val element: Element) {
    /** Text of the first descendant element named [name] (any depth: configuration, executions). */
    fun descendant(name: String): String? =
        element.getElementsByTagName(name).let { if (it.length > 0) it.item(0).textContent.trim().ifEmpty { null } else null }
}

/**
 * Static read of a plain `pom.xml` and its `relativePath` parent chain (no Maven run, no effective POM): properties
 * for `${…}` substitution, declared and managed dependencies, plugins. The merge swaps this for the effective POM.
 */
internal class PomModel private constructor(
    val file: Path,
    private val chain: List<Element>,
) {
    private val own: Element get() = chain.first()

    val properties: Map<String, String> = buildMap {
        for (root in chain.reversed()) {
            kids(root, "properties").firstOrNull()?.let { p -> elements(p).forEach { put(it.tagName, it.textContent.trim()) } }
        }
        val v = kids(own, "version").firstOrNull()?.textContent?.trim()
            ?: kids(own, "parent").firstOrNull()?.let { kids(it, "version").firstOrNull()?.textContent?.trim() }
        if (v != null) put("project.version", v)
    }

    val packaging: String? = kids(own, "packaging").firstOrNull()?.textContent?.trim()
    val hasModules: Boolean = kids(own, "modules").isNotEmpty()

    /** `${prop}` substitution; null when something stays unresolved. */
    fun resolve(s: String?): String? {
        if (s == null) return null
        var cur = s.trim()
        repeat(5) {
            if ("\${" !in cur) return cur.ifEmpty { null }
            cur = Regex("""\$\{([^}]+)}""").replace(cur) { properties[it.groupValues[1]] ?: it.value }
        }
        return if ("\${" in cur) null else cur.ifEmpty { null }
    }

    private fun coords(parentPath: List<String>, itemTag: String, includeChain: Boolean = true): List<Coord> =
        (if (includeChain) chain else listOf(own)).flatMap { root ->
            var nodes = listOf(root)
            for (t in parentPath) nodes = nodes.flatMap { kids(it, t) }
            nodes.flatMap { kids(it, itemTag) }.map { e ->
                Coord(kids(e, "groupId").firstOrNull()?.textContent?.trim(), kids(e, "artifactId").firstOrNull()?.textContent?.trim() ?: "",
                    resolve(kids(e, "version").firstOrNull()?.textContent))
            }
        }

    /** Dependencies declared in this pom, version filled from `dependencyManagement` in the chain. */
    val dependencies: List<Coord> by lazy {
        val managed = coords(listOf("dependencyManagement", "dependencies"), "dependency").associateBy { it.groupId to it.artifactId }
        coords(listOf("dependencies"), "dependency", includeChain = false).map { d ->
            if (d.version != null) d else d.copy(version = managed[d.groupId to d.artifactId]?.version)
        }
    }

    val plugins: List<PluginRef> by lazy {
        chain.flatMap { root ->
            val direct = kids(root, "build").flatMap { kids(it, "plugins") }.flatMap { kids(it, "plugin") }
            val managed = kids(root, "build").flatMap { kids(it, "pluginManagement") }.flatMap { kids(it, "plugins") }
                .flatMap { kids(it, "plugin") }
            (direct + managed).map { e ->
                PluginRef(Coord(kids(e, "groupId").firstOrNull()?.textContent?.trim(), kids(e, "artifactId").firstOrNull()?.textContent?.trim() ?: "",
                    resolve(kids(e, "version").firstOrNull()?.textContent)), e)
            }
        }
    }

    fun plugin(artifactId: String): PluginRef? = plugins.firstOrNull { it.coord.artifactId == artifactId }

    companion object {
        fun load(pom: Path): PomModel? {
            val chain = ArrayList<Element>()
            var cur: Path? = pom
            while (cur != null && chain.size < 6) {
                val root = parse(cur) ?: break
                chain += root
                val parent = kids(root, "parent").firstOrNull()
                val rel = parent?.let { kids(it, "relativePath").firstOrNull()?.textContent?.trim() ?: "../pom.xml" }
                cur = rel?.takeIf { it.isNotEmpty() }?.let { r ->
                    cur!!.parent.resolve(r).normalize().let { if (Files.isDirectory(it)) it.resolve("pom.xml") else it }
                }?.takeIf { Files.isRegularFile(it) }
            }
            return if (chain.isEmpty()) null else PomModel(pom, chain)
        }

        private fun parse(p: Path): Element? = runCatching {
            val f = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isNamespaceAware = false
            }
            Files.newInputStream(p).use { f.newDocumentBuilder().parse(it).documentElement }
        }.getOrNull()

        private fun elements(e: Element): List<Element> =
            (0 until e.childNodes.length).map { e.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }

        private fun kids(e: Element, name: String): List<Element> = elements(e).filter { it.tagName == name }
    }
}

/** Text-level facts from `build.gradle(.kts)` (Groovy and Kotlin DSL look alike enough for these patterns). */
internal class GradleFacts(val text: String) {
    val javafxArtifacts: List<Pair<String, String?>> =
        Regex("""org\.openjfx:(javafx-[\w-]+)(?::([\w.\-]+))?""").findAll(text).map { it.groupValues[1] to it.groupValues[2].ifEmpty { null } }.toList()
    val javafxPlugin: Boolean = Regex("""org\.openjfx\.javafxplugin""").containsMatchIn(text)
    val javafxPluginVersion: String? = Regex("""javafx\s*\{[^}]*?version\s*=?\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
        .find(text)?.groupValues?.get(1)
    val javafxPluginModules: List<String> = Regex("""javafx\s*\{[^}]*?modules\s*=?\s*(?:listOf|\[)?\(?([^\])]*)""", RegexOption.DOT_MATCHES_ALL)
        .find(text)?.groupValues?.get(1)?.let { Regex("""["'](javafx\.[\w.]+)["']""").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
    val mainClass: String? = Regex("""mainClass(?:Name)?\s*(?:=|\.set\(|\()\s*["']([\w.$/]+)["']""").find(text)?.groupValues?.get(1)
        ?: Regex("""["']Main-Class["']\s*(?:to|:|=)\s*["']([\w.$]+)["']""").find(text)?.groupValues?.get(1)
    /** A plugin application (`id("org.lab59.fxloop")`, `id 'org.lab59.fxloop'`, `apply plugin:`, `alias(...)`, the plugin on a classpath), not a group id or a package name. */
    val fxloopPlugin: Boolean = Regex("""\b(id|plugin|apply|alias)\b[^\n]*["']org\.lab59\.fxloop["']|fxloop-gradle-plugin|org\.lab59\.fxloop\.gradle\b""").containsMatchIn(text)
    val fxloopPluginVersion: String? = Regex("""["']org\.lab59\.fxloop["'](?:\)|\s)*\s*version\s*\(?\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
}
