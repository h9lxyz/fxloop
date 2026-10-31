package dev.fxloop.maven

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.apache.maven.plugin.MojoFailureException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MainClassResolverTest {

    @TempDir
    lateinit var tmp: Path

    private fun compile(sources: Map<String, String>): Path {
        val src = Files.createDirectories(tmp.resolve("src"))
        val out = Files.createDirectories(tmp.resolve("out"))
        val files = (STUB + sources).map { (name, code) ->
            val f = src.resolve(name)
            Files.createDirectories(f.parent)
            Files.writeString(f, code)
            f.toString()
        }
        val jc = ToolProvider.getSystemJavaCompiler()
        val args = listOf("-d", out.toString(), "-proc:none") + files
        assertThat(jc.run(null, null, null, *args.toTypedArray())).isZero()
        // drop the stub from the "project" output so only user classes are scanned
        Files.delete(out.resolve("javafx/application/Application.class"))
        return out
    }

    private fun scan(out: Path) = MainClassResolver.Inputs(null, null, null, null, out)

    @Test
    fun explicitWins() {
        val r = MainClassResolver.resolve(MainClassResolver.Inputs("a.B", "c.D", "e.F", "g.H", tmp))
        assertThat(r.mainClass).isEqualTo("a.B")
        assertThat(r.mainModule).isNull()
    }

    @Test
    fun javafxPluginSplitsModuleAndClass() {
        val r = MainClassResolver.resolve(MainClassResolver.Inputs(" ", "my.mod/p.Main", "e.F", "g.H", tmp))
        assertThat(r.mainModule).isEqualTo("my.mod")
        assertThat(r.mainClass).isEqualTo("p.Main")
    }

    @Test
    fun execPluginBeatsManifest() {
        val r = MainClassResolver.resolve(MainClassResolver.Inputs(null, null, "e.F", "g.H", tmp))
        assertThat(r.mainClass).isEqualTo("e.F")
    }

    @Test
    fun manifestUsedBeforeScan() {
        val r = MainClassResolver.resolve(MainClassResolver.Inputs(null, null, null, "g.H", tmp))
        assertThat(r.mainClass).isEqualTo("g.H")
    }

    @Test
    fun scanPrefersLauncherPattern() {
        val out = compile(mapOf("p/App.java" to APP, "p/Launcher.java" to LAUNCHER))
        assertThat(MainClassResolver.resolve(scan(out)).mainClass).isEqualTo("p.Launcher")
    }

    @Test
    fun scanFallsBackToApplicationSubclass() {
        val out = compile(mapOf("p/App.java" to APP, "p/Util.java" to "package p; public class Util {}"))
        assertThat(MainClassResolver.resolve(scan(out)).mainClass).isEqualTo("p.App")
    }

    @Test
    fun scanFindsPlainMain() {
        val out = compile(mapOf("p/Tool.java" to "package p; public class Tool { public static void main(String[] a) {} }"))
        assertThat(MainClassResolver.resolve(scan(out)).mainClass).isEqualTo("p.Tool")
    }

    @Test
    fun scanAmbiguousListsCandidates() {
        val out = compile(
            mapOf(
                "p/App.java" to APP,
                "p/App2.java" to "package p; public class App2 extends javafx.application.Application {}",
            ),
        )
        assertThatThrownBy { MainClassResolver.resolve(scan(out)) }
            .isInstanceOf(MojoFailureException::class.java)
            .hasMessageContaining("p.App (extends Application)")
            .hasMessageContaining("p.App2 (extends Application)")
            .hasMessageContaining("fxloop.mainClass")
    }

    @Test
    fun scanNothingFound() {
        val out = compile(mapOf("p/Util.java" to "package p; public class Util {}"))
        assertThatThrownBy { MainClassResolver.resolve(scan(out)) }
            .isInstanceOf(MojoFailureException::class.java)
            .hasMessageContaining("No main class")
    }

    private companion object {
        // Java source fixtures, compiled at test time with javax.tools.
        val STUB = mapOf(
            "javafx/application/Application.java" to
                "package javafx.application; public abstract class Application {" +
                " public static void launch(Class<? extends Application> c, String... a) {} }",
        )
        const val APP = "package p; public class App extends javafx.application.Application {}"
        const val LAUNCHER = "package p; public class Launcher { public static void main(String[] a)" +
            " { javafx.application.Application.launch(App.class, a); } }"
    }
}
