package dev.fxloop.agent.css

/**
 * A declaration inside a rule; [line] is where the property name starts (1-based). [valueStart]..[valueEnd] is the
 * value's character range in the source (after the colon, trailing whitespace and `;` excluded), -1 when unknown.
 */
internal data class ScannedDecl(
    val property: String,
    val text: String,
    val line: Int,
    val important: Boolean,
    val valueStart: Int = -1,
    val valueEnd: Int = -1,
)

/**
 * A style rule; [line] is where its selector starts. [selectors] are normalized, one per top-level comma part.
 * [closeBrace] is the offset of its closing `}` in the source, -1 when the block runs to EOF.
 */
internal data class ScannedRule(
    val selectorText: String,
    val selectors: List<String>,
    val line: Int,
    val declarations: List<ScannedDecl>,
    val closeBrace: Int = -1,
)

/** Result of [CssSource.scan]: style rules plus the bits the rule mapper needs to know about (imports, @font-face). */
internal data class ScannedSheet(val rules: List<ScannedRule>, val imports: List<String>, val fontFaceCount: Int)

/**
 * Line-aware scanner for JavaFX stylesheets. JavaFX's `CssParser` exposes rules in source order but no
 * positions, so this walks the raw text to recover them. Never throws: unterminated comments, strings,
 * blocks or `url(` simply end the scan.
 */
internal object CssSource {
    fun scan(text: String): ScannedSheet {
        val c = Cursor(text)
        val rules = ArrayList<ScannedRule>()
        val imports = ArrayList<String>()
        var fontFaces = 0
        val prelude = StringBuilder()
        while (true) {
            c.skipSpaceAndComments()
            if (c.eof) break
            val ch = c.peek()
            if (ch == '}' || ch == ';') {
                c.advance()
                continue
            }
            if (ch == '@') {
                c.advance()
                val name = readIdent(c).lowercase()
                prelude.setLength(0)
                when (collect(c, prelude, ";{}")) {
                    ';' -> {
                        c.advance()
                        if (name == "import") importTarget(prelude.toString())?.let { imports += it }
                    }
                    '{' -> {
                        if (name == "font-face") fontFaces++
                        skipBlock(c)
                    }
                    else -> {} // stray '}' or EOF: the loop head handles both
                }
                continue
            }
            val line = c.line
            prelude.setLength(0)
            when (collect(c, prelude, "{;}")) {
                '{' -> {
                    c.advance()
                    val decls = parseDeclarations(c)
                    val close = if (c.pos > 0 && text[c.pos - 1] == '}') c.pos - 1 else -1
                    val selector = collapseWs(prelude.toString()).trim()
                    if (selector.isNotEmpty()) {
                        rules += ScannedRule(selector, splitSelectors(selector), line, decls, close)
                    }
                }
                null -> break
                else -> c.advance() // malformed prelude terminated by ';' or '}'
            }
        }
        return ScannedSheet(rules, imports, fontFaces)
    }

