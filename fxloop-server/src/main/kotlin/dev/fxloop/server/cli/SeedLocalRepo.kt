package dev.fxloop.server.cli

import dev.fxloop.server.app.Versions
import org.w3c.dom.Element
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TEMPORARY pre-Central bridge (PRD 11 section 4.4): `--seed-local-repo` writes the FXLoop Maven artifacts that this jar
 * bundles, and the jar itself, into the local Maven repository (`dev/fxloop/...` only) so `mvn fxloop:run`, Gradle
 * `mavenLocal()` and `gradle fxloopRun` resolve the build plugins, the agent and the server before they are on Central
 * and the Plugin Portal. Remove this file, the `bundle-seed` execution in pom.xml and the Main.kt hook in the first
 * release after Central publishing works (PRD 10 §7).
 */
object SeedLocalRepo {
    /**
     * One bundled artifact: resources `seed/<artifactId>.pom` and, when [jar], `seed/<artifactId>.jar` (stored under
     * [classifier] when set); [module] adds the Gradle module metadata `seed/<artifactId>.module`. An [optional]
     * artifact is skipped when it is not bundled: the Gradle plugin is built by Gradle and is only inside a server
     * built after `gradlew -p fxloop-gradle-plugin publishToMavenLocal` (release and edge jars are), and the server
     * itself is only there when this code runs from its jar.
     */
    data class Coord(
        val artifactId: String,
        val jar: Boolean = true,
        val module: Boolean = false,
        val optional: Boolean = false,
        val classifier: String? = null,
        /** Also store the classified jar under the main artifact name: Gradle's `mavenLocal()` ignores a module without a main jar. */
        val alsoMain: Boolean = false,
    )

    /** Everything a user's build needs that is not on Central or the Plugin Portal. */
    val COORDS = listOf(
        Coord("fxloop-parent", jar = false),
        Coord("fxloop-protocol"),
        Coord("fxloop-launch"),
        Coord("fxloop-agent"),
        Coord("fxloop-maven-plugin"),
        // this jar: `fxloop:run` and `fxloopRun` start it next to the app as the CSS inspector helper
        Coord("fxloop-server", classifier = "all", optional = true, alsoMain = true),
        // Gradle: `id("dev.fxloop")` resolves the marker POM, which depends on the plugin jar (with its module metadata)
        Coord("fxloop-gradle-plugin", module = true, optional = true),
        Coord("dev.fxloop.gradle.plugin", jar = false, optional = true),
    )

    const val GROUP = "dev.fxloop"
    private const val RES = "/dev/fxloop/server/seed/"

    fun defaultRepo(): Path = Path.of(System.getProperty("user.home"), ".m2", "repository")

    /** The jar this code runs from, or null (tests, IDE). */
    fun selfJar(): Path? = runCatching { Path.of(SeedLocalRepo::class.java.protectionDomain.codeSource.location.toURI()) }
        .getOrNull()?.takeIf { Files.isRegularFile(it) && it.toString().endsWith(".jar") }

    /**
     * A bundled resource. The agent jar is the one the server launches apps with, so the jar carries it once, outside
     * `seed/`; the server's own POM and jar come from the running jar.
     */
    fun bundled(name: String): InputStream? =
        SeedLocalRepo::class.java.getResourceAsStream(if (name == "fxloop-agent.jar") "/dev/fxloop/server/$name" else RES + name) ?: when (name) {
        "fxloop-server.pom" -> selfJar()?.let { serverPom(Versions.SERVER).byteInputStream() }
        "fxloop-server.jar" -> selfJar()?.let { Files.newInputStream(it) }
        else -> null
    }

