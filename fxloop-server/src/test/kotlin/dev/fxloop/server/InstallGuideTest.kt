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
 * README's install lines, and the assets the edge build publishes.
 */
class InstallGuideTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isDirectory(it.resolve("fxloop-skills")) }
    private val guide = repo.resolve("INSTALL.md").readText()
    private val readme = repo.resolve("README.md").readText()
    private val connect = repo.resolve("fxloop-skills/skills/fxloop-connect/references")

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
    fun `readme sends agents to the raw install guide and shares its download lines`() {
        assertThat(readme).contains("https://raw.githubusercontent.com/h9lxyz/fxloop/main/INSTALL.md").contains("](INSTALL.md")
        for (asset in listOf("releases/download/edge/fxloop-server-all.jar", "releases/download/edge/fxloop-skills.zip")) {
            assertThat(readme).describedAs(asset).contains(asset)
            assertThat(guide).describedAs(asset).contains(asset)
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
    fun `edge build publishes the asset names the docs download`() {
        val script = repo.resolve("release/assemble-assets.sh").readText()
        val workflow = repo.resolve(".github/workflows/edge.yml").readText()
        assertThat(script).contains("fxloop-server-all.jar").contains("fxloop-skills.zip").contains("SHA256SUMS")
        assertThat(workflow).contains("release/assemble-assets.sh").contains("tag_name: edge").contains("prerelease: true")
            .contains("publishToMavenLocal").contains("-pl fxloop-server")
        assertThat(Files.isExecutable(repo.resolve("release/assemble-assets.sh"))).describedAs("assemble-assets.sh is executable").isTrue()
    }
}
