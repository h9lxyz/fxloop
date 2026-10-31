package dev.fxloop.gradle

import dev.fxloop.protocol.LaunchDescriptor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class FxloopPluginFunctionalTest {

    @TempDir
    lateinit var project: Path

    private fun app(name: String) = """
        package demo;
        import javafx.application.Application;
        import javafx.scene.Scene;
        import javafx.scene.control.Label;
        import javafx.stage.Stage;
        public class $name extends Application {
            @Override public void start(Stage s) { s.setScene(new Scene(new Label("hi"))); s.show(); }
        }
    """.trimIndent()

    private val launcher = """
        package demo;
        public class Launcher {
            public static void main(String[] args) { javafx.application.Application.launch(DemoApp.class, args); }
        }
    """.trimIndent()

    private fun write(rel: String, content: String) {
        val p = project.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
    }

    private val fxDeps = """
        dependencies {
            implementation("org.openjfx:javafx-controls:21.0.13:linux")
            implementation("org.openjfx:javafx-graphics:21.0.13:linux")
            implementation("org.openjfx:javafx-base:21.0.13:linux")
        }
    """.trimIndent()

    private fun buildFile(extra: String = "") {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\n")
        write(
            "build.gradle.kts",
            "plugins {\n    java\n    id(\"dev.fxloop\")\n}\nrepositories { mavenCentral() }\n$fxDeps\n$extra\n",
        )
    }

    private fun runner(vararg args: String) =
        GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            // TestKit daemons otherwise idle for 3 h after every test run
            .withArguments(*args, "-Dorg.gradle.daemon.idletimeout=60000")

    private fun descriptor() = LaunchDescriptor.readFrom(project.resolve("build/fxloop/launch.json"))

    private fun real(rel: String) = project.resolve(rel).toRealPath().toString()

    @Test
    fun `launcher pattern is preferred and descriptor is written`() {
        buildFile()
        write("src/main/java/demo/DemoApp.java", app("DemoApp"))
        write("src/main/java/demo/Launcher.java", launcher)
        write("src/main/resources/demo/view.fxml", "<x/>")

        val r = runner("fxloopPrepare", "--stacktrace").build()
        assertThat(r.output).contains("FXLoop: mainClass=demo.Launcher")

        val d = descriptor()
        assertEquals("gradle", d.buildTool)
        assertEquals("demo.Launcher", d.mainClass)
        assertNull(d.mainModule)
        assertEquals(real("build/classes/java/main"), d.outputDir)
        assertThat(d.classpath).anyMatch { it.contains("javafx-controls-21.0.13-linux.jar") }
        assertThat(d.classpath).anyMatch { it.contains("javafx-base-21.0.13-linux.jar") }
        assertThat(d.classpath).doesNotContain(d.outputDir)
        assertThat(d.classpath).contains(real("build/resources/main"))
        assertTrue(d.classpath.all { Path.of(it).isAbsolute })
        assertThat(d.modulePath).isEmpty()
        assertThat(d.addModules).isEmpty()
        assertThat(d.sourceRoots).containsExactly(real("src/main/java"))
        assertThat(d.resourceRoots).containsExactly(real("src/main/resources"))
        assertEquals(project.toRealPath().toString(), d.workingDir)
        assertEquals(Runtime.version().feature().toString(), d.release)
        assertTrue(!d.annotationProcessors)
    }

    private val kotlinApp = """
        package demo
        import javafx.application.Application
        import javafx.scene.Scene
        import javafx.scene.control.Label
        import javafx.stage.Stage
        class DemoApp : Application() {
            override fun start(s: Stage) { s.scene = Scene(Label("hi")); s.show() }
        }
        fun main(args: Array<String>) = Application.launch(DemoApp::class.java, *args)
    """.trimIndent()

    private fun kotlinBuildFile() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\n")
        write(
            "build.gradle.kts",
            "plugins {\n    kotlin(\"jvm\") version \"2.4.21\"\n    id(\"dev.fxloop\")\n}\n" +
                "repositories { mavenCentral() }\n$fxDeps\n",
        )
    }

    @Test
    fun `Kotlin project uses the Kotlin output and sources`() {
        kotlinBuildFile()
        write("src/main/kotlin/demo/Main.kt", kotlinApp)
        write("src/main/resources/demo/view.fxml", "<x/>")

        val r = runner("fxloopPrepare", "--stacktrace").build()
        assertThat(r.output).contains("FXLoop: mainClass=demo.MainKt")

        val d = descriptor()
        assertEquals("demo.MainKt", d.mainClass)
        assertEquals(real("build/classes/kotlin/main"), d.outputDir)
        assertThat(d.classpath).doesNotContain(d.outputDir)
        assertThat(d.classpath).noneMatch { it.contains("classes/java") }
        assertThat(d.classpath).contains(real("build/resources/main"))
        assertThat(d.classpath).anyMatch { it.contains("kotlin-stdlib") }
        assertThat(d.sourceRoots).containsExactly(real("src/main/kotlin"))
        assertThat(d.resourceRoots).containsExactly(real("src/main/resources"))
    }

    @Test
    fun `mixed Kotlin and Java keeps the Java output and puts Kotlin classes on the classpath`() {
        kotlinBuildFile()
        write("src/main/kotlin/demo/Main.kt", kotlinApp)
        write("src/main/java/demo/Util.java", "package demo;\npublic class Util { }\n")

        runner("fxloopPrepare").build()
        val d = descriptor()
        assertEquals("demo.MainKt", d.mainClass)
        assertEquals(real("build/classes/java/main"), d.outputDir)
        assertEquals(real("build/classes/kotlin/main"), d.classpath.first())
        assertThat(d.sourceRoots).containsExactlyInAnyOrder(real("src/main/java"), real("src/main/kotlin"))
    }

    @Test
    fun `single Application subclass is found`() {
        buildFile()
        write("src/main/java/demo/DemoApp.java", app("DemoApp"))
        runner("fxloopPrepare").build()
        assertEquals("demo.DemoApp", descriptor().mainClass)
    }

    @Test
    fun `ambiguity fails listing candidates`() {
        buildFile()
        write("src/main/java/demo/AppOne.java", app("AppOne"))
        write("src/main/java/demo/AppTwo.java", app("AppTwo"))
        val r = runner("fxloopPrepare").buildAndFail()
        assertThat(r.output)
            .contains("cannot determine the main class unambiguously")
            .contains("demo.AppOne (extends Application)")
            .contains("demo.AppTwo (extends Application)")
            .contains("fxloop { mainClass")
    }

    @Test
    fun `fxloopRun starts the main class with the agent, dev bar and extra args`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\n")
        write(
            "build.gradle.kts",
            "plugins {\n    java\n    id(\"dev.fxloop\")\n}\n" +
                "repositories { mavenLocal(); mavenCentral() }\n$fxDeps\nfxloop {\n    mainClass = \"demo.Probe\"\n" +
                "    appArgs = listOf(\"--seed\")\n}\n",
        )
        // no JavaFX toolkit starts, so the agent stays idle and the JVM exits after main
        write(
            "src/main/java/demo/Probe.java",
            """
            package demo;
            public class Probe {
                public static void main(String[] args) {
                    String agent = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                        .filter(a -> a.startsWith("-javaagent:")).findFirst().orElse("");
                    System.out.println("probe agent=" + agent.contains("fxloop-agent") + " bar=" + agent.contains(",bar=on")
                        + " handshake=" + agent.contains("handshake=") + " args=" + java.util.Arrays.toString(args));
                }
            }
            """.trimIndent(),
        )
        val out = runner("fxloopRun", "--args=--de --rtl").build().output
        assertThat(out).contains("probe agent=true bar=true handshake=true args=[--seed, --de, --rtl]")
        assertThat(out).doesNotContain("cannot resolve dev.fxloop:fxloop-server")
        assertThat(project.resolve("build/fxloop/handshake.json")).doesNotExist()
    }

    @Test
    fun `extension overrides everything and passes args`() {
        buildFile(
            """
            fxloop {
                mainClass = "demo.AppTwo"
                jvmArgs = listOf("-Xmx64m")
                appArgs = listOf("--seed")
            }
            """.trimIndent(),
        )
        write("src/main/java/demo/AppOne.java", app("AppOne"))
        write("src/main/java/demo/AppTwo.java", app("AppTwo"))
        runner("fxloopPrepare").build()
        val d = descriptor()
        assertEquals("demo.AppTwo", d.mainClass)
        assertThat(d.jvmArgs).containsExactly("-Xmx64m")
        assertThat(d.appArgs).containsExactly("--seed")
    }

    @Test
    fun `application plugin mainClass is used before scan`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\n")
        write(
            "build.gradle.kts",
            "plugins { application; id(\"dev.fxloop\") }\nrepositories { mavenCentral() }\n$fxDeps\n" +
                "application { mainClass = \"demo.AppOne\" }\n",
        )
        write("src/main/java/demo/AppOne.java", app("AppOne"))
        write("src/main/java/demo/AppTwo.java", app("AppTwo"))
        runner("fxloopPrepare").build()
        assertEquals("demo.AppOne", descriptor().mainClass)
    }

    @Test
    fun `modulepath mode splits JavaFX jars and works with configuration cache`() {
        buildFile("fxloop { launchMode = \"modulepath\" }")
        write("src/main/java/demo/DemoApp.java", app("DemoApp"))
        runner("fxloopPrepare", "--configuration-cache").build()
        val second: BuildResult = runner("fxloopPrepare", "--configuration-cache").build()
        assertThat(second.output).contains("Reusing configuration cache")

        val d = descriptor()
        assertNull(d.mainModule)
        assertThat(d.modulePath).anyMatch { it.contains("javafx-controls-21.0.13-linux.jar") }
        assertThat(d.addModules).contains("javafx.controls", "javafx.base", "javafx.graphics")
        assertThat(d.classpath).noneMatch { it.contains("javafx-") }
    }

    @Test
    fun `release comes from compile options`() {
        buildFile("tasks.withType<JavaCompile> { options.release = 17 }")
        write("src/main/java/demo/DemoApp.java", app("DemoApp"))
        runner("fxloopPrepare").build()
        assertEquals("17", descriptor().release)
    }

    @Test
    fun `invalid launch mode fails`() {
        buildFile("fxloop { launchMode = \"bogus\" }")
        write("src/main/java/demo/DemoApp.java", app("DemoApp"))
        assertThat(runner("fxloopPrepare").buildAndFail().output).contains("Invalid fxloop.launchMode 'bogus'")
    }

    @Test
    fun `module name parsing ignores comments`() {
        assertEquals(
            "my.app",
            FxloopPrepareTask.parseModuleName("/* module no { */ // module nope {\nopen module my.app {\n}"),
        )
        assertEquals(listOf("javafx.controls"), JavaFxModules.moduleNames(listOf("javafx-controls", "javafx-controls", "x")))
    }
}
