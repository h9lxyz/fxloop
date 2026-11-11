package org.lab59.fxloop.acceptance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * PRD 11 4.2 (A-O1/A-O4 subset): the server prepares and launches a project that has no FXLoop in its build files,
 * and `fx_get_app_status` says which route ran. The Gradle lane needs the bundled Gradle plugin in the server jar
 * (build order: see fxloop-server/pom.xml), so it is skipped when the resource is absent.
 */
class PluginFreePrepareTest {
    @Test
    fun `maven app starts through the server's own prepare`() {
        Harness(configure = { it.copy(prepareRoute = "server") }).use { h ->
            val st = h.ok("fx_get_app_status")
            assertThat(st["state"].asText()).isEqualTo("running")
            assertThat(st["prepare"].asText()).isEqualTo("server")
            assertThat(st["buildTool"].asText()).isEqualTo("maven")
            assertThat(st["mainClass"].asText()).isEqualTo("org.lab59.fxloop.sample.Launcher")
        }
    }

    @Test
    fun `gradle kotlin app without the plugin starts through the init script`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            PluginFreePrepareTest::class.java.getResource("/org/lab59/fxloop/server/gradle/fxloop-gradle-plugin.jar") != null,
            "server built without the bundled Gradle plugin",
        )
        Harness(app = "fxloop-sample-app-kotlin").use { h ->
            val build = h.project.resolve("build.gradle.kts")
            build.writeText(build.readText().replace(Regex("\\s*id\\(\"org\\.lab59\\.fxloop\"\\)[^\\n]*"), ""))
            assertThat(build.readText()).doesNotContain("id(\"org.lab59.fxloop\")")
            val st = h.ok("fx_get_app_status")
            assertThat(st["state"].asText()).isEqualTo("running")
            assertThat(st["prepare"].asText()).isEqualTo("server")
            assertThat(st["mainClass"].asText()).isEqualTo("org.lab59.fxloop.sample.MainKt")
        }
    }
}
