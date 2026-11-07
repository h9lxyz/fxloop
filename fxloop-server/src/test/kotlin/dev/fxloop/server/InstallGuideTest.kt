package dev.fxloop.server

import dev.fxloop.server.app.Versions
import dev.fxloop.server.cli.McpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * PRD 11 §7: INSTALL.md is the one page an agent (or a developer) follows from an empty machine to a first screenshot,
 * so it must stay in step with the server version, the `fxloop-connect` skill's snippets and client configs, the
 * README's one-liner, and the assets the release workflow publishes.
 */
class InstallGuideTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isDirectory(it.resolve("fxloop-skills")) }
    private val guide = repo.resolve("INSTALL.md").readText()
    private val readme = repo.resolve("README.md").readText()
    private val connect = repo.resolve("fxloop-skills/skills/fxloop-connect/references")
    private val skillsOneLiner = "npx skills add h9lxyz/fxloop -g -y"

    /** Fenced code blocks of [md] whose language is in [langs], as their verbatim bodies. */
    private fun blocks(md: String, vararg langs: String): List<String> =
        Regex("```(\\w+)\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(md)
            .filter { it.groupValues[1] in langs }.map { it.groupValues[2] }.toList()

    @Test
    fun `install guide pins the server version in every build snippet`() {
        val v = Versions.SERVER
        assertThat(guide).contains("<version>$v</version>")
            .contains("id(\"dev.fxloop\") version \"$v\"")
            .contains("id 'dev.fxloop' version '$v'")
        assertThat(Regex("dev\\.fxloop[^\\n]*version[^\\n]*\\d+\\.\\d+\\.\\d+[^\\n]*").findAll(guide).map { it.value }.toList())
            .describedAs("every dev.fxloop version in INSTALL.md is the server version").allMatch { v in it }
    }

    @Test
    fun `readme starts from the skills one-liner and leaves the downloads to the install guide`() {
        assertThat(readme).contains(skillsOneLiner)
            .contains("https://raw.githubusercontent.com/h9lxyz/fxloop/main/INSTALL.md").contains("](INSTALL.md")
        assertThat(guide).contains(skillsOneLiner)
        for (asset in listOf("releases/latest/download/fxloop-server-all.jar", "releases/latest/download/fxloop-skills.zip")) {
            assertThat(guide).describedAs(asset).contains(asset)
        }
    }

    @Test
    fun `getting started docs give one command per step, not one per shell`() {
        val docs = listOf("README.md", "INSTALL.md", "CONTRIBUTING.md", "docs/mcp.md", "docs/skills.md", "docs/troubleshooting.md",
            "fxloop-skills/README.md", "fxloop-skills/skills/fxloop-connect/SKILL.md", "fxloop-skills/skills/fxloop-connect/references/snippets.md")
        for (doc in docs) {
            assertThat(repo.resolve(doc).readText()).describedAs(doc).doesNotContain("```powershell").doesNotContain("Invoke-WebRequest")
        }
    }

    @Test
    fun `install guide carries the connect skill's build snippets verbatim`() {
        val v = Versions.SERVER
        val snippets = blocks(connect.resolve("snippets.md").readText(), "xml", "kotlin", "groovy")
        assertThat(snippets).isNotEmpty()
        for (block in snippets) {
            val expected = block.replace("<version><version></version>", "<version>$v</version>")
                .replace("\"<version>\"", "\"$v\"").replace("'<version>'", "'$v'")
            assertThat(guide).describedAs("INSTALL.md lacks this snippets.md block (keep them identical):\n%s", expected).contains(expected)
        }
    }

    @Test
    fun `install guide carries every MCP client config verbatim`() {
        val configs = blocks(connect.resolve("mcp-clients.md").readText(), "json", "toml")
        assertThat(configs).hasSizeGreaterThanOrEqualTo(5)
        for (block in configs) {
            assertThat(guide).describedAs("INSTALL.md lacks this mcp-clients.md block (keep them identical):\n%s", block).contains(block)
        }
        for (client in McpClient.entries) {
            assertThat(guide).describedAs("INSTALL.md names the %s config file", client.label).contains("`${client.file}`")
        }
    }

    @Test
    fun `release workflow publishes the asset names the docs download`() {
        val workflow = repo.resolve(".github/workflows/release.yml").readText()
        assertThat(workflow).contains("dist/fxloop-server-all.jar").contains("dist/fxloop-skills.zip").contains("SHA256SUMS")
            .contains("publishToMavenLocal").contains("-pl fxloop-server")
    }
}
