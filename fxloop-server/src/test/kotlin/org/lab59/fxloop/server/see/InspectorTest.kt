package org.lab59.fxloop.server.see

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class InspectorTest {
    @Test
    fun buildsUrlWithEncodedPath() {
        assertThat(Inspector.buildUrl("http://127.0.0.1:1/inspect?t=x", 12, "Stage[0]/Scene/Button#a b"))
            .isEqualTo("http://127.0.0.1:1/inspect?t=x&h=12&path=Stage%5B0%5D%2FScene%2FButton%23a+b")
        assertThat(Inspector.buildUrl("http://h/inspect?t=x", null, null)).isEqualTo("http://h/inspect?t=x")
    }

    @Test
    fun browserNoneReturnsUrlWithoutLaunching(@TempDir dir: Path) {
        val launched = mutableListOf<String>()
        Viewer(0, "tk", ScreenStore(dir, 5)).use { v ->
            v.start()
            val r = Inspector({ v }, false) { launched += it; true }.open(3, null)
            assertThat(launched).isEmpty()
            assertThat(r["opened"].asBoolean()).isFalse()
            assertThat(r["url"].asText()).isEqualTo("http://127.0.0.1:${v.port}/inspect?t=tk&h=3")
            assertThat(r["reason"].asText()).contains("FXLOOP_BROWSER")
        }
    }

    @Test
    fun launcherResultIsReported(@TempDir dir: Path) {
        Viewer(0, "tk", ScreenStore(dir, 5)).use { v ->
            v.start()
            val ok = Inspector({ v }, true) { true }.open(null, "P")
            assertThat(ok["opened"].asBoolean()).isTrue()
            assertThat(ok["url"].asText()).endsWith("&path=P")
            assertThat(Inspector({ v }, true) { throw IllegalStateException("no") }.open(null, null)["opened"].asBoolean()).isFalse()
        }
    }
}
