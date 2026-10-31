package dev.fxloop.server.loop

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SceneDiffTest {
    private fun snap(vararg nodes: Pair<String, ObjectNode>, windows: List<Pair<String, String>> = listOf("Stage[0]" to "App")) = Json.obj {
        val w = putArray("windows")
        windows.forEach { (seg, title) -> w.addObject().put("segment", seg).put("title", title).put("type", "STAGE") }
        val n = putObject("nodes")
        nodes.forEach { (p, v) -> n.set<ObjectNode>(p, v) }
        put("overflow", false)
    }

    private fun node(type: String, text: String? = null, disabled: Boolean = false) = Json.obj {
        put("type", type)
        text?.let { put("text", it) }
        put("disabled", disabled)
        put("visible", true)
    }

    @Test
    fun `reports added subtree roots, removed nodes, changed watched props and windows`() {
        val before = snap(
            "Stage[0]/Scene/VBox" to node("VBox"),
            "Stage[0]/Scene/VBox/Label#status" to node("Label", "0 contacts"),
            "Stage[0]/Scene/VBox/Button#add" to node("Button", "Add", disabled = true),
            "Stage[0]/Scene/VBox/Label#gone" to node("Label", "bye"),
        )
        val after = snap(
            "Stage[0]/Scene/VBox" to node("VBox"),
            "Stage[0]/Scene/VBox/Label#status" to node("Label", "1 contacts"),
            "Stage[0]/Scene/VBox/Button#add" to node("Button", "Add", disabled = false),
            "Stage[0]/Scene/VBox/TableRow[0]" to node("TableRow", "Ada | Work"),
            "Stage[0]/Scene/VBox/TableRow[0]/TableCell[0]" to node("TableCell", "Ada"),
            windows = listOf("Stage[0]" to "App", "Popup[0]" to ""),
        )
        val fx = SceneDiff.diff(before, after)
        assertThat(fx["added"].map { it["path"].asText() }).containsExactly("Stage[0]/Scene/VBox/TableRow[0]")
        assertThat(fx["removed"].map { it.asText() }).containsExactly("Stage[0]/Scene/VBox/Label#gone")
        assertThat(fx["changed"].map { it["path"].asText().substringAfterLast('/') + "." + it["prop"].asText() })
            .containsExactlyInAnyOrder("Label#status.text", "Button#add.disabled")
        assertThat(fx["windowChanges"][0]["change"].asText()).isEqualTo("opened")
        assertThat(SceneDiff.summary(fx)).contains("Label#status.text").contains("+1 nodes")
    }

    @Test
    fun `caps at 50 entries with overflow`() {
        val before = snap()
        val after = snap(*(0 until 80).map { "Stage[0]/Scene/L[$it]" to node("Label", "x$it") }.toTypedArray())
        val fx = SceneDiff.diff(before, after)
        assertThat(fx["added"].size()).isEqualTo(SceneDiff.CAP)
        assertThat(fx["overflow"].asBoolean()).isTrue()
    }

    @Test
    fun `diff of 5000-node graphs stays within the 200 ms budget (G9)`() {
        val nodes = (0 until 5000).map { "Stage[0]/Scene/FlowPane/Label[$it]" to node("Label", "n$it") }
        val before = snap(*nodes.toTypedArray())
        val after = snap(*nodes.mapIndexed { i, (p, n) -> p to if (i % 100 == 0) node("Label", "changed$i") else n }.toTypedArray())
        SceneDiff.diff(before, after) // warm-up
        val t0 = System.nanoTime()
        val fx = SceneDiff.diff(before, after)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertThat(fx["changed"].size()).isEqualTo(50)
        assertThat(ms).isLessThan(200)
    }
}
