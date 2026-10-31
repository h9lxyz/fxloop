package dev.fxloop.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.fxloop.server.tools.Ctx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** PRD 06 §7: the Claude Code plugin stays consistent with the server (generated tool reference, skill format, links). */
class PluginLayoutTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isDirectory(it.resolve("fxloop-plugin")) }
    private val plugin = repo.resolve("fxloop-plugin")

    @Test
    fun `tool references are generated from the registry`(@TempDir dir: Path) {
        val ctx = Ctx(Config(projectDir = dir, webview = true))
        val expected = toolReference(registry(ctx))
        ctx.shutdown()
        assertThat(repo.resolve("docs/tools.md").readText()).describedAs("regenerate with --tool-reference").isEqualTo(expected)
        assertThat(plugin.resolve("references/tool-reference.md").readText()).describedAs("copy docs/tools.md").isEqualTo(expected)
    }

    @Test
    fun `skills have frontmatter, stay short and link to existing files`() {
        val skills = plugin.resolve("skills").listDirectoryEntries().filter { it.isDirectory() }
        assertThat(skills.map { it.name }).contains("connect", "ui-review", "fix-layout", "style", "accessibility", "responsive",
            "flow-polish", "forms-dialogs", "consistency", "lock-in")
        val json = ObjectMapper()
        for (s in skills) {
            val md = s.resolve("SKILL.md").readText()
            val front = Regex("(?s)^---\\n(.*?)\\n---\\n").find(md)?.groupValues?.get(1)
            assertThat(front).describedAs("frontmatter of %s", s.name).isNotNull()
            assertThat(front).contains("name: ${s.name}\n").contains("description: ")
            assertThat(md.lines().size).describedAs("%s/SKILL.md lines", s.name).isLessThanOrEqualTo(150)
            Regex("\\]\\(([^)#:]+\\.md)\\)").findAll(md).forEach {
                assertThat(s.resolve(it.groupValues[1]).normalize()).describedAs("link in %s", s.name).exists()
            }
            val evals = json.readTree(s.resolve("evals/evals.json").toFile())
            assertThat(evals["skill_name"].asText()).isEqualTo(s.name)
            assertThat(evals["evals"].size()).isBetween(3, 5)
        }
        val triggers = json.readTree(plugin.resolve("evals/triggers.json").toFile())
        assertThat(triggers.map { it["skill"].asText() }.toSet()).containsExactlyInAnyOrderElementsOf(skills.map { it.name })
        assertThat(json.readTree(plugin.resolve(".claude-plugin/plugin.json").toFile())["version"].asText())
            .isEqualTo(dev.fxloop.server.app.Versions.SERVER)
    }
}
