package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

/** HumanInput (PRD 07 §5): human journal, agent brackets, hotkeys; synthetic input via testInput, no sockets. */
@ExtendWith(ApplicationExtension::class)
class HumanInputFxTest {
    private val events = CopyOnWriteArrayList<Pair<String, ObjectNode>>()
    private val labelMouse = AtomicInteger()
    private val rootKeys = AtomicInteger()

    companion object {
        private lateinit var rt: AgentRuntime

        @JvmStatic
        @BeforeAll
        fun runtime() {
            rt = AgentRuntime.startForTest()
        }
    }

    @Start
    fun start(stage: Stage) {
        val btn = Button("Go").apply { id = "hbtn" }
        val tf = TextField().apply { id = "htf" }
        val pw = PasswordField().apply { id = "hpw" }
        val lbl = Label("L").apply { id = "hlbl"; addEventHandler(MouseEvent.MOUSE_CLICKED) { labelMouse.incrementAndGet() } }
        val empty = Pane().apply { styleClass.add("hempty"); setPrefSize(100.0, 60.0) }
        val root = VBox(btn, tf, pw, lbl, empty)
        root.addEventHandler(KeyEvent.KEY_PRESSED) { rootKeys.incrementAndGet() }
        stage.scene = Scene(root, 300.0, 300.0)
        stage.show()
        rt.human.install()
        rt.human.flush()
        events.clear()
        labelMouse.set(0)
        rootKeys.set(0)
        rt.human.listener = { name, p -> events.add(name to p) }
    }

