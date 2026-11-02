package dev.fxloop.server.cli

import java.util.jar.JarFile

/**
 * `--version` output and the JDK check (PRD 11 section 3 / 11 risk 1).
 *
 * The jar is compiled for Java 17, so on an older JVM the launcher fails with UnsupportedClassVersionError before
 * `main` runs; the "< 17" branch below can therefore only fire in theory and exists for completeness and tests.
 * A JRE (no `jdk.compiler`) is the realistic failure: the hot-swap tier cannot compile.
 */
object JdkCheck {
    const val MIN_FEATURE = 17

    /** Pure verdict: null when fine, otherwise a one-line fix. */
    fun problem(featureVersion: Int, hasCompiler: Boolean): String? = when {
        featureVersion < MIN_FEATURE ->
            "Java $featureVersion is too old: install a JDK $MIN_FEATURE or newer and put it first on PATH (or set JAVA_HOME)."
        !hasCompiler ->
            "this is a JRE, not a JDK (no javac): install a JDK $MIN_FEATURE or newer and put it first on PATH (or set JAVA_HOME)."
        else -> null
    }

    fun hasCompiler(): Boolean = ModuleLayer.boot().findModule("jdk.compiler").isPresent

    /** The git commit the jar was built from, from the manifest ("unknown" without .git at build time or outside a jar). */
    fun buildCommit(): String {
        val raw = runCatching {
            val f = java.io.File(JdkCheck::class.java.protectionDomain.codeSource.location.toURI())
            if (f.isFile) JarFile(f).use { it.manifest?.mainAttributes?.getValue("Implementation-Build") } else null
        }.getOrNull()
        return if (raw.isNullOrBlank() || raw.startsWith("\${")) "unknown" else raw
    }

    /** Lines to print and the exit code (0 fine, 1 failed check). Everything is injectable for tests. */
    fun report(version: String, commit: String, props: Map<String, String?>, featureVersion: Int, hasCompiler: Boolean): Pair<List<String>, Int> {
        val problem = problem(featureVersion, hasCompiler)
        val lines = listOf(
            "fxloop-server $version (commit $commit)",
            "java ${props["java.version"]} (${props["java.vendor"]}) at ${props["java.home"]}",
            if (problem == null) "OK: JDK $featureVersion with javac" else "FAIL: $problem",
        )
        return lines to (if (problem == null) 0 else 1)
    }

    fun run(version: String): Int {
        val props = listOf("java.version", "java.vendor", "java.home").associateWith { System.getProperty(it) }
        val (lines, code) = report(version, buildCommit(), props, Runtime.version().feature(), hasCompiler())
        lines.forEach(::println)
        return code
    }
}
