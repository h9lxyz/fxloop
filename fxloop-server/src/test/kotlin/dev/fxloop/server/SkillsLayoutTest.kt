package dev.fxloop.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.fxloop.server.tools.Ctx
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * PRD 06 §7: the agent skills stay consistent with the server (generated tool reference, versions) and each
 * `skills/fxloop-*` folder is self-contained, so it can be copied into any agent's skills directory on its own.
 */
class SkillsLayoutTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isDirectory(it.resolve("fxloop-skills")) }
    private val root = repo.resolve("fxloop-skills")
    private val skills = root.resolve("skills").listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
    private val shared = root.resolve("references").listDirectoryEntries("*.md")

    @Test
    fun `tool reference is generated from the registry`(@TempDir dir: Path) {
        val ctx = Ctx(Config(projectDir = dir, webview = true))
        val expected = toolReference(registry(ctx))
        ctx.shutdown()
        assertThat(repo.resolve("docs/tools.md").readText()).describedAs("regenerate with --tool-reference").isEqualTo(expected)
    }

    @Test
    fun `skills have frontmatter, stay short and only link inside their own folder`() {
        assertThat(skills.map { it.name }).containsExactlyInAnyOrder("fxloop-connect", "fxloop-ui-review", "fxloop-fix-layout",
            "fxloop-style", "fxloop-accessibility", "fxloop-responsive", "fxloop-flow-polish", "fxloop-forms-dialogs",
            "fxloop-consistency", "fxloop-lock-in")
        for (s in skills) {
            val md = s.resolve("SKILL.md").readText()
            val front = Regex("(?s)^---\\n(.*?)\\n---\\n").find(md)?.groupValues?.get(1)
            assertThat(front).describedAs("frontmatter of %s", s.name).isNotNull()
            assertThat(front).contains("name: ${s.name}\n").contains("description: ")
            assertThat(md.lines().size).describedAs("%s/SKILL.md lines", s.name).isLessThanOrEqualTo(150)
            Regex("\\]\\(([^)#:]+\\.md)\\)").findAll(md).forEach {
                val target = s.resolve(it.groupValues[1]).normalize()
                assertThat(target).describedAs("link in %s", s.name).exists()
                assertThat(target.startsWith(s)).describedAs("%s links outside its folder: %s", s.name, it.groupValues[1]).isTrue()
            }
        }
    }

    @Test
    fun `bundled reference copies match the shared source`() {
        for (s in skills) {
            val md = s.resolve("SKILL.md").readText()
            for (src in shared) {
                val copy = s.resolve("references").resolve(src.name)
                if (md.contains("](references/${src.name})")) {
                    assertThat(copy).describedAs("%s needs %s (run fxloop-skills/sync-references.sh)", s.name, src.name).exists()
                    assertThat(copy.readText()).describedAs("%s/%s is stale (run sync-references.sh)", s.name, src.name)
                        .isEqualTo(src.readText())
                } else {
                    assertThat(copy.exists()).describedAs("%s bundles unused %s (run sync-references.sh)", s.name, src.name).isFalse()
                }
            }
        }
    }

    @Test
    fun `evals and triggers cover every skill`() {
        val json = ObjectMapper()
        for (s in skills) {
            val evals = json.readTree(root.resolve("evals/${s.name}.json").toFile())
            assertThat(evals["skill_name"].asText()).isEqualTo(s.name)
            assertThat(evals["evals"].size()).isBetween(3, 5)
        }
        val triggers = json.readTree(root.resolve("evals/triggers.json").toFile())
        assertThat(triggers.map { it["skill"].asText() }.toSet()).containsExactlyInAnyOrderElementsOf(skills.map { it.name })
    }

    @Test
    fun `connect skill and bootstrap carry the server version`() {
        val connect = root.resolve("skills/fxloop-connect")
        val version = dev.fxloop.server.app.Versions.SERVER
        assertThat(connect.resolve("SKILL.md").readText()).contains("  fxloop-version: $version\n")
        assertThat(connect.resolve("scripts/FxloopBootstrap.java").readText())
            .contains("env(\"FXLOOP_SERVER_VERSION\", \"$version\")")
    }
}
