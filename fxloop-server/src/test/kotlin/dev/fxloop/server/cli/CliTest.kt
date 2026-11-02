package dev.fxloop.server.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliTest {
    @Test
    fun jdkCheckVerdicts() {
        assertThat(JdkCheck.problem(21, true)).isNull()
        assertThat(JdkCheck.problem(17, true)).isNull()
        assertThat(JdkCheck.problem(11, true)).contains("too old")
        assertThat(JdkCheck.problem(21, false)).contains("JRE")
    }

    @Test
    fun reportExitCode() {
        val props = mapOf("java.version" to "21.0.1", "java.vendor" to "X", "java.home" to "/jdk")
        val ok = JdkCheck.report("1.0", "abc", props, 21, true)
        assertThat(ok.second).isZero()
        assertThat(ok.first).hasSizeLessThanOrEqualTo(5)
        assertThat(ok.first[0]).isEqualTo("fxloop-server 1.0 (commit abc)")
        assertThat(JdkCheck.report("1.0", "abc", props, 21, false).second).isNotZero()
    }

    @Test
    fun seedsStandardLayoutWithMetadata(@TempDir repo: Path) {
        val written = SeedLocalRepo.seed(repo)
        val g = repo.resolve("dev/fxloop")
        for (c in SeedLocalRepo.COORDS) {
            val a = g.resolve(c.artifactId)
            val v = Files.list(a).use { s -> s.filter { Files.isDirectory(it) }.toList() }.single()
            val ver = v.fileName.toString()
            assertThat(v.resolve("${c.artifactId}-$ver.pom")).isRegularFile()
            if (c.jar) assertThat(v.resolve("${c.artifactId}-$ver.jar")).isNotEmptyFile()
            assertThat(a.resolve("maven-metadata-local.xml")).content().contains("<version>$ver</version>")
            if (ver.endsWith("-SNAPSHOT")) {
                assertThat(v.resolve("maven-metadata-local.xml")).content()
                    .contains("<localCopy>true</localCopy>", "<extension>pom</extension>")
            }
        }
        assertThat(written).allSatisfy { assertThat(it).startsWith(g) }
        // Only dev/fxloop is touched.
        assertThat(Files.list(repo).use { s -> s.map { it.fileName.toString() }.toList() }).containsExactly("dev")
        assertThat(Files.list(repo.resolve("dev")).use { s -> s.map { it.fileName.toString() }.toList() }).containsExactly("fxloop")
        // Idempotent, and a second version is added to the artifact metadata, not replacing it.
        SeedLocalRepo.seed(repo)
        assertThat(g.resolve("fxloop-agent/maven-metadata-local.xml")).content().containsOnlyOnce("<version>")
    }

    @Test
    fun pomVersionFallsBackToParent() {
        val pom = "<project><parent><version>2.0</version></parent></project>".toByteArray()
        assertThat(SeedLocalRepo.pomVersion(pom)).isEqualTo("2.0")
    }
}
