package dev.fxloop.server.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class EffectivePomTest {
    private val effective = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <properties><maven.compiler.release>21</maven.compiler.release><maven.compiler.target>17</maven.compiler.target></properties>
          <dependencies>
            <dependency><groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId><version>21</version><scope>compile</scope></dependency>
            <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version><scope>provided</scope></dependency>
          </dependencies>
          <build>
            <sourceDirectory>/app/src/main/java</sourceDirectory>
            <outputDirectory>/app/target/classes</outputDirectory>
            <resources><resource><directory>/app/src/main/resources</directory></resource></resources>
            <plugins>
              <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration><release>21</release></configuration>
              </plugin>
              <plugin>
                <groupId>org.openjfx</groupId><artifactId>javafx-maven-plugin</artifactId>
                <configuration><mainClass>p.Fx</mainClass><options><option>--enable-preview</option><option>-Xmx1g</option></options></configuration>
                <executions><execution><id>default-cli</id><configuration><commandlineArgs>--seed 1</commandlineArgs></configuration></execution></executions>
              </plugin>
            </plugins>
            <pluginManagement><plugins>
              <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId>
                <configuration><archive><manifest><mainClass>p.Jar</mainClass></manifest></archive></configuration></plugin>
            </plugins></pluginManagement>
          </build>
        </project>
    """.trimIndent()

    @Test
    fun readsBuildDirsPropertiesAndDependencies() {
        val p = EffectivePom.parse(effective)
        assertThat(p.sourceDirectory).isEqualTo("/app/src/main/java")
        assertThat(p.outputDirectory).isEqualTo("/app/target/classes")
        assertThat(p.resourceDirectories).containsExactly("/app/src/main/resources")
        assertThat(p.property("maven.compiler.release")).isEqualTo("21")
        assertThat(p.dependencies.map { it.artifactId to it.scope }).containsExactly("javafx-controls" to "compile", "lombok" to "provided")
    }

    @Test
    fun pluginConfigurationLooksAtPluginThenExecutionsAndPluginManagement() {
        val p = EffectivePom.parse(effective)
        val fx = p.plugin("org.openjfx", "javafx-maven-plugin")!!
        assertThat(fx.text("mainClass")).isEqualTo("p.Fx")
        assertThat(fx.childTexts("options")).containsExactly("--enable-preview", "-Xmx1g")
        assertThat(fx.text("commandlineArgs")).isEqualTo("--seed 1")
        // groupId defaults to org.apache.maven.plugins
        assertThat(p.plugin("org.apache.maven.plugins", "maven-compiler-plugin")!!.text("release")).isEqualTo("21")
        assertThat(p.plugin("org.apache.maven.plugins", "maven-jar-plugin")!!.text("archive", "manifest", "mainClass")).isEqualTo("p.Jar")
        assertThat(p.plugin("org.codehaus.mojo", "exec-maven-plugin")).isNull()
    }

    @Test
    fun reactorEffectivePomPicksTheJavaFxProject() {
        val xml = """
            <projects>
              <project><artifactId>lib</artifactId><build><outputDirectory>/lib/classes</outputDirectory></build></project>
              <project><artifactId>app</artifactId><dependencies><dependency><groupId>org.openjfx</groupId><artifactId>javafx-base</artifactId></dependency></dependencies>
                <build><outputDirectory>/app/classes</outputDirectory></build></project>
              <project><artifactId>other</artifactId><build><outputDirectory>/other/classes</outputDirectory></build></project>
            </projects>
        """.trimIndent()
        assertThat(EffectivePom.parse(xml).outputDirectory).isEqualTo("/app/classes")
    }

    @Test
    fun doctypeIsRejected() {
        org.junit.jupiter.api.assertThrows<Exception> { EffectivePom.parse("<!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project>&x;</project>") }
    }

    @Test
    fun cacheKeyIncludesParentPoms(@TempDir dir: Path) {
        val parent = dir.resolve("pom.xml").also { Files.writeString(it, "<project/>") }
        val child = Files.createDirectories(dir.resolve("app")).resolve("pom.xml")
        Files.writeString(child, "<project><parent><artifactId>p</artifactId></parent></project>")
        Files.setLastModifiedTime(child, FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(parent, FileTime.fromMillis(5_000_000))
        assertThat(EffectivePom.newestPomMtime(child)).isEqualTo(5_000_000)

        val fxloop = Files.createDirectories(dir.resolve("app/target/fxloop"))
        val cached = fxloop.resolve(MavenPrepare.EFFECTIVE_POM).also { Files.writeString(it, effective) }
        Files.setLastModifiedTime(cached, FileTime.fromMillis(6_000_000))
        assertThat(MavenPrepare.effectivePomFresh(fxloop, child)).isTrue()
        Files.setLastModifiedTime(parent, FileTime.fromMillis(7_000_000)) // edited parent invalidates the cache
        assertThat(MavenPrepare.effectivePomFresh(fxloop, child)).isFalse()
        assertThat(MavenPrepare.goals(fxloop, child)).contains("help:effective-pom")
        Files.setLastModifiedTime(cached, FileTime.fromMillis(8_000_000))
        assertThat(MavenPrepare.goals(fxloop, child)).doesNotContain("help:effective-pom").contains("dependency:build-classpath")
    }
}
