package org.lab59.fxloop.server.tools

import org.lab59.fxloop.protocol.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InspectorHandoverTest {
    private fun advert(dir: Path, pid: Long, url: String): Path =
        Files.writeString(dir.resolve(Ctx.INSPECTOR_FILE), Json.write(Json.obj { put("pid", pid); put("url", url) }))

    @Test
    fun `the helper follows a live agent session's loopback inspector only`(@TempDir dir: Path) {
        val me = ProcessHandle.current().pid()
        val url = "http://127.0.0.1:32897/inspect?t=cb2ea9ba"
        assertThat(Ctx.advertisedInspector(dir.resolve(Ctx.INSPECTOR_FILE))).isNull()
        assertThat(Ctx.advertisedInspector(advert(dir, me, url))).isEqualTo(url)
        assertThat(Ctx.advertisedInspector(advert(dir, Long.MAX_VALUE, url))).isNull()
        assertThat(Ctx.advertisedInspector(advert(dir, me, "http://evil.example/inspect?t=x"))).isNull()
        assertThat(Ctx.advertisedInspector(advert(dir, me, "$url&x=\"><script>"))).isNull()
        Files.writeString(dir.resolve(Ctx.INSPECTOR_FILE), "not json")
        assertThat(Ctx.advertisedInspector(dir.resolve(Ctx.INSPECTOR_FILE))).isNull()
    }
}
