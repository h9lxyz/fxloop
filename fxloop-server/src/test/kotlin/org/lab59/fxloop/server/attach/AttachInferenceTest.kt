package org.lab59.fxloop.server.attach

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

class AttachInferenceTest {
    private fun dir(p: Path): Path = Files.createDirectories(p)

    private fun facts(cp: List<Any>, userDir: Path, extra: Map<String, String> = emptyMap(), pid: Long = 42): JvmFacts =
        JvmFacts(pid, mapOf("user.dir" to userDir.toString(), "java.class.path" to cp.joinToString(File.pathSeparator),
            "sun.java.command" to "org.example.ContactApp --seed", "java.specification.version" to "21") + extra)

    /** A minimal class file header for major version [major]. */
    private fun classFile(p: Path, major: Int): Path {
        Files.createDirectories(p.parent)
        return Files.write(p, byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, major.toByte()))
    }

    @Test
    fun `maven layout`(@TempDir root: Path) {
        val p = dir(root.resolve("contacts"))
        Files.writeString(p.resolve("pom.xml"), "<project/>")
        val classes = dir(p.resolve("target/classes"))
        val r = AttachInference.infer(facts(listOf(classes, root.resolve("m2/javafx-controls.jar")), p))
        assertThat(r.project!!.projectDir).isEqualTo(p)
        assertThat(r.project!!.moduleDir).isEqualTo(p)
        assertThat(r.project!!.outputDirs).containsExactly(classes)
        assertThat(r.project!!.buildTool).isEqualTo("maven")
    }

    @Test
    fun `gradle, IntelliJ and Eclipse layouts strip their output suffix`(@TempDir root: Path) {
        val p = dir(root.resolve("app"))
        Files.writeString(p.resolve("build.gradle.kts"), "plugins { java }")
        for (layout in listOf(listOf("build/classes/java/main", "build/resources/main"), listOf("build/classes/kotlin/main"),
            listOf("out/production/classes", "out/production/resources"), listOf("bin/main"))) {
            val dirs = layout.map { dir(p.resolve(it)) }
            val r = AttachInference.infer(facts(dirs, root)).project!!
            assertThat(r.moduleDir).describedAs(layout.toString()).isEqualTo(p)
            assertThat(r.projectDir).isEqualTo(p)
            assertThat(r.outputDirs).containsExactlyElementsOf(dirs)
            assertThat(r.buildTool).isEqualTo("gradle")
        }
    }

    @Test
    fun `IntelliJ's own module output finds the nearest build file`(@TempDir root: Path) {
        Files.writeString(root.resolve("pom.xml"), "<project/>")
        val out = dir(root.resolve("out/production/contacts"))
        assertThat(AttachInference.infer(facts(listOf(out), root)).project!!.projectDir).isEqualTo(root)
    }

    @Test
    fun `multi-module takes the app's own module, the other modules' dirs are not its output`(@TempDir root: Path) {
        Files.writeString(root.resolve("pom.xml"), "<project/>")
        val ui = dir(root.resolve("ui")).also { Files.writeString(it.resolve("pom.xml"), "<project/>") }
        val core = dir(root.resolve("core")).also { Files.writeString(it.resolve("pom.xml"), "<project/>") }
        val uiClasses = dir(ui.resolve("target/classes"))
        val coreClasses = dir(core.resolve("target/classes"))
        val r = AttachInference.infer(facts(listOf(uiClasses, coreClasses), root)).project!!
        assertThat(r.projectDir).isEqualTo(ui)
        assertThat(r.moduleDir).isEqualTo(ui)
        assertThat(r.outputDirs).containsExactly(uiClasses)
        // a module without a build file of its own belongs to the nearest one above
        val bare = dir(root.resolve("bare/target/classes"))
        assertThat(AttachInference.infer(facts(listOf(bare), root)).project!!.projectDir).isEqualTo(root)
    }

    @Test
    fun `a Gradle subproject without its own build file is found by suffix stripping, the root is the project`(@TempDir root: Path) {
        Files.writeString(root.resolve("build.gradle.kts"), "subprojects { apply(plugin = \"java\") }")
        val app = dir(root.resolve("app"))
        val classes = dir(app.resolve("build/classes/java/main"))
        val res = dir(app.resolve("build/resources/main"))
        val r = AttachInference.infer(facts(listOf(classes, res), root)).project!!
        assertThat(r.moduleDir).isEqualTo(app)
        assertThat(r.projectDir).isEqualTo(root)
        assertThat(r.outputDirs).containsExactly(classes, res)
    }

    @Test
    fun `a folder of jars on the module path is no runtime dir, and the descriptor gets its jars and the added modules`(@TempDir root: Path) {
        val sdk = dir(root.resolve("javafx-sdk/lib"))
        listOf("javafx.base.jar", "javafx.controls.jar").forEach { Files.writeString(sdk.resolve(it), "jar") }
        Files.writeString(sdk.resolve("libglass.so"), "so")
        val p = dir(root.resolve("app")).also { Files.writeString(it.resolve("pom.xml"), "<project/>") }
        val classes = dir(p.resolve("target/classes"))
        val f = JvmFacts(5, mapOf("user.dir" to p.toString(), "java.class.path" to classes.toString(), "jdk.module.path" to sdk.toString(),
            "sun.java.command" to "org.example.App"),
            mapOf("sun.jvm.args" to "-Djdk.module.addmods.0=javafx.controls,javafx.fxml -Dfile.encoding=UTF-8"))
        assertThat(AttachInference.runtimeDirs(f)).containsExactly(classes)
        val d = AttachInference.descriptor(f, AttachInference.infer(f).project!!)
        assertThat(d.modulePath).containsExactly(sdk.resolve("javafx.base.jar").toString(), sdk.resolve("javafx.controls.jar").toString())
        assertThat(d.addModules).containsExactly("javafx.controls", "javafx.fxml")
        // the SDK folder first on the module path, the app's classes after it: the project is still found
        val first = f.copy(props = f.props + ("java.class.path" to "") + ("jdk.module.path" to listOf(sdk, classes).joinToString(File.pathSeparator)))
        assertThat(AttachInference.infer(first).project!!.projectDir).isEqualTo(p)
        assertThat(JvmFacts(1, emptyMap(), mapOf("sun.jvm.args" to "--add-modules javafx.controls --add-modules=javafx.web")).addModules)
            .containsExactly("javafx.controls", "javafx.web")
    }

    @Test
    fun `the project root itself on the class path is no output dir`(@TempDir root: Path) {
        Files.writeString(root.resolve("pom.xml"), "<project/>")
        val classes = dir(root.resolve("target/classes"))
        val f = facts(listOf(".", "target/classes"), root)
        assertThat(AttachInference.runtimeDirs(f)).containsExactly(classes)
        assertThat(AttachInference.infer(facts(listOf("."), root)).project).isNull()
    }

    @Test
    fun `a source root holding stylesheets or FXML is a resource root too`(@TempDir root: Path) {
        Files.writeString(root.resolve("pom.xml"), "<project/>")
        val java = dir(root.resolve("src/main/java/org/example"))
        Files.writeString(java.resolve("app.css"), ".root {}")
        dir(root.resolve("src/main/kotlin"))
        val classes = dir(root.resolve("target/classes"))
        val f = facts(listOf(classes), root)
        val d = AttachInference.descriptor(f, AttachInference.infer(f).project!!)
        assertThat(d.resourceRoots).containsExactly(root.resolve("src/main/java").toString())
        assertThat(d.sourceRoots).containsExactly(root.resolve("src/main/java").toString(), root.resolve("src/main/kotlin").toString())
    }

    @Test
    fun `relative class path entries resolve against the app's user dir`(@TempDir root: Path) {
        Files.writeString(root.resolve("pom.xml"), "<project/>")
        val classes = dir(root.resolve("target/classes"))
        val f = facts(listOf("target/classes"), root)
        assertThat(f.classPath).containsExactly(classes)
        assertThat(AttachInference.infer(f).project!!.projectDir).isEqualTo(root)
    }

    @Test
    fun `jars only or no build file means no project`(@TempDir root: Path) {
        val jars = AttachInference.infer(facts(listOf(root.resolve("app.jar")), root))
        assertThat(jars.project).isNull()
        assertThat(jars.whyNone).contains("jars")
        val loose = dir(root.resolve("classes"))
        val none = AttachInference.infer(facts(listOf(loose), root))
        assertThat(none.project).isNull()
        assertThat(none.whyNone).contains("no build file")
    }

    @Test
    fun `an explicit project dir replaces the inference but must fit the app`(@TempDir root: Path) {
        Files.writeString(root.resolve("pom.xml"), "<project/>")
        val ui = dir(root.resolve("ui")).also { Files.writeString(it.resolve("pom.xml"), "<project/>") }
        val classes = dir(ui.resolve("target/classes"))
        val r = AttachInference.infer(facts(listOf(classes), root), root).project!!
        assertThat(r.projectDir).isEqualTo(root)
        assertThat(r.moduleDir).isEqualTo(ui)
        assertThat(r.outputDirs).containsExactly(classes)
        val other = dir(root.parent.resolve(root.fileName.toString() + "-other")).also { Files.writeString(it.resolve("pom.xml"), "<project/>") }
        try {
            assertThatThrownBy { AttachInference.infer(facts(listOf(classes), root), other) }.isInstanceOf(AttachException::class.java)
                .hasMessageContaining("does not run classes from")
            assertThatThrownBy { AttachInference.infer(facts(listOf(classes), root), dir(root.resolve("nobuild"))) }
                .hasMessageContaining("no build file")
        } finally {
            other.toFile().deleteRecursively()
        }
    }

    @Test
    fun `descriptor from JVM facts and conventions`(@TempDir root: Path) {
        val p = dir(root.resolve("app"))
        Files.writeString(p.resolve("build.gradle.kts"), "plugins { java }")
        dir(p.resolve("src/main/java"))
        dir(p.resolve("src/main/resources"))
        val res = dir(p.resolve("build/resources/main"))
        Files.writeString(res.resolve("app.css"), ".root {}")
        val classes = dir(p.resolve("build/classes/java/main"))
        classFile(classes.resolve("org/example/ContactApp.class"), 61)
        val jar = root.resolve("javafx-controls.jar")
        val f = facts(listOf(res, classes, jar), p)
        val d = AttachInference.descriptor(f, AttachInference.infer(f).project!!)
        assertThat(d.outputDir).isEqualTo(classes.toString()) // the classes dir, not the resources dir listed first
        assertThat(d.classpath).containsExactly(res.toString(), jar.toString()) // the real class path: saves reach build/resources
        assertThat(d.sourceRoots).containsExactly(p.resolve("src/main/java").toString())
        assertThat(d.resourceRoots).containsExactly(p.resolve("src/main/resources").toString())
        assertThat(d.release).isEqualTo("17")
        assertThat(d.mainClass).isEqualTo("org.example.ContactApp")
        assertThat(d.workingDir).isEqualTo(p.toString())
        assertThat(d.buildTool).isEqualTo("gradle")
        assertThat(d.annotationProcessors).isFalse()
    }

    @Test
    fun `release falls back to the JVM's, and a processor in the build file is a hint`(@TempDir root: Path) {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>Lombok</artifactId></project>")
        val classes = dir(root.resolve("target/classes"))
        val f = facts(listOf(classes), root, mapOf("jdk.module.main" to "contacts", "jdk.module.main.class" to "org.example.Main"))
        val d = AttachInference.descriptor(f, AttachInference.infer(f).project!!)
        assertThat(d.release).isEqualTo("21")
        assertThat(d.annotationProcessors).isTrue()
        assertThat(d.mainModule).isEqualTo("contacts")
        assertThat(d.mainClass).isEqualTo("org.example.Main")
    }

    @Test
    fun `main class and display name`() {
        fun f(cmd: String) = JvmFacts(1, mapOf("sun.java.command" to cmd))
        assertThat(f("org.example.ContactApp --x").displayName).isEqualTo("ContactApp")
        assertThat(f("contacts/org.example.ContactApp").mainClass).isEqualTo("org.example.ContactApp")
        assertThat(f("/opt/app/contacts.jar").displayName).isEqualTo("contacts.jar")
        assertThat(f("/home/u/app.jar --x").mainClass).isEqualTo("app.jar")
        assertThat(JvmScan.Listed(1, "/home/u/app.jar").mainClass).isEqualTo("app.jar")
        assertThat(JvmFacts(7, emptyMap()).displayName).isEqualTo("pid 7")
        assertThat(JvmFacts(1, mapOf("java.specification.version" to "1.8")).javaRelease).isEqualTo(8)
        assertThat(JvmFacts(1, mapOf("javafx.runtime.version" to "21")).javafx).isTrue()
        assertThat(JvmFacts(1, emptyMap()).javafx).isFalse()
        assertThat(JvmScan.Listed(1, "contacts/org.example.ContactApp --seed").mainClass).isEqualTo("org.example.ContactApp")
    }

    @Test
    fun `choosing among candidates`(@TempDir root: Path) {
        fun c(pid: Long, project: Path?) = AttachInference.Candidate(JvmFacts(pid, emptyMap()),
            project?.let { AttachProject(it, it, emptyList(), "maven") })
        val a = root.resolve("a")
        val b = root.resolve("b")
        assertThat(AttachInference.choose(listOf(c(1, null)), null)!!.facts.pid).isEqualTo(1)
        assertThat(AttachInference.choose(listOf(c(1, a), c(2, b)), b)!!.facts.pid).isEqualTo(2)
        assertThat(AttachInference.choose(listOf(c(1, a.resolve("ui")), c(2, b)), a)!!.facts.pid).isEqualTo(1) // a submodule
        assertThat(AttachInference.choose(listOf(c(1, a), c(2, a)), a)).isNull()
        assertThat(AttachInference.choose(listOf(c(1, a), c(2, b)), null)).isNull()
        assertThat(AttachInference.choose(listOf(c(1, a), c(2, b)), root.resolve("elsewhere"))).isNull()

        val out = ByteArrayOutputStream()
        val asked = AttachCommand(emptyList(), out = PrintStream(out), console = BufferedReader(StringReader("2\n")), workDir = root)
        assertThat(asked.pickFrom(listOf(c(1, a), c(2, b)), null).facts.pid).isEqualTo(2)
        assertThat(out.toString()).contains("1) 1").contains("2) 2")
        val noConsole = AttachCommand(emptyList(), out = PrintStream(ByteArrayOutputStream()), workDir = root)
        assertThatThrownBy { noConsole.pickFrom(listOf(c(1, a), c(2, b)), null) }
            .isInstanceOfSatisfying(AttachException::class.java) { assertThat(it.exitCode).isEqualTo(2) }
            .hasMessageContaining("attach <pid>")
    }

    @Test
    fun `the same app after a restart`(@TempDir root: Path) {
        fun c(pid: Long, main: String, project: Path?, userDir: Path = root) = AttachInference.Candidate(
            JvmFacts(pid, mapOf("sun.java.command" to main, "user.dir" to userDir.toString())),
            project?.let { AttachProject(it, it, emptyList(), "maven") })
        val p = root.resolve("p")
        assertThat(AttachInference.sameApp(c(1, "a.Main", p), c(2, "a.Main", p))).isTrue()
        assertThat(AttachInference.sameApp(c(1, "a.Main", p), c(2, "b.Main", p))).isFalse()
        assertThat(AttachInference.sameApp(c(1, "a.Main", p), c(2, "a.Main", root.resolve("q")))).isFalse()
        assertThat(AttachInference.sameApp(c(1, "a.Main", null), c(2, "a.Main", null))).isTrue()
        assertThat(AttachInference.sameApp(c(1, "a.Main", null), c(2, "a.Main", null, root.resolve("x")))).isFalse()
    }

    @Test
    fun `argument parsing`() {
        val a = AttachCommand.parse(listOf("--once", "9904", "--watch=all", "--project-dir=../app"))
        assertThat(a.pid).isEqualTo(9904)
        assertThat(a.once).isTrue()
        assertThat(a.list).isFalse()
        assertThat(a.config).containsExactly("--watch=all", "--project-dir=../app")
        assertThat(AttachCommand.parse(listOf("--list")).list).isTrue()
        assertThatThrownBy { AttachCommand.parse(listOf("--bogus")) }.hasMessageContaining("--bogus")
        assertThatThrownBy { AttachCommand.parse(listOf("1", "2")) }.hasMessageContaining("'2'")
    }

    @Test
    fun `the printed summary and the load failures`(@TempDir root: Path) {
        val f = JvmFacts(9904, mapOf("sun.java.command" to "org.example.ContactApp"))
        val s = AttachCommand.summary(f, AttachProject(root, root, emptyList(), "maven"), null, "http://127.0.0.1:1/inspect?t=x", "css-only")
        assertThat(s.lines().first()).isEqualTo("FXLoop attached to ContactApp (pid 9904).")
        assertThat(s).contains("Project    $root (Maven)").contains("Inspector  http://127.0.0.1:1/inspect?t=x")
            .contains("--watch=all").contains("restart the app yourself").doesNotContain("FXML and Java").endsWith("Ctrl+C stops FXLoop. Your app keeps running.\n")
        val io = AttachCommand.summary(f, null, "it runs from jars only", "u", "css-only")
        assertThat(io).contains("inspect-only").contains("it runs from jars only").contains("nothing is saved to files").doesNotContain("Limits")
        assertThat(AttachCommand.loadFailure(f, "Failed to load agent library: Dynamic agent loading is not enabled. Use …"))
            .contains("-XX:+EnableDynamicAgentLoading").contains("ContactApp")
        assertThat(AttachCommand.loadFailure(f, "boom")).contains("DisableAttachMechanism").contains("java.instrument")
    }

    @Test
    fun `the agent jar is extracted once per content into the user cache`(@TempDir root: Path) {
        val bytes = "jar".toByteArray()
        val first = AgentJarCache.path(null, root) { bytes }
        assertThat(first.fileName.toString()).matches("fxloop-agent-[0-9a-f]{12}\\.jar")
        val mtime = Files.getLastModifiedTime(first)
        assertThat(AgentJarCache.path(null, root) { bytes }).isEqualTo(first)
        assertThat(Files.getLastModifiedTime(first)).isEqualTo(mtime)
        assertThat(AgentJarCache.path(null, root) { "other".toByteArray() }).isNotEqualTo(first)
        assertThat(AgentJarCache.path(root.resolve("mine.jar"), root) { bytes }).isEqualTo(root.resolve("mine.jar"))
        assertThat(AgentJarCache.defaultDir("1.2.3", root)).isEqualTo(root.resolve(".fxloop/cache/1.2.3/agent"))
    }
}
