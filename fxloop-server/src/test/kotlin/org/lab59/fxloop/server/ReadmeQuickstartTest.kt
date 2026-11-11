package org.lab59.fxloop.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The README's build-plugin quickstart pins one release. The jar it downloads seeds exactly its own plugin version into
 * ~/.m2, so the download URL and the pasted `<plugin>` block must name the same version, or `mvn fxloop:run` cannot
 * resolve the plugin. Both are bumped by hand when a release is cut (CONTRIBUTING.md).
 */
class ReadmeQuickstartTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isDirectory(it.resolve("fxloop-skills")) }
    private val readme = repo.resolve("README.md").readText()

    @Test
    fun `build plugin quickstart pastes the version it downloads`() {
        val downloaded = Regex("releases/download/(\\d+\\.\\d+\\.\\d+)/fxloop-server-all\\.jar").findAll(readme).map { it.groupValues[1] }.toSet()
        val pasted = Regex("<artifactId>fxloop-maven-plugin</artifactId>\\s*<version>([^<]+)</version>").findAll(readme).map { it.groupValues[1] }.toSet()
        assertThat(downloaded).describedAs("release versions the README downloads").hasSize(1)
        assertThat(pasted).describedAs("fxloop-maven-plugin versions the README pastes").isEqualTo(downloaded)
        assertThat(readme).contains("--seed-local-repo").contains("./mvnw fxloop:run").doesNotContain("SNAPSHOT")
    }
}
