package org.lab59.fxloop.launch

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LaunchDescriptorTest {
    @Test
    fun `launch descriptor round trips and tolerates missing arrays`(@TempDir dir: Path) {
        val d = LaunchDescriptor.builder().buildTool("maven").mainClass("a.B").classpath(listOf("/x.jar"))
            .workingDir("/w").outputDir("/w/target/classes").build()
        val file = dir.resolve("launch.json")
        d.writeTo(file)
        assertThat(LaunchDescriptor.readFrom(file)).isEqualTo(d)
        Files.writeString(file, """{"buildTool":"gradle","mainClass":"a.B","workingDir":"/w","outputDir":"/o"}""")
        assertThat(LaunchDescriptor.readFrom(file).classpath).isEmpty()
    }

    @Test
    fun `schema ships next to the descriptor`() {
        assertThat(LaunchDescriptor::class.java.getResource("launch.schema.json")).isNotNull()
    }

    @Test
    fun `run command uses the class path or the main module`() {
        val sep = File.pathSeparator
        val cp = LaunchDescriptor.builder().buildTool("maven").mainClass("a.B").classpath(listOf("/x.jar"))
            .jvmArgs(listOf("-Dj")).appArgs(listOf("app")).workingDir("/w").outputDir("/o").build()
        assertThat(cp.runCommand("java", "agent.jar=x", listOf("--seed")))
            .containsExactly("java", "-Dj", "-javaagent:agent.jar=x", "-cp", "/o$sep/x.jar", "a.B", "app", "--seed")
        val mod = cp.copy(mainModule = "m", classpath = emptyList(), modulePath = listOf("/o", "/fx.jar"), addModules = listOf("javafx.base"))
        assertThat(mod.runCommand("java", "a.jar"))
            .containsExactly("java", "-Dj", "-javaagent:a.jar", "--module-path", "/o$sep/fx.jar", "--add-modules", "javafx.base", "-m", "m/a.B", "app")
    }
}
