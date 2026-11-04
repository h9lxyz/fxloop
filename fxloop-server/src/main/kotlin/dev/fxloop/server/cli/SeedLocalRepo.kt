package dev.fxloop.server.cli

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
 * bundles into the local Maven repository (`dev/fxloop/...` only) so `mvn fxloop:run` and Gradle `mavenLocal()` resolve
 * the plugin and the agent before they are on Central. Remove this file, the `bundle-seed` execution in pom.xml and the
 * Main.kt hook in the first release after Central publishing works (PRD 10 A-R5).
 */
object SeedLocalRepo {
    /** One bundled artifact: resources `seed/<artifactId>.pom` and, when [jar], `seed/<artifactId>.jar`. */
    data class Coord(val artifactId: String, val jar: Boolean = true)

    /** Everything a user's build needs that is not on Central. */
    val COORDS = listOf(
        Coord("fxloop-parent", jar = false),
        Coord("fxloop-protocol"),
        Coord("fxloop-launch"),
        Coord("fxloop-agent"),
        Coord("fxloop-maven-plugin"),
    )

    const val GROUP = "dev.fxloop"
    private const val RES = "/dev/fxloop/server/seed/"

    fun defaultRepo(): Path = Path.of(System.getProperty("user.home"), ".m2", "repository")

    /** The agent jar is the one the server launches apps with, so the jar carries it once, outside `seed/`. */
    fun bundled(name: String): InputStream? =
        SeedLocalRepo::class.java.getResourceAsStream(if (name == "fxloop-agent.jar") "/dev/fxloop/server/$name" else RES + name)

    /** Writes [coords] from [source] below `<repo>/dev/fxloop`; returns the files written. */
    fun seed(repo: Path, coords: List<Coord> = COORDS, source: (String) -> InputStream? = ::bundled): List<Path> {
        val written = mutableListOf<Path>()
        val now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        for (c in coords) {
            fun read(ext: String): ByteArray =
                (source("${c.artifactId}.$ext") ?: error("bundled ${c.artifactId}.$ext is missing from this jar")).use { it.readBytes() }
            val pomBytes = read("pom")
            val version = pomVersion(pomBytes)
            val artifactDir = repo.resolve("dev/fxloop/${c.artifactId}")
            val dir = artifactDir.resolve(version)
            Files.createDirectories(dir)
            val remote = StringBuilder("#NOTE: This is a Maven Resolver internal implementation file, its format can be changed without prior notice.\n")
            fun put(ext: String, bytes: ByteArray) {
                val name = "${c.artifactId}-$version.$ext"
                val tmp = Files.createTempFile(dir, ".seed", ".tmp")
                Files.write(tmp, bytes)
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                remote.append("$name>=\n")
                written.add(dir.resolve(name))
            }
            put("pom", pomBytes)
            if (c.jar) put("jar", read("jar"))
            Files.writeString(dir.resolve("_remote.repositories"), remote.toString())
            if (version.endsWith("-SNAPSHOT")) {
                val sv = (if (c.jar) listOf("pom", "jar") else listOf("pom")).joinToString("") {
                    "      <snapshotVersion>\n        <extension>$it</extension>\n        <value>$version</value>\n        <updated>$now</updated>\n      </snapshotVersion>\n"
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
        val files = seed(repo)
        println("fxloop: seeded ${COORDS.size} artifacts into ${repo.resolve("dev/fxloop")} (temporary until Central publishing)")
        files.forEach { println("  $it") }
        return 0
    }
}
