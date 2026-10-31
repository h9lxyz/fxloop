package dev.fxloop.server.loop

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
}
