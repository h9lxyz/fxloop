package dev.fxloop.server.see

import dev.fxloop.protocol.FcpException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScratchPropsTest {
    private val log = ArrayList<String>()
    private val live = HashMap<String, String?>()
    private val props = ScratchProps { _, path, property, value ->
        val k = "$path.$property"
        if (property == "bound") throw FcpException("PROPERTY_BOUND", "bound")
        log += "$k=$value"
        live[k].also { live[k] = value }
    }

    private fun want(path: String, property: String, value: String?) = ScratchProps.Want(1, path, property, value)

    @Test
    fun `new edits are written and remember the value they replaced`() {
        live["A.prefWidth"] = "-1"
        val r = props.replace(listOf(want("A", "prefWidth", "100"), want("A", "HBox.hgrow", "ALWAYS")))
        assertThat(r.items.map { Triple(it.property, it.before, it.after) })
            .containsExactly(Triple("prefWidth", "-1", "100"), Triple("HBox.hgrow", null, "ALWAYS"))
        assertThat(r.errors).isEmpty()
    }

    @Test
    fun `a changed edit keeps its original before and an unchanged one is not rewritten`() {
        live["A.prefWidth"] = "-1"
        props.replace(listOf(want("A", "prefWidth", "100")))
        log.clear()
        val r = props.replace(listOf(want("A", "prefWidth", "100")))
        assertThat(log).isEmpty()
        assertThat(r.changed).isEmpty()
        props.replace(listOf(want("A", "prefWidth", "150")))
        assertThat(log).containsExactly("A.prefWidth=150")
        assertThat(props.list().single().before).isEqualTo("-1")
    }

    @Test
    fun `edits missing from the desired state are reverted to their before`() {
        live["A.prefWidth"] = "-1"
        props.replace(listOf(want("A", "prefWidth", "100"), want("B", "margin", "4")))
        log.clear()
        val plan = props.plan(listOf(want("B", "margin", "4")))
        assertThat(plan.set).isEmpty()
        assertThat(plan.revert.map { it.property }).containsExactly("prefWidth")
        assertThat(log).isEmpty() // plan is pure
        props.replace(listOf(want("B", "margin", "4")))
        assertThat(log).containsExactly("A.prefWidth=-1")
        assertThat(props.list().map { it.path }).containsExactly("B")
        props.replace(emptyList())
        assertThat(props.isEmpty()).isTrue()
    }

    @Test
    fun `a failing write is reported and the rest still applies`() {
        val r = props.replace(listOf(want("A", "bound", "1"), want("A", "ok", "2")))
        assertThat(r.errors.single().message).contains("bound")
        assertThat(r.items.map { it.property }).containsExactly("ok")
    }

    @Test
    fun `clear reverts everything and reset forgets without writing`() {
        live["A.p"] = "x"
        props.replace(listOf(want("A", "p", "y"), want("A", "q", "z")))
        log.clear()
        props.clear()
        assertThat(log).containsExactlyInAnyOrder("A.p=x", "A.q=null")
        assertThat(props.isEmpty()).isTrue()
        props.replace(listOf(want("A", "p", "y")))
        log.clear()
        props.reset()
        assertThat(log).isEmpty()
        assertThat(props.isEmpty()).isTrue()
    }

    @Test
    fun `items are keyed by path, else handle, and drop removes applied ones`() {
        props.replace(listOf(ScratchProps.Want(5, null, "p", "1"), want("A", "p", "1")))
        assertThat(props.list()).hasSize(2)
        props.drop(props.list().filter { it.path == "A" })
        assertThat(props.list().single().h).isEqualTo(5)
        assertThat(ScratchProps.toJson(props.list())[0]["before"].isNull).isTrue()
    }
}
