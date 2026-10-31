package dev.fxloop.agent.css

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class CssSourceTest {
    private fun css(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `line numbers survive multi-line comments`() {
        val sheet = CssSource.scan(
            css(
                "/* a",
                "   multi",
                "   line */",
                ".root {",
                "    /* c",
                "    */ -fx-background-color: red;",
                "    -fx-padding: 4px;",
                "}",
                "  /* x */ .b { -fx-x: 1 }",
            ),
        )
        assertThat(sheet.rules.map { it.selectorText to it.line }).containsExactly(".root" to 4, ".b" to 9)
        assertThat(sheet.rules[0].declarations.map { it.line }).containsExactly(6, 7)
        assertThat(sheet.rules[0].declarations[0].text).isEqualTo("-fx-background-color: red;")
    }

    @Test
    fun `semicolons braces and quotes inside strings do not break the scan`() {
        val sheet = CssSource.scan(
            """.a { -fx-font-family: "a;b{c}"; -fx-text: 'x}y'; -fx-padding: 1px; }
.b { -fx-x: 1 }""",
        )
        assertThat(sheet.rules.map { it.selectorText }).containsExactly(".a", ".b")
        val decls = sheet.rules[0].declarations
        assertThat(decls).hasSize(3)
        assertThat(decls[0].property).isEqualTo("-fx-font-family")
        assertThat(decls[0].text).isEqualTo("""-fx-font-family: "a;b{c}";""")
        assertThat(decls[1].text).isEqualTo("-fx-text: 'x}y';")
    }

    @Test
    fun `url with braces and semicolons is copied verbatim`() {
        val sheet = CssSource.scan(
            """.a { -fx-background-image: url(images/a{b}.png); -fx-x: url("x{y};.png"); -fx-z: 1 }""",
        )
        val decls = sheet.rules.single().declarations
        assertThat(decls.map { it.property }).containsExactly("-fx-background-image", "-fx-x", "-fx-z")
        assertThat(decls[0].text).isEqualTo("-fx-background-image: url(images/a{b}.png);")
    }

    @Test
    fun `important is detected case and whitespace insensitively`() {
        val decls = CssSource.scan(
            css(
                ".a {",
                "  -fx-a: red !important;",
                "  -fx-b: red!IMPORTANT ;",
                "  -fx-c: red ! important;",
                "  -fx-d: red;",
                "  -fx-e: important;",
                "}",
            ),
        ).rules.single().declarations
        assertThat(decls.map { it.important }).containsExactly(true, true, true, false, false)
        assertThat(decls[0].text).isEqualTo("-fx-a: red !important;")
    }

    @Test
    fun `selector lists are split on top-level commas and normalized`() {
        val rule = CssSource.scan(".a   >  .b ,  .c .d,#x:hover, .p:not(.q,.r)\n{ }").rules.single()
        assertThat(rule.selectorText).isEqualTo(".a > .b , .c .d,#x:hover, .p:not(.q,.r)")
        assertThat(rule.selectors).containsExactly(".a>.b", ".c .d", "#x:hover", ".p:not(.q,.r)")
    }

    @Test
    fun `normalizeSelector collapses whitespace and drops spaces around combinators`() {
        assertThat(CssSource.normalizeSelector(".a   >  .b")).isEqualTo(".a>.b")
        assertThat(CssSource.normalizeSelector("  a ,  b  ")).isEqualTo("a,b")
        assertThat(CssSource.normalizeSelector(".x .y")).isEqualTo(".x .y")
    }

    @Test
    fun `both import forms are recorded and produce no rule`() {
        val sheet = CssSource.scan(
            css(
                "@import \"a.css\";",
                "@import url(b/c.css);",
                "@import url(\"d.css\") screen;",
                ".x { }",
            ),
        )
        assertThat(sheet.imports).containsExactly("a.css", "b/c.css", "d.css")
        assertThat(sheet.rules.map { it.selectorText }).containsExactly(".x")
    }

    @Test
    fun `font-face blocks are counted and produce no rule`() {
        val sheet = CssSource.scan(
            css(
                "@font-face { src: url(\"f.ttf\"); font-family: X; }",
                "@font-face { src: url(g.ttf) }",
                ".a {}",
            ),
        )
        assertThat(sheet.fontFaceCount).isEqualTo(2)
        assertThat(sheet.rules.map { it.selectorText }).containsExactly(".a")
    }

    @Test
    fun `unknown at-rules are skipped whole`() {
        val sheet = CssSource.scan(
            css(
                "@media screen { .hidden { -fx-x: 1; } .also { } }",
                "@keyframes k { 0% { x: y } }",
                "@charset \"UTF-8\";",
                ".real { -fx-y: 2; }",
            ),
        )
        assertThat(sheet.rules.map { it.selectorText }).containsExactly(".real")
        assertThat(sheet.rules.single().declarations.single().text).isEqualTo("-fx-y: 2;")
        assertThat(sheet.imports).isEmpty()
        assertThat(sheet.fontFaceCount).isZero()
    }

    @Test
    fun `empty rules are kept with no declarations`() {
        val sheet = CssSource.scan(".a {}\n.b{ }")
        assertThat(sheet.rules.map { it.selectorText }).containsExactly(".a", ".b")
        assertThat(sheet.rules.all { it.declarations.isEmpty() }).isTrue()
        assertThat(sheet.rules[1].line).isEqualTo(2)
    }

    @Test
    fun `last declaration may lack a semicolon and multi-line values collapse`() {
        val decls = CssSource.scan(".a { -fx-a: 1; -fx-b:\n    2 }").rules.single().declarations
        assertThat(decls).hasSize(2)
        assertThat(decls[1].text).isEqualTo("-fx-b: 2")
        assertThat(decls[1].line).isEqualTo(1)
        assertThat(decls[1].important).isFalse()
    }

    @Test
    fun `declarations without colon or value are skipped`() {
        val decls = CssSource.scan(".a { garbage; -fx-x: ; -fx-ok: 1 }").rules.single().declarations
        assertThat(decls.map { it.property }).containsExactly("-fx-ok")
    }

    @Test
    fun `malformed and unterminated input never throws`() {
        val inputs = listOf(
            "/* never closed",
            ".a { -fx-x: 'open",
            ".a { -fx-x: url(",
            "@font-face {",
            "@import \"x",
            ".a {",
            "}}}}{{{",
            "\"",
            "@",
            ".a { -fx-x: 1 }\n.b { /* unterminated",
            "",
        )
        for (input in inputs) {
            assertThatCode { CssSource.scan(input) }.doesNotThrowAnyException()
        }
        val partial = CssSource.scan(".a { -fx-x: 1 }\n.b { /* unterminated")
        assertThat(partial.rules.map { it.selectorText }).containsExactly(".a", ".b")
        assertThat(partial.rules[1].declarations).isEmpty()
    }
}
