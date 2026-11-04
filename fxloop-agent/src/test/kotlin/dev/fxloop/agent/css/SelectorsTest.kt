package dev.fxloop.agent.css

import javafx.css.Selector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Selector parts read back from `Selector.toString()`, which must hold on JavaFX 21, 25 and 27. */
class SelectorsTest {
    @Test
    fun `simple selector parts`() {
        val p = Selectors.parts(Selector.createSelector("Button#ok.a.b:hover:focused")).single()
        assertThat(p.name).isEqualTo("Button")
        assertThat(p.id).isEqualTo("ok")
        // the order comes from JavaFX's class set: it depends on which classes this JVM has already seen
        assertThat(p.styleClasses).containsExactlyInAnyOrder("a", "b")
        assertThat(p.pseudoClasses.map { it.pseudoClassName }).containsExactlyInAnyOrder("hover", "focused")
    }

    @Test
    fun `compound selector parts keep order`() {
        val parts = Selectors.parts(Selector.createSelector("VBox > .x .y:pressed"))
        assertThat(parts.map { it.name }).containsExactly("VBox", "*", "*")
        assertThat(parts[1].styleClasses).containsExactly("x")
        assertThat(parts.last().pseudoClasses.map { it.pseudoClassName }).containsExactly("pressed")
        assertThat(parts.last().id).isNull()
    }

    @Test
    fun `specificity counts ids, classes and types`() {
        assertThat(Specificity.of(Selector.createSelector("*"))).isEqualTo("0,0,0")
        assertThat(Specificity.of(Selector.createSelector("#id"))).isEqualTo("1,0,0")
        assertThat(Specificity.of(Selector.createSelector("VBox > .x .y:pressed"))).isEqualTo("0,3,1")
        assertThat(Specificity.of(Selector.createSelector("Label.c#i"))).isEqualTo("1,1,1")
    }
}
