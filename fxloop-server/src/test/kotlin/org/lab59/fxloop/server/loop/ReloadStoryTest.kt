package org.lab59.fxloop.server.loop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.server.app.AppSession.Mode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReloadStoryTest {
    private fun result(build: ObjectNode.() -> Unit): ObjectNode = Json.obj(build)

    private fun ObjectNode.tier(name: String, ms: Int, files: List<String> = emptyList(), details: ObjectNode.() -> Unit = {}) {
        withArray("tiersApplied").addObject().apply {
            put("tier", name)
            set<JsonNode>("files", Json.strings(files))
            put("ms", ms)
            set<JsonNode>("details", Json.obj(details))
        }
    }

    @Test
    fun cssOnly() {
        val s = ReloadStory.of(result { tier("css", 12, listOf("a.css")) }, Mode.LAUNCHED, false)
        assertThat(s.lastReload).isEqualTo("CSS applied · 12 ms")
        assertThat(s.ok).isTrue()
        assertThat(s.actionNeeded).isEmpty()
    }

    @Test
    fun noVisualChange() {
        val s = ReloadStory.of(result {
            tier("css", 5)
            set<JsonNode>("visualDiff", Json.obj { put("hint", "no visual change — the edited selector may not match") })
        }, Mode.LAUNCHED, false)
        assertThat(s.lastReload).isEqualTo("CSS applied · No visual change — selector may not match · 5 ms")
    }

    @Test
    fun hotswapAndRemount() {
        val s = ReloadStory.of(result {
            tier("hotswap", 30) { put("ok", true); put("classes", 2) }
            tier("remount", 10) { withArray("remounted").add("edit-form") }
        }, Mode.LAUNCHED, false)
        assertThat(s.lastReload).isEqualTo("Hot-swapped 2 classes · remounted edit-form · 40 ms")
    }

    @Test
    fun fxmlWithState() {
        val s = ReloadStory.of(result {
            tier("fxml", 7, listOf("views/main-view.fxml"))
            set<JsonNode>("stateCarried", Json.obj { put("restored", 7) })
        }, Mode.LAUNCHED, false)
        assertThat(s.lastReload).isEqualTo("FXML main-view.fxml swapped (7 states restored) · 7 ms")
    }

    @Test
    fun restartNeededLaunched() {
        val s = ReloadStory.of(result {
            tier("hotswap", 3) { put("ok", false) }
            put("needsRestart", true)
            withArray("restartReasons").add("hotswap rejected for app.EditForm: schema change: field added")
            put("restartDeferred", "humanActive")
        }, Mode.LAUNCHED, false)
        val a = s.actionNeeded.single()
        assertThat(a.kind).isEqualTo("restart")
        assertThat(a.reason).isEqualTo("Restart needed · fields added in EditForm")
        assertThat(a.button).isEqualTo("restart")
        assertThat(s.ok).isTrue()
    }

    @Test
    fun restartNeededEmbeddedAndAttached() {
        for (m in listOf(Mode.EMBEDDED, Mode.ATTACHED)) {
            val s = ReloadStory.of(result {
                put("needsRestart", true)
                withArray("restartReasons").add("pom.xml changed").add("x")
            }, m, false)
            val a = s.actionNeeded.single()
            assertThat(a.kind).isEqualTo("rebuildIde")
            assertThat(a.reason).isEqualTo("Rebuild and restart from your IDE · pom.xml changed (+1 more)")
            assertThat(a.button).isEqualTo("copy")
        }
    }

    @Test
    fun compileErrors() {
        val s = ReloadStory.of(result {
            withArray("compileErrors")
                .add(Json.obj { put("file", "src/Foo.java"); put("line", 42); put("message", "boom") })
                .add(Json.obj { put("message", "b") })
        }, Mode.LAUNCHED, false)
        assertThat(s.ok).isFalse()
        assertThat(s.lastReload).isEqualTo("Compile error · Foo.java:42 (+1 more)")
        assertThat(s.actionNeeded.single().kind).isEqualTo("compileError")
    }

    @Test
    fun waitingForBuild() {
        val s = ReloadStory.of(result { put("waitingForBuild", true) }, Mode.LAUNCHED, true)
        assertThat(s.actionNeeded.single().kind).isEqualTo("waitingBuild")
        assertThat(s.actionNeeded.single().reason).isEqualTo("Waiting for a build · run your IDE build or gradle -t classes")
    }

    @Test
    fun restartFailed() {
        val s = ReloadStory.of(result {
            put("needsRestart", true)
            withArray("restartReasons").add("pom.xml changed")
            set<JsonNode>("restartFailed", Json.obj { put("message", "cannot resolve x"); put("logPath", "/tmp/b.log") })
        }, Mode.LAUNCHED, false)
        assertThat(s.ok).isFalse()
        val a = s.actionNeeded.single()
        assertThat(a.kind).isEqualTo("rebuildFailed")
        assertThat(a.reason).isEqualTo("Rebuild failed · cannot resolve x")
        assertThat(a.next).isEqualTo("/tmp/b.log")
        assertThat(a.button).isEqualTo("copy")
    }

    @Test
    fun remountFailed() {
        val s = ReloadStory.of(result {
            tier("remount", 4) { withArray("failed").add(Json.obj { put("key", "edit-form"); put("error", "NPE\nat x") }) }
        }, Mode.LAUNCHED, false)
        assertThat(s.ok).isFalse()
        assertThat(s.actionNeeded.single().reason).isEqualTo("Remount failed · edit-form: NPE")
    }

    @Test
    fun nothingChanged() {
        val s = ReloadStory.of(result { put("note", "no changes detected since the last reload/launch") }, Mode.LAUNCHED, false)
        assertThat(s.lastReload).isEqualTo("no changes detected since the last reload/launch")
        assertThat(s.ok).isTrue()
    }
}
