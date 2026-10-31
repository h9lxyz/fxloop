package dev.fxloop.maven

import org.apache.maven.model.Plugin
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

/** Reads values out of other plugins' configuration (plugin level first, then each execution). */
internal object PluginConfigs {

    fun find(project: MavenProject, groupId: String, artifactId: String): Plugin? {
        val key = "$groupId:$artifactId"
        val build = project.build ?: return null
        return build.plugins.firstOrNull { it.key == key }
            ?: build.pluginManagement?.plugins?.firstOrNull { it.key == key }
    }

    fun configurations(plugin: Plugin?): List<Xpp3Dom> {
        if (plugin == null) return emptyList()
        return buildList {
            (plugin.configuration as? Xpp3Dom)?.let(::add)
            plugin.executions.forEach { e -> (e.configuration as? Xpp3Dom)?.let(::add) }
        }
    }

    private fun Xpp3Dom.descend(path: Array<out String>): Xpp3Dom? {
        var n: Xpp3Dom? = this
        for (p in path) n = n?.getChild(p)
        return n
    }

    /** First non-blank text at [path] (child element names) across plugin and execution configs. */
    fun text(plugin: Plugin?, vararg path: String): String? =
        configurations(plugin).firstNotNullOfOrNull { cfg ->
            cfg.descend(path)?.value?.takeIf { it.isNotBlank() }?.trim()
        }

    /** Text values of all children of the element at [path]. */
    fun childTexts(plugin: Plugin?, vararg path: String): List<String> {
        for (cfg in configurations(plugin)) {
            val n = cfg.descend(path) ?: continue
            val out = n.children.mapNotNull { c -> c.value?.takeIf { it.isNotBlank() }?.trim() }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    fun has(plugin: Plugin?, vararg path: String): Boolean =
        configurations(plugin).any { it.descend(path) != null }
}
