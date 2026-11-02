package dev.fxloop.server.see

import dev.fxloop.protocol.Json
import dev.fxloop.server.loop.Reloader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Inspector stylesheet mappings: a sheet from outside the project → its local source (PRD 08 §5). */
class CssMappingsTest {
    @TempDir
    lateinit var root: Path

    private val app: Path get() = root.resolve("app").also { Files.createDirectories(it) }

    private fun file(rel: String, text: String = ".a { }\n"): Path =
        root.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    private val jarUrl = "jar:file:/home/dev/.m2/repository/com/acme/widgets/1.2/widgets-1.2.jar!/com/acme/widgets.css"

    @Test
    fun `mappings persist per project, ignore themselves in git and can be removed`() {
        val src = file("widgets/src/main/resources/com/acme/widgets.css").toRealPath()
        val m = CssMappings(app)
        val r = m.set("$jarUrl?fxloop=3", "../widgets/src/main/resources/com/acme/widgets.css")
        assertThat(r["url"].asText()).isEqualTo(jarUrl)
        assertThat(r["file"].asText()).isEqualTo(src.toString())
        assertThat(Files.readString(app.resolve(".fxloop/.gitignore"))).contains("*")

        val again = CssMappings(app)
        assertThat(again.list().map { it.url to it.file }).containsExactly(jarUrl to src)
        assertThat(again.urlsFor(src)).containsExactly(jarUrl)
        assertThat(again.isMapped(src)).isTrue()
        assertThat(Json.write(again.toJson())).isEqualTo("[{\"url\":\"$jarUrl\",\"file\":\"${src.toString().replace("\\", "\\\\")}\"}]")

        assertThat(again.set(jarUrl, "")["file"].isNull).isTrue()
        assertThat(CssMappings(app).list()).isEmpty()
    }

    @Test
    fun `only existing source stylesheets can be mapped, and only from jar, jrt or file URLs`() {
        file("app/target/classes/app.css")
        file("notes.txt", "hi")
        val m = CssMappings(app) { app.resolve("target/classes") }
        assertThatThrownBy { m.set(jarUrl, "../missing.css") }.hasMessageContaining("does not exist")
        assertThatThrownBy { m.set(jarUrl, "../notes.txt") }.hasMessageContaining("not a .css file")
        assertThatThrownBy { m.set(jarUrl, "target/classes/app.css") }.hasMessageContaining("build copy")
        assertThatThrownBy { m.set("data:text/css,.a{}", "../x.css") }.hasMessageContaining("jar:, jrt: or file:")
        assertThatThrownBy { m.set("", "../x.css") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.exists(app.resolve(".fxloop"))).isFalse()
        m.set(jarUrl, null) // removing what is not there writes nothing
        assertThat(Files.exists(app.resolve(".fxloop"))).isFalse()
    }

    @Test
    fun `suggestions come from the module behind a build copy, sibling projects and existing mappings`() {
        val moduleSrc = file("widgets/src/main/resources/com/acme/widgets.css")
        val buildCopy = file("widgets/target/classes/com/acme/widgets.css")
        val m = CssMappings(app)
        assertThat(m.suggest(buildCopy.toUri().toString())["suggestions"].map { it.asText() })
            .containsExactly(moduleSrc.toAbsolutePath().normalize().toString())

        val jarSuggest = m.suggest(jarUrl)
        assertThat(jarSuggest["resource"].asText()).isEqualTo("com/acme/widgets.css")
        assertThat(jarSuggest["suggestions"].map { it.asText() }).containsExactly(moduleSrc.toAbsolutePath().normalize().toString())

        // a second sheet of the same library, checked out where no search would look, follows the first mapping
        val far = file("elsewhere/lib/src/main/resources/com/acme/widgets.css")
        val dark = file("elsewhere/lib/src/main/resources/com/acme/dark.css")
        m.set(jarUrl, far.toString())
        assertThat(m.suggest(jarUrl.replace("widgets.css", "dark.css"))["suggestions"].map { it.asText() }.first())
            .isEqualTo(dark.toRealPath().toString())
        assertThat(m.suggest("jar:file:/x/other.jar!/nothing/here.css")["suggestions"].size()).isEqualTo(0)
    }

    @Test
    fun `a saved library source is copied over the build copy the app loads, not into a jar`() {
        val src = file("widgets/src/main/resources/com/acme/widgets.css", ".a { -fx-padding: 2; }\n")
        val copy = file("widgets/target/classes/com/acme/widgets.css", ".a { -fx-padding: 1; }\n")
        val rels = Reloader.copyMapped(src, listOf(copy.toUri().toString(), jarUrl))
        assertThat(Files.readString(copy)).isEqualTo(".a { -fx-padding: 2; }\n")
        assertThat(rels).containsExactly(copy.toUri().rawPath) // the jar copy is not reloaded: that would re-read the old one
        assertThat(Reloader.copyMapped(src, listOf(src.toUri().toString()))).containsExactly(src.toUri().rawPath) // loaded from source
    }
}
