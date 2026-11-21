package org.lab59.fxloop.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The README's "Start the app yourself" quickstart: one download of the latest release (no version to bump when a
 * release is cut) and the `attach` command, with no build-file edits and no snapshot version.
 */
class ReadmeQuickstartTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isDirectory(it.resolve("fxloop-skills")) }
    private val readme = repo.resolve("README.md").readText()

    @Test
    fun `start-it-yourself quickstart downloads the latest release and runs attach`() {
        assertThat(readme).contains("releases/latest/download/fxloop-server-all.jar")
            .contains("java -jar \"\$HOME/.fxloop/fxloop-server.jar\" attach")
            .contains("(docs/attach.md)")
            .doesNotContain("SNAPSHOT")
            .doesNotContain("<artifactId>fxloop-maven-plugin</artifactId>")
        assertThat(Regex("releases/download/\\d+\\.\\d+\\.\\d+/").containsMatchIn(readme)).describedAs("a pinned release in the README").isFalse()
    }
}
