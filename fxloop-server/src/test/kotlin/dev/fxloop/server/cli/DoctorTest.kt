package dev.fxloop.server.cli

import com.fasterxml.jackson.databind.ObjectMapper
import dev.fxloop.server.Config
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

/** PRD 11 §6 / A-O3: `doctor` on temp project fixtures. Never touches the real machine except the fixtures. */
class DoctorTest {
    private val jdk21 = JdkInfo("21.0.5", 21, "Eclipse Adoptium", "OpenJDK Runtime Environment", "OpenJDK 64-Bit Server VM",
        "/opt/jdk", hasCompiler = true, javaHomeEnv = null, pathJavaHome = null)

    private fun env(
        dir: Path, os: String = "Linux", vars: Map<String, String> = mapOf("DISPLAY" to ":0"),
        path: Set<String> = emptySet(), jdk: JdkInfo = jdk21, jar: Path? = null,
    ) = DoctorEnv(os, vars, { n -> if (n in path) Path.of("/usr/bin/$n") else null }, dir.resolve("home"), dir, jdk, jar)

    private fun cfg(dir: Path) = Config.load(emptyMap(), emptyArray(), dir)

    private fun run(dir: Path, env: DoctorEnv = env(dir), client: String? = null) = Doctor.run(cfg(dir), env, client).associateBy { it.id }

