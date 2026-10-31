package dev.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils

@ExtendWith(ApplicationExtension::class)
class MountsFxTest {
    private lateinit var tabs: TabPane
    private lateinit var border: BorderPane

    @Start
    fun start(stage: Stage) {
        tabs = TabPane()
        border = BorderPane()
        border.center = tabs
        stage.scene = Scene(border, 300.0, 200.0)
        stage.show()
    }

    private fun params(vararg kv: Pair<String, Boolean>): ObjectNode = Json.obj().also { o -> kv.forEach { o.put(it.first, it.second) } }

    private fun onFx(work: () -> Unit) {
        Platform.runLater(work)
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun keys(arr: JsonNode) = arr.map { it.asText() }

    @Test
    fun remountsTabAndBorderPaneMounts() {
        val registry = MountRegistry()
        val gen = AtomicInteger()
        val tabSupplier = Supplier<Node> { Label("tab v${gen.incrementAndGet()}") }
        val centerSupplier = Supplier<Node> { Label("center v${gen.incrementAndGet()}") }
        lateinit var tabNode: Node
        lateinit var centerNode: Node
        onFx {
            tabNode = registry.register("dialogs", tabSupplier, tabSupplier.get())
            centerNode = registry.register("center", centerSupplier, centerSupplier.get())
            tabs.tabs.add(Tab("Dialogs", tabNode))
            val inner = BorderPane()
            inner.center = centerNode
            border.bottom = inner
        }
        val res = registry.remount(params())
        assertThat(res.path("mounts").asInt()).isEqualTo(2)
        assertThat(keys(res.path("remounted"))).containsExactlyInAnyOrder("dialogs", "center")
        assertThat(res.path("failed")).isEmpty()
        assertThat(tabs.tabs[0].content).isNotSameAs(tabNode)
        assertThat((tabs.tabs[0].content as Label).text).startsWith("tab v")
        assertThat((border.bottom as BorderPane).center).isNotSameAs(centerNode)
        assertThat(res.path("callbacks").path("ran").asInt()).isEqualTo(0)
        assertThat(registry.describe().map { it.path("inScene").asBoolean() }).containsOnly(true)
        val again = registry.remount(params())
        assertThat(keys(again.path("remounted"))).containsExactlyInAnyOrder("dialogs", "center")
    }

    @Test
    fun skipsMountsNotInAScene() {
        val registry = MountRegistry()
        val orphan = Label("orphan")
        registry.register("orphan", Supplier { Label("new") }, orphan)
        val res = registry.remount(params())
        assertThat(res.path("remounted")).isEmpty()
        assertThat(res.path("skipped")[0].path("key").asText()).isEqualTo("orphan")
        assertThat(res.path("skipped")[0].path("reason").asText()).isEqualTo("not in a scene")
    }

    @Test
    fun failingFactoryKeepsOldNodeAndReportsRootCause() {
        val registry = MountRegistry()
        val fail = AtomicBoolean()
        val supplier = Supplier<Node> {
            if (fail.get()) throw RuntimeException(java.lang.reflect.InvocationTargetException(NullPointerException("boom")))
            Label("ok")
        }
        lateinit var old: Node
        onFx {
            old = registry.register("form", supplier, supplier.get())
            border.top = old
        }
        fail.set(true)
        val res = registry.remount(params())
        assertThat(res.path("remounted")).isEmpty()
        assertThat(res.path("failed")[0].path("key").asText()).isEqualTo("form")
        assertThat(res.path("failed")[0].path("error").asText()).startsWith("NullPointerException at ")
        assertThat(border.top).isSameAs(old)
    }

    @Test
    fun callbacksRunAndFailuresAreReported() {
        val registry = MountRegistry()
        val ran = AtomicInteger()
        registry.onReload { ran.incrementAndGet() }
        registry.onReload { throw IllegalStateException("cb broke") }
        val res = registry.remount(params())
        assertThat(ran.get()).isEqualTo(1)
        assertThat(res.path("callbacks").path("ran").asInt()).isEqualTo(2)
        assertThat(res.path("callbacks").path("failed")).hasSize(1)
        assertThat(res.path("callbacks").path("failed")[0].path("error").asText()).startsWith("IllegalStateException at")
    }

    @Test
    fun remountFalseOnlyRunsCallbacks() {
        val registry = MountRegistry()
        val ran = AtomicInteger()
        registry.onReload { ran.incrementAndGet() }
        val supplier = Supplier<Node> { Label("x") }
        lateinit var node: Node
        onFx {
            node = registry.register("k", supplier, supplier.get())
            border.left = node
        }
        val res = registry.remount(params("remount" to false))
        assertThat(res.path("remounted")).isEmpty()
        assertThat(border.left).isSameAs(node)
        assertThat(ran.get()).isEqualTo(1)
    }

    @Test
    fun reRegisteringAKeyReplacesIt() {
        val registry = MountRegistry()
        registry.register("k", Supplier { Label("a2") }, Label("a"))
        registry.register("k", Supplier { Label("b2") }, Label("b"))
        assertThat(registry.describe()).hasSize(1)
    }

    @Test
    fun restageWithoutCapturedApplicationReportsError() {
        val res = MountRegistry().remount(params("restage" to true))
        assertThat(res.has("restaged") || res.has("restageError")).isTrue()
    }
}
