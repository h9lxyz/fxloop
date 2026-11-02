package dev.fxloop.server.app

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * The few facts the server-side Maven prepare reads out of `mvn help:effective-pom` output (PRD 11 4.2): build
 * directories, plugin configuration (plugin level first, then each execution, as `PrepareMojo` does), properties and
 * dependencies. Parsing is namespace-agnostic and also accepts a plain `pom.xml` (degraded mode when the help plugin
 * cannot run). An effective POM of a reactor is `<projects><project>...`; [parse] picks the project that looks like the app.
 */
class EffectivePom private constructor(private val project: Element) {

    /** One `<plugin>`: configuration lookups across its plugin-level and execution-level `<configuration>`. */
    class PluginNode internal constructor(private val configs: List<Element>) {
        fun text(vararg path: String): String? =
            configs.firstNotNullOfOrNull { c -> c.descend(path)?.textContent?.trim()?.takeIf { it.isNotEmpty() } }

        fun childTexts(vararg path: String): List<String> {
            for (c in configs) {
                val n = c.descend(path) ?: continue
                val out = n.children().map { it.textContent.trim() }.filter { it.isNotEmpty() }
                if (out.isNotEmpty()) return out
            }
            return emptyList()
        }

        fun has(vararg path: String): Boolean = configs.any { it.descend(path) != null }
    }

    data class Dependency(val groupId: String, val artifactId: String, val version: String?, val scope: String?, val classifier: String?)

    val outputDirectory: String? get() = project.descend(arrayOf("build", "outputDirectory"))?.textContent?.trim()?.ifEmpty { null }
    val directory: String? get() = project.descend(arrayOf("build", "directory"))?.textContent?.trim()?.ifEmpty { null }
    val sourceDirectory: String? get() = project.descend(arrayOf("build", "sourceDirectory"))?.textContent?.trim()?.ifEmpty { null }

    val resourceDirectories: List<String>
        get() = project.descend(arrayOf("build", "resources"))?.children("resource")
            ?.mapNotNull { it.child("directory")?.textContent?.trim()?.ifEmpty { null } }.orEmpty()

    fun property(name: String): String? = project.child("properties")?.child(name)?.textContent?.trim()

    /** Plugin from `<build><plugins>`, else `<pluginManagement>` (same lookup order as `PluginConfigs.find`). */
    fun plugin(groupId: String, artifactId: String): PluginNode? {
        val build = project.child("build") ?: return null
        val found = sequenceOf(build.child("plugins"), build.child("pluginManagement")?.child("plugins"))
            .filterNotNull().flatMap { it.children("plugin") }
            .firstOrNull { (it.child("groupId")?.textContent?.trim() ?: "org.apache.maven.plugins") == groupId && it.child("artifactId")?.textContent?.trim() == artifactId }
            ?: return null
        val configs = buildList {
            found.child("configuration")?.let(::add)
            found.child("executions")?.children("execution")?.forEach { e -> e.child("configuration")?.let(::add) }
        }
        return PluginNode(configs)
    }

    /** `<build><plugins>` execution config sources of build-helper `add-source`: extra source roots. */
    val extraSourceDirectories: List<String>
        get() = plugin("org.codehaus.mojo", "build-helper-maven-plugin")
            ?.let { p -> p.childTexts("sources") } .orEmpty()

    val dependencies: List<Dependency>
        get() = project.child("dependencies")?.children("dependency")?.map {
            Dependency(
                it.child("groupId")?.textContent?.trim().orEmpty(), it.child("artifactId")?.textContent?.trim().orEmpty(),
                it.child("version")?.textContent?.trim(), it.child("scope")?.textContent?.trim(),
                it.child("classifier")?.textContent?.trim(),
            )
        }.orEmpty()

    companion object {
        fun parse(file: Path): EffectivePom = parse(Files.readString(file))

        fun parse(xml: String): EffectivePom {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // untrusted-ish input: no DTDs/external entities
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isXIncludeAware = false
            }
            val root = f.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
            if (root.tagName != "projects") return EffectivePom(root)
            val projects = root.children("project")
            require(projects.isNotEmpty()) { "effective POM contains no <project>" }
            // reactor: the app is the project that uses JavaFX (plugin or dependency); else the last one built
            val app = projects.firstOrNull { p ->
                EffectivePom(p).let { e ->
                    e.plugin("org.openjfx", "javafx-maven-plugin") != null || e.dependencies.any { it.groupId == "org.openjfx" }
                }
            } ?: projects.last()
            return EffectivePom(app)
        }

        /** Newest mtime of [pom] and its parent POMs found through `<relativePath>` (default `../pom.xml`). */
        fun newestPomMtime(pom: Path): Long {
            var newest = 0L
            var cur: Path? = pom
            val seen = HashSet<Path>()
            while (cur != null && Files.isRegularFile(cur) && seen.add(cur.toAbsolutePath().normalize())) {
                newest = maxOf(newest, Files.getLastModifiedTime(cur).toMillis())
                cur = parentPom(cur)
            }
            return newest
        }

        private fun parentPom(pom: Path): Path? = try {
            val root = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(pom.toFile()).documentElement
            val parent = root.child("parent")
            if (parent == null) null
            else {
                val rel = parent.child("relativePath")
                val text = rel?.textContent?.trim()
                if (rel != null && text.isNullOrEmpty()) null // `<relativePath/>`: look it up in the repository, not on disk
                else (pom.toAbsolutePath().parent.resolve(text ?: "../pom.xml")).normalize().let { if (Files.isDirectory(it)) it.resolve("pom.xml") else it }
            }
        } catch (e: Exception) {
            null
        }

        private fun Element.children(): List<Element> {
            val out = ArrayList<Element>()
            var n: Node? = firstChild
            while (n != null) { if (n is Element) out += n; n = n.nextSibling }
            return out
        }

        private fun Element.children(name: String): List<Element> = children().filter { it.tagName == name }
        private fun Element.child(name: String): Element? = children().firstOrNull { it.tagName == name }
        private fun Element.descend(path: Array<out String>): Element? {
            var n: Element? = this
            for (p in path) n = n?.child(p)
            return n
        }
    }
}
