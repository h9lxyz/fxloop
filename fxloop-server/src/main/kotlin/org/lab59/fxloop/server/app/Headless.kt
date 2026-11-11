package org.lab59.fxloop.server.app

import org.lab59.fxloop.launch.LaunchDescriptor
import org.lab59.fxloop.server.Config
import org.lab59.fxloop.server.mcp.ToolException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless lanes (PRD 03 §4): `FXLOOP_HEADLESS=monocle` adds the Monocle system properties and the matching
 * `openjfx-monocle` jar; `xvfb` wraps the launch in `xvfb-run` (handled by [AppSession]).
 */
object Headless {
    /**
     * Our compatibility table: JavaFX feature line → openjfx-monocle version. Missing line → use xvfb.
     * There is no Monocle 25 or 27; 21.0.2 runs FXLoop's own headless suites on JavaFX 25 and 27 (CI lanes).
     */
    val MONOCLE = mapOf(17 to "17.0.10", 21 to "21.0.2", 25 to "21.0.2", 27 to "21.0.2")

    private val FX_JAR = Regex("""javafx-graphics-(\d+)[.\d]*.*\.jar$""")

    fun jvmArgs(c: Config): List<String> =
        if (!c.headless.equals("monocle", true)) emptyList()
        else listOf("-Dglass.platform=Monocle", "-Dmonocle.platform=Headless", "-Dprism.order=sw", "-Dprism.text=t2k",
            "-Dtestfx.headless=true", "-Djava.awt.headless=true")

    fun extraClasspath(c: Config, d: LaunchDescriptor): List<String> {
        if (!c.headless.equals("monocle", true)) return emptyList()
        c.monocleJar?.let { return listOf(it) }
        if (d.classpath.any { "openjfx-monocle" in it }) return emptyList()
        val line = javafxLine(d)
        val version = MONOCLE[line] ?: throw ToolException("HEADLESS_UNSUPPORTED",
            "no openjfx-monocle build is known for JavaFX $line", "use FXLOOP_HEADLESS=xvfb for this JavaFX line (docs/ci/monocle.md)")
        val jar = Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "testfx", "openjfx-monocle", version,
            "openjfx-monocle-$version.jar")
        if (!Files.exists(jar)) {
            throw ToolException("HEADLESS_UNSUPPORTED", "openjfx-monocle $version not found at $jar",
                "add org.testfx:openjfx-monocle:$version as a test dependency (or set FXLOOP_MONOCLE_JAR)")
        }
        return listOf(jar.toString())
    }

    fun javafxLine(d: LaunchDescriptor): Int =
        (d.classpath + d.modulePath).firstNotNullOfOrNull { FX_JAR.find(it)?.groupValues?.get(1)?.toInt() } ?: -1
}
