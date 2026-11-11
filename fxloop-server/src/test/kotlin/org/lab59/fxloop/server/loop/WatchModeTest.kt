package org.lab59.fxloop.server.loop

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lab59.fxloop.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class WatchModeTest {
    @Test
    fun `css-only applies stylesheets only`() {
        assertThat(WatchMode.applies("css-only", "app.CSS")).isTrue()
        assertThat(WatchMode.applies("css-only", "main-view.fxml")).isFalse()
        assertThat(WatchMode.applies("css-only", "MainController.kt")).isFalse()
    }

    @Test
    fun `all also applies FXML, Java and Kotlin but not scripts or other files`() {
        listOf("app.css", "main-view.fxml", "Main.java", "MainController.kt").forEach {
            assertThat(WatchMode.applies("all", it)).describedAs(it).isTrue()
        }
        assertThat(WatchMode.applies("all", "build.gradle.kts")).isFalse()
        assertThat(WatchMode.applies("all", "messages.properties")).isFalse()
    }

    @Test
    fun `off applies nothing`() {
        assertThat(WatchMode.applies("off", "app.css")).isFalse()
    }

    @Test
    fun `a single-file root applies saves of that file, not of its siblings`(@TempDir dir: Path) {
        val res = Files.createDirectories(dir.resolve("res"))
        val lib = Files.createDirectories(dir.resolve("lib"))
        val mapped = Files.writeString(lib.resolve("theme.css"), ".a {}")
        val sibling = Files.writeString(lib.resolve("other.css"), ".b {}")
        val inRoot = Files.writeString(res.resolve("app.css"), ".c {}")
        val applied = CopyOnWriteArrayList<String>()
        val watch = WatchMode { files -> applied.addAll(files); Json.obj() }
        try {
            watch.set("css-only", listOf(res, mapped))
            Thread.sleep(500) // let the watcher register
            Files.writeString(sibling, ".b { -fx-padding: 1; }")
            Files.writeString(mapped, ".a { -fx-padding: 1; }")
            Files.writeString(inRoot, ".c { -fx-padding: 1; }")
            val deadline = System.currentTimeMillis() + 10_000
            while (applied.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            Thread.sleep(400) // a late event for the sibling would land here
            assertThat(applied.map { Path.of(it).fileName.toString() }.toSet()).containsExactlyInAnyOrder("theme.css", "app.css")
        } finally {
            watch.dispose()
        }
    }
}