    /** Collapse whitespace, drop spaces around '>' and ','; used to compare with Selector.toString(). */
    fun normalizeSelector(s: String): String {
        val src = collapseWs(s).trim()
        val out = StringBuilder(src.length)
        var quote: Char? = null
        for ((i, ch) in src.withIndex()) {
            if (quote != null) {
                out.append(ch)
                if (ch == quote) quote = null
                continue
            }
            when {
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    out.append(ch)
                }
                ch == ' ' && (isJoiner(out.lastOrNull()) || isJoiner(src.getOrNull(i + 1))) -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun isJoiner(ch: Char?) = ch == '>' || ch == ','

    /** Reads declarations after the opening '{' up to and including the matching '}' (or EOF). */
    private fun parseDeclarations(c: Cursor): List<ScannedDecl> {
        val out = ArrayList<ScannedDecl>()
        val sb = StringBuilder()
        while (true) {
            c.skipSpaceAndComments()
            if (c.eof) break
            when (c.peek()) {
                '}' -> {
                    c.advance()
                    break
                }
                ';' -> c.advance()
                else -> {
                    val line = c.line
                    val start = c.pos
                    sb.setLength(0)
                    val terminated = collect(c, sb, ";}") == ';'
                    val end = c.pos
                    if (terminated) c.advance()
                    declarationOf(line, sb.toString(), terminated)?.let { out += it.withRange(c.src, start, end) }
                }
            }
        }
        return out
    }

    private fun declarationOf(line: Int, raw: String, terminated: Boolean): ScannedDecl? {
        val body = collapseWs(raw).trim()
        val colon = body.indexOf(':')
        if (colon < 0) return null
        val property = body.substring(0, colon).trim().lowercase()
        val value = body.substring(colon + 1).trim()
        if (property.isEmpty() || value.isEmpty()) return null
        val text = if (terminated) "$body;" else body
        return ScannedDecl(property, text, line, isImportant(value))
    }

    /** Locates the value inside `src[start, end)`: after the first colon, surrounding whitespace trimmed. */
    private fun ScannedDecl.withRange(src: String, start: Int, end: Int): ScannedDecl {
        val colon = src.indexOf(':', start)
        if (colon < 0 || colon >= end) return this
        var a = colon + 1
        var b = end
        while (a < b && src[a].isWhitespace()) a++
        while (b > a && src[b - 1].isWhitespace()) b--
        return copy(valueStart = a, valueEnd = b)
    }

    private fun isImportant(value: String): Boolean {
        if (!value.endsWith("important", ignoreCase = true)) return false
        return value.dropLast("important".length).trimEnd().endsWith('!')
    }

    /** Target of `@import "x.css";` or `@import url(x.css) screen;`, or null if none is recognizable. */
    private fun importTarget(prelude: String): String? {
        val p = prelude.trim()
        val target = when {
            p.startsWith("url(", ignoreCase = true) -> p.substring(4).substringBefore(')')
            p.startsWith('"') || p.startsWith('\'') -> p.substring(1).substringBefore(p[0])
            else -> return null
        }
        return target.trim().trim('"', '\'').trim().takeIf { it.isNotEmpty() }
    }

    /** Splits a selector on commas that are outside quotes, parentheses and brackets. */
    private fun splitSelectors(selector: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (ch in selector) {
            when {
                quote != null -> {
                    cur.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    cur.append(ch)
                }
                ch == '(' || ch == '[' -> {
                    depth++
                    cur.append(ch)
                }
                ch == ')' || ch == ']' -> {
                    if (depth > 0) depth--
                    cur.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    parts += cur.toString()
                    cur.setLength(0)
                }
                else -> cur.append(ch)
            }
        }
        parts += cur.toString()
        return parts.map { normalizeSelector(it) }.filter { it.isNotEmpty() }
    }

    /**
     * Appends raw text to [out] until an unquoted, uncommented character in [stops] (not consumed) or EOF.
     * Comments become nothing, strings and `url(...)` are copied verbatim so their contents never act as stops.
     */
    private fun collect(c: Cursor, out: StringBuilder, stops: String): Char? {
        while (!c.eof) {
            val ch = c.peek()
            when {
                c.atComment() -> c.skipComment()
                ch == '"' || ch == '\'' -> readString(c, out)
                (ch == 'u' || ch == 'U') && c.matchesUrl() && !isIdentChar(out.lastOrNull()) -> readUrl(c, out)
                ch in stops -> return ch
                else -> {
                    out.append(ch)
                    c.advance()
                }
            }
        }
        return null
    }

    /** Copies a quoted string verbatim, honouring backslash escapes. An unescaped newline ends it. */
    private fun readString(c: Cursor, out: StringBuilder) {
        val quote = c.peek()
        out.append(quote)
        c.advance()
        while (!c.eof) {
            val ch = c.peek()
            if (ch == '\n') return
            out.append(ch)
            c.advance()
            if (ch == '\\') {
                if (!c.eof) {
                    out.append(c.peek())
                    c.advance()
                }
            } else if (ch == quote) {
                return
            }
        }
    }

    /** Copies `url(...)` verbatim up to the closing ')'; quoted parts may contain parentheses. */
    private fun readUrl(c: Cursor, out: StringBuilder) {
        repeat(4) {
            out.append(c.peek())
            c.advance()
        }
        while (!c.eof) {
            val ch = c.peek()
            when (ch) {
                '\n' -> return
                '"', '\'' -> readString(c, out)
                ')' -> {
                    out.append(ch)
                    c.advance()
                    return
                }
                else -> {
                    out.append(ch)
                    c.advance()
                }
            }
        }
    }

    /** Skips a balanced `{ ... }` block; the cursor must be on the opening brace. */
    private fun skipBlock(c: Cursor) {
        c.advance()
        var depth = 1
        val sink = StringBuilder()
        while (depth > 0) {
            sink.setLength(0)
            when (collect(c, sink, "{}")) {
                '{' -> {
                    depth++
                    c.advance()
                }
                '}' -> {
                    depth--
                    c.advance()
                }
                else -> return
            }
        }
    }

    private fun readIdent(c: Cursor): String {
        val sb = StringBuilder()
        while (!c.eof && (isIdentChar(c.peek()))) {
            sb.append(c.peek())
            c.advance()
        }
        return sb.toString()
    }

    private fun isIdentChar(ch: Char?) = ch != null && (ch.isLetterOrDigit() || ch == '-' || ch == '_')

    /** Collapses every whitespace run to a single space. */
    private fun collapseWs(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch.isWhitespace()) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Position and 1-based line tracking over the source text. */
    private class Cursor(val src: String) {
        var pos = 0
            private set
        var line = 1
            private set

        val eof: Boolean get() = pos >= src.length

        fun peek(): Char = src[pos]

        fun advance() {
            if (pos >= src.length) return
            if (src[pos] == '\n') line++
            pos++
        }

        fun atComment(): Boolean = src.startsWith("/*", pos)

        fun matchesUrl(): Boolean = src.regionMatches(pos, "url(", 0, 4, ignoreCase = true)

        fun skipComment() {
            advance()
            advance()
            while (!eof && !src.startsWith("*/", pos)) advance()
            if (!eof) {
                advance()
                advance()
            }
        }

        fun skipSpaceAndComments() {
            while (!eof) {
                when {
                    peek().isWhitespace() -> advance()
                    atComment() -> skipComment()
                    else -> return
                }
            }
        }
    }
}