    private fun pom(dir: Path, extra: String = "") {
        Files.writeString(dir.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>app</artifactId><version>1</version>
              <properties><fx>21.0.2</fx><entry>demo.Main</entry></properties>
              <dependencies>
                <dependency><groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId><version>${'$'}{fx}</version></dependency>
                <dependency><groupId>org.openjfx</groupId><artifactId>javafx-fxml</artifactId><version>${'$'}{fx}</version></dependency>
              </dependencies>
              $extra
            </project>""".trimIndent())
    }

    /** Compiles tiny classes (with a stub javafx.application.Application, removed again) into target/classes. */
    private fun compile(dir: Path, vararg sources: Pair<String, String>) {
        val src = Files.createDirectories(dir.resolve("gen-src"))
        val out = Files.createDirectories(dir.resolve("target/classes"))
        val all = mutableListOf("javafx.application.Application" to "package javafx.application; public abstract class Application {}") + sources
        val files = all.map { (n, code) -> src.resolve(n.replace('.', '/') + ".java").also { Files.createDirectories(it.parent); Files.writeString(it, code) }.toFile() }
        val javac = ToolProvider.getSystemJavaCompiler()
        val fm = javac.getStandardFileManager(null, null, null)
        check(javac.getTask(null, fm, null, listOf("-d", out.toString()), null, fm.getJavaFileObjectsFromFiles(files)).call()) { "compile failed" }
        out.resolve("javafx").toFile().deleteRecursively()
    }

    private fun twoApps(dir: Path) = compile(dir,
        "demo.AppOne" to "package demo; public class AppOne extends javafx.application.Application {}",
        "demo.AppTwo" to "package demo; public class AppTwo extends javafx.application.Application {}")

    @Test
    fun `A-O3 two Application subclasses and no config lists both and the pin line, pinning resolves`(@TempDir dir: Path) {
        pom(dir); twoApps(dir)
        val c = run(dir)["main-class"]!!
        assertThat(c.status).isEqualTo(DoctorStatus.fail)
        assertThat(c.summary).contains("demo.AppOne").contains("demo.AppTwo")
        assertThat(c.fix).contains("main-class=demo.AppOne")
        @Suppress("UNCHECKED_CAST") val cands = c.data!!["candidates"] as List<Map<String, Any?>>
        assertThat(cands.map { it["name"] }).containsExactly("demo.AppOne", "demo.AppTwo")

        Files.writeString(dir.resolve("fxloop.properties"), "main-class=demo.AppTwo\n")
        val pinned = run(dir)["main-class"]!!
        assertThat(pinned.status).isEqualTo(DoctorStatus.ok)
        assertThat(pinned.summary).contains("demo.AppTwo").contains("rule: main-class in fxloop.properties")
    }

    @Test
    fun `single Application subclass is found by the scan and a pom mainClass wins with property substitution`(@TempDir dir: Path) {
        pom(dir); compile(dir, "demo.Only" to "package demo; public class Only extends javafx.application.Application {}")
        assertThat(run(dir)["main-class"]!!.summary).contains("demo.Only").contains("class scan")

        pom(dir, "<build><plugins><plugin><artifactId>javafx-maven-plugin</artifactId><configuration><mainClass>${'$'}{entry}</mainClass></configuration></plugin></plugins></build>")
        val c = run(dir)["main-class"]!!
        assertThat(c.status).isEqualTo(DoctorStatus.ok)
        assertThat(c.summary).contains("demo.Main").contains("javafx-maven-plugin")
    }

    @Test
    fun `not compiled yet is a warning that says compile first or pin`(@TempDir dir: Path) {
        pom(dir)
        val c = run(dir)["main-class"]!!
        assertThat(c.status).isEqualTo(DoctorStatus.warn)
        assertThat(c.fix).contains("compile first").contains("main-class=")
    }

    @Test
    fun `JRE and old JDK and JAVA_HOME mismatch verdicts`() {
        assertThat(Doctor.jdkCheck(jdk21).status).isEqualTo(DoctorStatus.ok)
        val jre = Doctor.jdkCheck(jdk21.copy(hasCompiler = false))
        assertThat(jre.status).isEqualTo(DoctorStatus.fail)
        assertThat(jre.summary).contains("JRE")
        assertThat(Doctor.jdkCheck(jdk21.copy(version = "11.0.2", feature = 11)).status).isEqualTo(DoctorStatus.fail)
        val mismatch = Doctor.jdkCheck(jdk21.copy(javaHomeEnv = "/nonexistent/a", pathJavaHome = "/nonexistent/b"))
        assertThat(mismatch.status).isEqualTo(DoctorStatus.warn)
        assertThat(mismatch.summary).contains("/nonexistent/a").contains("/nonexistent/b")
        assertThat(Doctor.jdkCheck(jdk21.copy(vendor = "JetBrains s.r.o.")).summary).contains("JetBrains Runtime")
    }

    @Test
    fun `headless Linux suggests xvfb when available, monocle otherwise, and respects configured headless`(@TempDir dir: Path) {
        pom(dir)
        val none = emptyMap<String, String>()
        val x = run(dir, env(dir, vars = none, path = setOf("xvfb-run")))["display"]!!
        assertThat(x.status).isEqualTo(DoctorStatus.warn)
        assertThat(x.fix).contains("headless=xvfb")
        val m = run(dir, env(dir, vars = none))["display"]!!
        assertThat(m.status).isEqualTo(DoctorStatus.warn)
        assertThat(m.fix).contains("headless=monocle")
        assertThat(run(dir, env(dir, vars = mapOf("WAYLAND_DISPLAY" to "wayland-0")))["display"]!!.status).isEqualTo(DoctorStatus.ok)
        assertThat(run(dir, env(dir, os = "Mac OS X", vars = none))["display"]!!.status).isEqualTo(DoctorStatus.ok)
        Files.writeString(dir.resolve("fxloop.properties"), "headless=monocle\n")
        assertThat(run(dir, env(dir, vars = none))["display"]!!.status).isEqualTo(DoctorStatus.ok)
    }

    @Test
    fun `build tool and JavaFX checks read the pom, wrapper and module-info`(@TempDir dir: Path) {
        pom(dir)
        val noTool = run(dir)["build-tool"]!!
        assertThat(noTool.status).isEqualTo(DoctorStatus.fail)
        Files.writeString(dir.resolve("mvnw"), "#!/bin/sh\n").toFile().setExecutable(true)
        assertThat(run(dir)["build-tool"]!!.summary).contains("Maven").contains("wrapper mvnw")
        val fx = run(dir)["javafx"]!!
        assertThat(fx.summary).contains("javafx-controls").contains("21.0.2").contains("-> launch mode classpath")
        Files.createDirectories(dir.resolve("src/main/java"))
        Files.writeString(dir.resolve("src/main/java/module-info.java"), "module demo { requires javafx.controls; }")
        assertThat(run(dir)["javafx"]!!.summary).contains("module-info.java (demo)").contains("modulepath")
    }

    @Test
    fun `Gradle project is read from build gradle`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("build.gradle.kts"), """
            plugins { id("org.openjfx.javafxplugin") version "0.1.0"; id("dev.fxloop") version "9.9.9" }
            javafx { version = "21.0.1"; modules = listOf("javafx.controls") }
            application { mainClass.set("demo.GMain") }
        """.trimIndent())
        val r = run(dir)
        assertThat(r["build-tool"]!!.summary).contains("Gradle")
        assertThat(r["javafx"]!!.summary).contains("javafx-controls").contains("21.0.1")
        assertThat(r["main-class"]!!.summary).contains("demo.GMain")
        assertThat(r["build-plugin"]!!.status).isEqualTo(DoctorStatus.warn)
    }

    @Test
    fun `a dev fxloop group id or package name is not the Gradle plugin`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("build.gradle.kts"), """
            plugins { kotlin("jvm") version "2.4.21"; application; id("org.openjfx.javafxplugin") version "0.1.0" }
            group = "dev.fxloop"
            application { mainClass = "dev.fxloop.sample.MainKt" }
        """.trimIndent())
        val absent = run(dir)["build-plugin"]!!
        assertThat(absent.status).isEqualTo(DoctorStatus.ok)
        assertThat(absent.summary).contains("no FXLoop build plugin")
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'dev.fxloop' version '9.9.9' }\n")
        Files.delete(dir.resolve("build.gradle.kts"))
        assertThat(run(dir)["build-plugin"]!!.status).isEqualTo(DoctorStatus.warn)
    }

    @Test
    fun `snippets use the home variable for clients that expand it`(@TempDir dir: Path) {
        pom(dir)
        val home = Files.createDirectories(dir.resolve("home"))
        val jar = Files.createDirectories(home.resolve(".fxloop")).resolve("fxloop-server.jar").also { Files.writeString(it, "x") }
        val all = run(dir, env(dir, jar = jar), client = "all")["mcp-client"]!!
        @Suppress("UNCHECKED_CAST") val snips = (all.data!!["snippets"] as List<Map<String, String>>).associate { it["client"]!! to it["content"]!! }
        assertThat(snips["claude-code"]).contains("\"\${HOME}/.fxloop/fxloop-server.jar\"")
        assertThat(snips["cursor"]).contains("\"\${userHome}/.fxloop/fxloop-server.jar\"")
        assertThat(snips["vscode"]).contains("\"\${userHome}/.fxloop/fxloop-server.jar\"")
        assertThat(snips["gemini"]).contains(jar.toString())
        assertThat(snips["codex"]).contains(jar.toString())
        val win = run(dir, env(dir, os = "Windows 11", jar = jar), client = "claude-code")["mcp-client"]!!
        @Suppress("UNCHECKED_CAST") val winSnip = (win.data!!["snippets"] as List<Map<String, String>>).single()["content"]!!
        assertThat(winSnip).contains("\${USERPROFILE}/.fxloop/fxloop-server.jar")
        // a jar outside the home directory stays absolute
        val elsewhere = Files.writeString(dir.resolve("fxloop-server.jar"), "x")
        @Suppress("UNCHECKED_CAST") val abs = (run(dir, env(dir, jar = elsewhere), client = "claude-code")["mcp-client"]!!.data!!["snippets"] as List<Map<String, String>>).single()["content"]!!
        assertThat(abs).contains(elsewhere.toString())
    }

    @Test
    fun `build plugin absent is fine and present with another version warns`(@TempDir dir: Path) {
        pom(dir)
        val absent = run(dir)["build-plugin"]!!
        assertThat(absent.status).isEqualTo(DoctorStatus.ok)
        assertThat(absent.fix).contains("fxloop:run")
        pom(dir, "<build><plugins><plugin><groupId>dev.fxloop</groupId><artifactId>fxloop-maven-plugin</artifactId><version>0.0.1</version></plugin></plugins></build>")
        assertThat(run(dir)["build-plugin"]!!.status).isEqualTo(DoctorStatus.warn)
    }

    @Test
    fun `MCP client configs are detected, snippets printed for the missing, broken jar paths fail`(@TempDir dir: Path) {
        pom(dir)
        val none = run(dir)["mcp-client"]!!
        assertThat(none.status).isEqualTo(DoctorStatus.warn)
        assertThat(none.fix).contains(".mcp.json")

        val jar = Files.writeString(dir.resolve("fxloop-server.jar"), "x")
        Files.createDirectories(dir.resolve(".cursor"))
        Files.writeString(dir.resolve(".cursor/mcp.json"), """{"mcpServers":{"other":{"command":"x"}}}""")
        val cursorOnly = run(dir, env(dir, jar = jar))["mcp-client"]!!
        assertThat(cursorOnly.summary).contains("Cursor").contains("without an fxloop entry")
        @Suppress("UNCHECKED_CAST") val snips = cursorOnly.data!!["snippets"] as List<Map<String, String>>
        assertThat(snips.map { it["client"] }).containsExactly("cursor")
        assertThat(snips[0]["content"]).contains("-jar").contains(jar.toString())

        Files.writeString(dir.resolve(".mcp.json"), """{"mcpServers":{"fxloop":{"command":"java","args":["-jar","$jar"]}}}""")
        val ok = run(dir, env(dir, jar = jar))["mcp-client"]!!
        assertThat(ok.status).isEqualTo(DoctorStatus.ok)
        assertThat(ok.summary).contains("Claude Code")

        Files.writeString(dir.resolve(".mcp.json"), """{"mcpServers":{"fxloop":{"command":"java","args":["-jar","${dir.resolve("gone.jar")}"]}}}""")
        val broken = run(dir, env(dir, jar = jar))["mcp-client"]!!
        assertThat(broken.status).isEqualTo(DoctorStatus.fail)
        assertThat(broken.summary).contains("gone.jar")

        Files.writeString(dir.resolve(".mcp.json"), """{"mcpServers":{"fxloop":{"command":"java","args":["-jar","$jar"]}}}""")
        val all = run(dir, env(dir, jar = jar), client = "all")["mcp-client"]!!
        @Suppress("UNCHECKED_CAST") val allSnips = all.data!!["snippets"] as List<Map<String, String>>
        assertThat(allSnips.map { it["client"] }).containsExactly("claude-code", "cursor", "vscode", "gemini", "codex")
        assertThat(allSnips.last()["content"]).contains("[mcp_servers.fxloop]")
        // the VS Code snippet is indented like the others, with "type" aligned with "command"
        assertThat(allSnips[2]["content"]).contains("\n    \"fxloop\": {\n      \"type\": \"stdio\",\n      \"command\": \"java\",\n")
    }

    @Test
    fun `claude mcp add registrations in the user file count, local scope for this directory first`(@TempDir dir: Path) {
        pom(dir)
        val jar = Files.writeString(dir.resolve("fxloop-server.jar"), "x")
        val home = Files.createDirectories(dir.resolve("home"))
        val ws = dir.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        Files.writeString(home.resolve(".claude.json"), """{"projects":{"$ws":{"mcpServers":{"fxloop":{"type":"stdio","command":"java","args":["-jar","$jar"]}}}}}""")
        val local = run(dir, env(dir, jar = jar))["mcp-client"]!!
        assertThat(local.status).isEqualTo(DoctorStatus.ok)
        assertThat(local.summary).contains("Claude Code (~/.claude.json, local scope)")
        assertThat(local.fix).isNull()

        Files.writeString(home.resolve(".claude.json"), """{"mcpServers":{"fxloop":{"type":"stdio","command":"java","args":["-jar","$jar"]}},"projects":{}}""")
        val user = run(dir, env(dir, jar = jar))["mcp-client"]!!
        assertThat(user.status).isEqualTo(DoctorStatus.ok)
        assertThat(user.summary).contains("user scope")

        // another directory's local entry does not count, and a stale jar path in the user file is reported
        Files.writeString(home.resolve(".claude.json"), """{"projects":{"/elsewhere":{"mcpServers":{"fxloop":{"command":"java","args":["-jar","$jar"]}}}}}""")
        assertThat(run(dir, env(dir, jar = jar))["mcp-client"]!!.status).isEqualTo(DoctorStatus.warn)
        Files.writeString(home.resolve(".claude.json"), """{"mcpServers":{"fxloop":{"command":"java","args":["-jar","${dir.resolve("gone.jar")}"]}}}""")
        val broken = run(dir, env(dir, jar = jar))["mcp-client"]!!
        assertThat(broken.status).isEqualTo(DoctorStatus.fail)
        assertThat(broken.summary).contains("gone.jar").contains(".claude.json")
    }

    @Test
    fun `codex toml entry is recognised`(@TempDir dir: Path) {
        pom(dir)
        Files.createDirectories(dir.resolve(".codex"))
        Files.writeString(dir.resolve(".codex/config.toml"), "[mcp_servers.fxloop]\ncommand = \"java\"\nargs = [\"-jar\", \"${dir.resolve("missing.jar")}\"]\n")
        val c = run(dir)["mcp-client"]!!
        assertThat(c.status).isEqualTo(DoctorStatus.fail)
        assertThat(c.summary).contains("Codex CLI")
    }

    @Test
    fun `json output is a stable array and doctor writes nothing`(@TempDir dir: Path) {
        pom(dir); twoApps(dir)
        val before = Files.walk(dir).use { s -> s.map { it.toString() }.sorted().toList() }
        val checks = Doctor.run(cfg(dir), env(dir))
        val json = ObjectMapper().readTree(Doctor.toJson(checks))
        assertThat(json.map { it["id"].asText() }).containsExactly("jdk", "build-tool", "javafx", "main-class", "build-plugin", "display", "mcp-client")
        assertThat(json.map { it["status"].asText() }).allMatch { it in setOf("ok", "warn", "fail") }
        assertThat(json[3]["data"]["candidates"]).hasSize(2)
        assertThat(json[0].has("fix")).isFalse()
        assertThat(Doctor.render(cfg(dir), checks, utf8 = true)).contains("❌ 4 Main class").contains("   → ").contains("main-class=demo.AppOne")
        assertThat(Doctor.render(cfg(dir), checks, utf8 = false)).contains("FAIL 4 Main class").contains("   -> ").doesNotContain("❌").doesNotContain("✅")
        assertThat(Doctor.summary(checks)).startsWith("doctor:")
        assertThat(Files.walk(dir).use { s -> s.map { it.toString() }.sorted().toList() }).isEqualTo(before)
    }
}
