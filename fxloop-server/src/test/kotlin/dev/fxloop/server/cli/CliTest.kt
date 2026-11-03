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
        val skipped = SeedLocalRepo.missing()
        for (c in SeedLocalRepo.COORDS) {
            val a = g.resolve(c.artifactId)
            if (c in skipped) { // this server build was made without the Gradle plugin in mavenLocal
                assertThat(c.optional).isTrue()
                assertThat(a).doesNotExist()
                continue
            }
            val v = Files.list(a).use { s -> s.filter { Files.isDirectory(it) }.toList() }.single()
            val ver = v.fileName.toString()
            assertThat(v.resolve("${c.artifactId}-$ver.pom")).isRegularFile()
            if (c.jar) assertThat(v.resolve("${c.artifactId}-$ver${c.classifier?.let { "-$it" } ?: ""}.jar")).isNotEmptyFile()
            if (c.module) assertThat(v.resolve("${c.artifactId}-$ver.module")).isNotEmptyFile()
            assertThat(a.resolve("maven-metadata-local.xml")).content().contains("<version>$ver</version>")
            if (ver.endsWith("-SNAPSHOT")) {
                assertThat(v.resolve("maven-metadata-local.xml")).content()
                    .contains("<localCopy>true</localCopy>", "<extension>pom</extension>")
                if (c.module) assertThat(v.resolve("maven-metadata-local.xml")).content().contains("<extension>module</extension>")
                if (c.classifier != null) assertThat(v.resolve("maven-metadata-local.xml")).content().contains("<classifier>${c.classifier}</classifier>")
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
    fun seedSkipsOptionalArtifactsThatAreNotBundled(@TempDir repo: Path) {
        val pom = "<project><groupId>dev.fxloop</groupId><artifactId>x</artifactId><version>9.9.9</version></project>".toByteArray()
        val source = { name: String -> if (name.startsWith("fxloop-gradle-plugin") || name.startsWith("dev.fxloop.gradle.plugin")) null
            else if (name.endsWith(".pom")) pom.inputStream() else byteArrayOf(1).inputStream() }
        assertThat(SeedLocalRepo.missing(source = source).map { it.artifactId }).containsExactly("fxloop-gradle-plugin", "dev.fxloop.gradle.plugin")
        SeedLocalRepo.seed(repo, source = source)
        assertThat(repo.resolve("dev/fxloop/fxloop-maven-plugin/9.9.9/fxloop-maven-plugin-9.9.9.jar")).isNotEmptyFile()
        // the server jar is stored under its `all` classifier, as the run goals resolve it
        assertThat(repo.resolve("dev/fxloop/fxloop-server/9.9.9/fxloop-server-9.9.9-all.jar")).isNotEmptyFile()
        assertThat(repo.resolve("dev/fxloop/fxloop-server/9.9.9/fxloop-server-9.9.9.jar")).describedAs("main-name alias for Gradle's mavenLocal").isNotEmptyFile()
        assertThat(repo.resolve("dev/fxloop/fxloop-server/9.9.9/fxloop-server-9.9.9.pom")).isRegularFile()
        assertThat(repo.resolve("dev/fxloop/fxloop-server/9.9.9/maven-metadata-local.xml")).doesNotExist() // not a SNAPSHOT
        assertThat(repo.resolve("dev/fxloop/fxloop-gradle-plugin")).doesNotExist()
        assertThat(repo.resolve("dev/fxloop/dev.fxloop.gradle.plugin")).doesNotExist()
        // and with everything bundled, the Gradle plugin gets its module metadata and the marker its POM
        val full = { name: String -> if (name.endsWith(".pom")) pom.inputStream() else byteArrayOf(1).inputStream() }
        SeedLocalRepo.seed(repo, source = full)
        assertThat(repo.resolve("dev/fxloop/fxloop-gradle-plugin/9.9.9/fxloop-gradle-plugin-9.9.9.module")).isNotEmptyFile()
        assertThat(repo.resolve("dev/fxloop/dev.fxloop.gradle.plugin/9.9.9/dev.fxloop.gradle.plugin-9.9.9.pom")).isRegularFile()
    }

    @Test
    fun serverPomCarriesTheVersion() {
        assertThat(SeedLocalRepo.pomVersion(SeedLocalRepo.serverPom("1.2.3").toByteArray())).isEqualTo("1.2.3")
        assertThat(SeedLocalRepo.serverPom("1.2.3")).contains("<artifactId>fxloop-server</artifactId>", "<packaging>jar</packaging>")
    }

    @Test
    fun usageNamesEveryCommand() {
        val u = dev.fxloop.server.usage()
        assertThat(u).contains("--version").contains("doctor").contains("--seed-local-repo").contains("--tool-reference").contains("INSTALL.md")
    }

    @Test
    fun launchModeIsAServerKey(@TempDir dir: Path) {
        assertThat(dev.fxloop.server.Config.load(emptyMap(), emptyArray(), dir).launchMode).isNull()
        assertThat(dev.fxloop.server.Config.load(mapOf("FXLOOP_LAUNCH_MODE" to "modulepath"), emptyArray(), dir).launchMode).isEqualTo("modulepath")
        Files.writeString(dir.resolve("fxloop.properties"), "launch-mode=Classpath\n")
        assertThat(dev.fxloop.server.Config.load(emptyMap(), emptyArray(), dir).launchMode).isEqualTo("classpath")
    }

    @Test
    fun pomVersionFallsBackToParent() {
        val pom = "<project><parent><version>2.0</version></parent></project>".toByteArray()
        assertThat(SeedLocalRepo.pomVersion(pom)).isEqualTo("2.0")
    }
}
