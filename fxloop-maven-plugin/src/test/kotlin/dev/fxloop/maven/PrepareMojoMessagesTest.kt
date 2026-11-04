package dev.fxloop.maven

import dev.fxloop.launch.MainClassCandidate
import dev.fxloop.launch.MainClassException
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PrepareMojoMessagesTest {

    @Test
    fun noneFoundMessage() {
        val classes = Path.of("/x/classes")
        val e = MainClassException(listOf(classes), emptyList())
        assertThat(PrepareMojo.mainClassMessage(e))
            .isEqualTo("No main class found in $classes. Compile first, or set -Dfxloop.mainClass=<fqcn>.")
    }

    @Test
    fun ambiguousMessageListsCandidatesAndHint() {
        val e = MainClassException(
            listOf(Path.of("/x")),
            listOf(MainClassCandidate("p.A", true, false, false), MainClassCandidate("p.M", false, true, true)),
        )
        assertThat(PrepareMojo.mainClassMessage(e)).isEqualTo(
            "Cannot determine the main class unambiguously. Candidates:\n  - p.A (extends Application)\n" +
                "  - p.M (declares main) [launcher]\n" +
                "Set -Dfxloop.mainClass=<fqcn> (or <mainClass> in the fxloop plugin configuration).",
        )
    }
}