    private fun input(build: ObjectNode.() -> Unit) {
        WaitForAsyncUtils.waitForFxEvents()
        rt.human.testInput(Json.obj(build))
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun actions() = events.filter { it.first == Fcp.Event.HUMAN_ACTION }.map { it.second }

    private fun skipped(): Int = Fx.call { rt.human.describe().get("skippedHuman").asInt() }

    @Test
    fun clickOnButtonEmitsClickNode() {
        input { put("kind", "click"); put("query", "#hbtn") }
        Fx.call { rt.human.flush() }
        val a = actions().single()
        assertThat(a.get("tool").asText()).isEqualTo("fx_click_node")
        assertThat(a.get("args").get("path").asText()).endsWith("Button#hbtn")
        assertThat(a.get("args").get("button").asText()).isEqualTo("primary")
        assertThat(a.get("args").get("clickCount").asInt()).isEqualTo(1)
        assertThat(a.get("target").get("fxId").asText()).isEqualTo("hbtn")
        assertThat(a.has("ts")).isTrue()
    }

    @Test
    fun doubleClickIsCoalesced() {
        input { put("kind", "click"); put("query", "#hbtn"); put("clickCount", 2) }
        Thread.sleep(600)
        WaitForAsyncUtils.waitForFxEvents()
        val a = actions().single()
        assertThat(a.get("args").get("clickCount").asInt()).isEqualTo(2)
    }

    @Test
    fun clickOnEmptyAreaIsSkipped() {
        val before = skipped()
        input { put("kind", "click"); put("query", ".hempty") }
        Thread.sleep(500)
        assertThat(actions()).isEmpty()
        assertThat(skipped()).isEqualTo(before + 1)
    }

    @Test
    fun textEditEmitsOneSetTextOnBlur() {
        input { put("kind", "text"); put("query", "#htf"); put("text", "hello") }
        val a = actions().single()
        assertThat(a.get("tool").asText()).isEqualTo("fx_set_text")
        assertThat(a.get("args").get("text").asText()).isEqualTo("hello")
        assertThat(a.get("args").get("path").asText()).endsWith("TextField#htf")
    }

    @Test
    fun passwordIsRedacted() {
        input { put("kind", "text"); put("query", "#hpw"); put("text", "s3cret") }
        val a = actions().single()
        assertThat(a.get("redacted").asBoolean()).isTrue()
        assertThat(a.get("args").has("text")).isFalse()
        assertThat(a.toString()).doesNotContain("s3cret")
    }

    @Test
    fun shortcutEmitsTypeKeys() {
        input {
            put("kind", "keys")
            putArray("keys").addObject().put("code", "S").putArray("modifiers").add("CTRL")
        }
        val a = actions().single()
        assertThat(a.get("tool").asText()).isEqualTo("fx_type_keys")
        val k = a.get("args").get("keys").get(0)
        assertThat(k.get("code").asText()).isEqualTo("S")
        assertThat(k.get("modifiers").map { it.asText() }).contains("CONTROL")
    }

    @Test
    fun restartHotkeyIsEmittedAndConsumed() {
        input {
            put("kind", "keys")
            putArray("keys").addObject().put("code", "F5").putArray("modifiers").add("CTRL").add("SHIFT")
        }
        val r = events.single { it.first == Fcp.Event.HUMAN_RESTART_REQUEST }.second
        assertThat(r.get("via").asText()).isEqualTo("hotkey")
        assertThat(rootKeys.get()).isZero()
        assertThat(actions()).isEmpty()
    }

    @Test
    fun invalidKeyConfigKeepsOldKey() {
        Fx.call { rt.human.configure(Json.obj().put("restartKey", "Ctrl+Nope+").put("recordHuman", true)) }
        input {
            put("kind", "keys")
            putArray("keys").addObject().put("code", "F5").putArray("modifiers").add("CTRL").add("SHIFT")
        }
        assertThat(events.count { it.first == Fcp.Event.HUMAN_RESTART_REQUEST }).isEqualTo(1)
    }

    @Test
    fun eventModeClickIsNotHumanAndHasNoConflicts() {
        val res = Interaction.click(Json.obj().put("query", "#hlbl").put("mode", "event"))
        WaitForAsyncUtils.waitForFxEvents()
        Thread.sleep(450)
        assertThat(labelMouse.get()).isEqualTo(1)
        assertThat(res.get("conflicts").asInt()).isZero()
        assertThat(res.get("mode").asText()).isEqualTo("event")
        assertThat(actions()).isEmpty()
    }

    @Test
    fun humanInputDuringBracketIsConflict() {
        WaitForAsyncUtils.waitForFxEvents()
        val b = rt.human.open(false)
        rt.human.testInput(Json.obj().put("kind", "click").put("query", "#hbtn"))
        val conflicts = rt.human.close(b)
        Thread.sleep(450)
        assertThat(conflicts).isEqualTo(1)
        assertThat(actions()).isEmpty()
    }

    @Test
    fun typeKeysEventModeReachesTextField() {
        val params = Json.obj().put("query", "#htf").put("mode", "event")
        params.putArray("keys").addObject().put("code", "A")
        val res = Interaction.typeKeys(params)
        WaitForAsyncUtils.waitForFxEvents()
        val text = Fx.call { (Targets.query("#htf", null).single() as TextField).text }
        assertThat(text).isEqualTo("a")
        assertThat(res.get("conflicts").asInt()).isZero()
        assertThat(res.get("mode").asText()).isEqualTo("event")
        assertThat(actions()).isEmpty()
    }

    @Test
    fun inputLockDropsDeveloperInputButNotAgentEvents() {
        Thread.sleep(150) // past the settle window of earlier brackets
        rt.human.setLock(10_000)
        try {
            input { put("kind", "click"); put("query", "#hlbl") }
            input { put("kind", "keys"); putArray("keys").addObject().put("code", "A") }
            Fx.call { rt.human.flush() }
            assertThat(labelMouse.get()).isZero()
            assertThat(rootKeys.get()).isZero()
            assertThat(actions()).isEmpty()
            val d = Fx.call { rt.human.describe() }
            assertThat(d.path("inputLocked").asBoolean()).isTrue()
            assertThat(d.path("blockedWhileLocked").asInt()).isGreaterThan(0)

            Interaction.click(Json.obj().put("query", "#hlbl").put("mode", "event"))
            WaitForAsyncUtils.waitForFxEvents()
            assertThat(labelMouse.get()).isEqualTo(1)
        } finally {
            rt.human.setLock(null)
        }
        Thread.sleep(150)
        input { put("kind", "click"); put("query", "#hlbl") }
        assertThat(labelMouse.get()).isEqualTo(2)
        assertThat(Fx.call { rt.human.describe() }.has("inputLocked")).isFalse()
    }

    @Test
    fun inputLockExpires() {
        rt.human.setLock(1)
        Thread.sleep(20)
        assertThat(rt.human.locked).isFalse()
    }
}