    /** Minimal POM for the shaded server jar: the run goals resolve that one artifact, never its dependencies. */
    fun serverPom(version: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>$GROUP</groupId>
          <artifactId>fxloop-server</artifactId>
          <version>$version</version>
          <packaging>jar</packaging>
          <description>FXLoop MCP server, shaded; written into the local repository by `fxloop-server.jar --seed-local-repo`</description>
        </project>
    """.trimIndent() + "\n"

    /** The optional artifacts [seed] skips because [source] has no POM for them. */
    fun missing(coords: List<Coord> = COORDS, source: (String) -> InputStream? = ::bundled): List<Coord> =
        coords.filter { c -> c.optional && source("${c.artifactId}.pom")?.also { it.close() } == null }

    /** Writes [coords] from [source] below `<repo>/dev/fxloop`; returns the files written. */
    fun seed(repo: Path, coords: List<Coord> = COORDS, source: (String) -> InputStream? = ::bundled): List<Path> {
        val written = mutableListOf<Path>()
        val now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val skipped = missing(coords, source).toSet()
        for (c in coords) {
            if (c in skipped) continue
            fun read(ext: String): ByteArray =
                (source("${c.artifactId}.$ext") ?: error("bundled ${c.artifactId}.$ext is missing from this jar")).use { it.readBytes() }
            val pomBytes = read("pom")
            val version = pomVersion(pomBytes)
            val artifactDir = repo.resolve("dev/fxloop/${c.artifactId}")
            val dir = artifactDir.resolve(version)
            Files.createDirectories(dir)
            val remote = StringBuilder("#NOTE: This is a Maven Resolver internal implementation file, its format can be changed without prior notice.\n")
            val files = mutableListOf<Pair<String, String?>>() // extension to classifier
            fun put(ext: String, bytes: ByteArray, classifier: String? = null) {
                val name = "${c.artifactId}-$version" + (classifier?.let { "-$it" } ?: "") + ".$ext"
                val tmp = Files.createTempFile(dir, ".seed", ".tmp")
                Files.write(tmp, bytes)
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                remote.append("$name>=\n")
                files.add(ext to classifier)
                written.add(dir.resolve(name))
            }
            put("pom", pomBytes)
            if (c.jar) {
                val jar = read("jar")
                put("jar", jar, c.classifier)
                if (c.classifier != null && c.alsoMain) {
                    // a hard link where the file system allows it (the same bytes, no second copy), else a copy
                    val main = dir.resolve("${c.artifactId}-$version.jar")
                    Files.deleteIfExists(main)
                    runCatching { Files.createLink(main, dir.resolve("${c.artifactId}-$version-${c.classifier}.jar")) }
                        .onFailure { Files.write(main, jar) }
                    remote.append("${main.fileName}>=\n")
                    files.add("jar" to null)
                    written.add(main)
                }
            }
            if (c.module) put("module", read("module"))
            Files.writeString(dir.resolve("_remote.repositories"), remote.toString())
            if (version.endsWith("-SNAPSHOT")) {
                val sv = files.joinToString("") { (ext, classifier) ->
                    "      <snapshotVersion>\n" + (classifier?.let { "        <classifier>$it</classifier>\n" } ?: "") +
                        "        <extension>$ext</extension>\n        <value>$version</value>\n        <updated>$now</updated>\n      </snapshotVersion>\n"
                }
                Files.writeString(
                    dir.resolve("maven-metadata-local.xml"),
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata modelVersion=\"1.1.0\">\n  <groupId>$GROUP</groupId>\n" +
                        "  <artifactId>${c.artifactId}</artifactId>\n  <versioning>\n    <lastUpdated>$now</lastUpdated>\n    <snapshot>\n" +
                        "      <localCopy>true</localCopy>\n    </snapshot>\n    <snapshotVersions>\n$sv    </snapshotVersions>\n  </versioning>\n" +
                        "  <version>$version</version>\n</metadata>\n",
                )
                written.add(dir.resolve("maven-metadata-local.xml"))
            }
            val metaFile = artifactDir.resolve("maven-metadata-local.xml")
            val known = if (Files.exists(metaFile)) {
                Regex("<version>([^<]+)</version>").findAll(Files.readString(metaFile)).map { it.groupValues[1] }.toSet()
            } else emptySet()
            val versions = (known + version).sorted().joinToString("") { "      <version>$it</version>\n" }
            Files.writeString(
                metaFile,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n  <groupId>$GROUP</groupId>\n  <artifactId>${c.artifactId}</artifactId>\n" +
                    "  <versioning>\n    <versions>\n$versions    </versions>\n    <lastUpdated>$now</lastUpdated>\n  </versioning>\n</metadata>\n",
            )
            written.add(metaFile)
        }
        return written
    }

    /** `<version>` of the POM, falling back to its parent's. */
    fun pomVersion(pom: ByteArray): String {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.inputStream()).documentElement
        fun child(parent: Element, name: String): Element? =
            (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == name }
        return child(root, "version")?.textContent?.trim()
            ?: child(root, "parent")?.let { child(it, "version") }?.textContent?.trim()
            ?: error("POM has no version")
    }

    fun run(args: Array<String>): Int {
        val repo = args.firstOrNull { it.startsWith("--repo=") }?.removePrefix("--repo=")?.let { Path.of(it) } ?: defaultRepo()
        val skipped = missing()
        val files = seed(repo)
        println("fxloop: seeded ${COORDS.size - skipped.size} artifacts into ${repo.resolve("dev/fxloop")} (temporary until Central publishing)")
        files.forEach { println("  $it") }
        if (skipped.isNotEmpty()) {
            println("fxloop: not bundled in this server jar, so not seeded: ${skipped.joinToString { it.artifactId }}." +
                if (skipped.any { it.artifactId == "fxloop-gradle-plugin" }) " `id(\"dev.fxloop\")` cannot resolve from mavenLocal " +
                    "with it; use a release or edge jar, or build the server after `gradlew -p fxloop-gradle-plugin publishToMavenLocal`." else "")
        }
        return 0
    }
}
