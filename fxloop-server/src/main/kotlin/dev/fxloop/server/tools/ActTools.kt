package dev.fxloop.server.tools

import com.fasterxml.jackson.databind.JsonNode
import dev.fxloop.protocol.Fcp
import dev.fxloop.protocol.Json
import dev.fxloop.server.export.TestExporter
import dev.fxloop.server.mcp.Schema
import dev.fxloop.server.mcp.ToolDef.Kind
import dev.fxloop.server.mcp.ToolException
import dev.fxloop.server.mcp.ToolRegistry
import org.objectweb.asm.ClassReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/** Hands (PRD 01 §7) + journal and TestFX export (PRD 02 §5, PRD 03 §5). Every action returns `effects`. */
object ActTools {
    fun register(reg: ToolRegistry, ctx: Ctx) {
        reg.tool("fx_click_node", "Click node", Kind.WRITE,
            "Click a node (handle/path/query) or screen point (x,y). mode=robot (default) moves the real mouse via " +
                "javafx Robot — picking, hover and focus behave like a user; enclosing tabs/scroll panes are brought into " +
                "view first. mode=event fires synthetic events (no real input). Returns effects (scene diff).",
            Schema.obj().target().number("x", "Screen x (instead of a target)").number("y", "Screen y")
                .strEnum("button", "Mouse button (default primary)", "primary", "secondary", "middle")
                .integer("clickCount", "1 or 2 (default 1)", 1, 3).strEnum("mode", "robot (default) or event", "robot", "event")
                .effects()) { a ->
            ctx.action("fx_click_node", Fcp.Method.CLICK, a) { r ->
                "clicked ${r.path("target").path("path").asText(r.path("x").asText() + "," + r.path("y").asText())} (${r.path("mode").asText()})"
            }
        }

        reg.tool("fx_type_keys", "Type keys", Kind.WRITE,
            "Press key codes via Robot (FX KeyCode names: ENTER, TAB, ESCAPE, DOWN, A, DIGIT1 …; modifiers SHIFT, CONTROL, " +
                "ALT, META, SHORTCUT). For free text use fx_set_text (layout-independent).",
            Schema.obj().array("keys", "Key presses in order", mapOf("type" to "object", "properties" to mapOf(
                "code" to mapOf("type" to "string"), "modifiers" to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
                "required" to listOf("code"))).target().effects().required("keys")) { a ->
            ctx.action("fx_type_keys", Fcp.Method.TYPE_KEYS, a) { r ->
                "typed ${r.path("typed").asInt()} key(s); focus now ${r.path("focusedAfter").path("path").asText("-")}"
            }
        }

        reg.tool("fx_set_text", "Set text", Kind.WRITE,
            "Programmatically set the text of a TextInputControl (TextField/TextArea/editable ComboBox). clearFirst=false appends.",
            Schema.obj().target().str("text", "Text to set").bool("clearFirst", "Replace (default true) or append").effects()
                .required("text")) { a ->
            ctx.action("fx_set_text", Fcp.Method.SET_TEXT, a) { r ->
                "${r.path("target").path("path").asText()} text = '${r.path("valueAfter").asText()}'"
            }
        }

        reg.tool("fx_set_property", "Set property", Kind.WRITE,
            "Write a JavaFX property (coerces strings to boolean/number/enum/Color/Insets). Bound properties fail with " +
                "PROPERTY_BOUND — write to the binding's source instead.",
            Schema.obj().target().str("name", "Property name; window.<name> targets the node's Stage, e.g. window.width / window.height to resize it").any("value", "New value").effects().required("name", "value")) { a ->
            ctx.action("fx_set_property", Fcp.Method.SET_PROPERTY, a) { r ->
                "${r.path("path").asText()}.${r.path("name").asText()}: ${r.path("valueBefore")} → ${r.path("valueAfter")}"
            }
        }

        reg.tool("fx_get_journal", "Get journal", Kind.READ,
            "The ordered log of successful actions (replayed after restarts and exported as tests).",
            Schema.obj().integer("limit", "Newest N entries (default 50)", 1, 500)) { a ->
            val entries = ctx.journal.entries().takeLast(a.path("limit").asInt(50))
            val arr = Json.array()
            entries.forEach { e ->
                arr.addObject().apply {
                    put("seq", e.seq)
                    put("tool", e.tool)
                    set<JsonNode>("args", e.args)
                    e.target?.let { set<JsonNode>("target", it) }
                    e.effects?.let { set<JsonNode>("effects", it) }
                    put("ts", e.ts)
                }
            }
            ok(entries.joinToString("\n") { "#${it.seq} ${it.tool} ${it.args}" }.ifEmpty { "journal is empty" },
                Json.obj { set<JsonNode>("entries", arr); put("count", entries.size) })
        }

        reg.tool("fx_export_test", "Export TestFX test", Kind.WRITE,
            "Generate a JUnit 5 + TestFX test from the journal (optionally a seq range) into src/test/java. Assertions are " +
                "synthesized from recorded effects; unclear spots get TODO(fxloop) markers. Generated draft — review it.",
            Schema.obj().str("name", "Test name, e.g. AddContact → AddContactGeneratedTest").str("package", "Java package (default: main class package)")
                .integer("fromSeq", "First journal seq").integer("toSeq", "Last journal seq")
                .bool("write", "Write files (default true); false returns the source only").required("name")) { a ->
            val d = ctx.session.descriptor ?: throw ToolException("NO_DESCRIPTOR", "the app has not been built yet", "call fx_start_app first")
            val name = a["name"].asText()
            if (!name.matches(Regex("[A-Z][A-Za-z0-9_]*"))) throw ToolException("BAD_REQUEST", "name must be a Java identifier", "e.g. AddContact")
            val pkg = a.path("package").asText(d.mainClass.substringBeforeLast('.', ""))
            if (!pkg.matches(Regex("([a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*)?"))) throw ToolException("BAD_REQUEST", "invalid package $pkg", "use a.b.c")
            val entries = ctx.journal.range(a.path("fromSeq").asLong(0), a.path("toSeq").asLong(Long.MAX_VALUE))
            if (entries.isEmpty()) throw ToolException("EMPTY_JOURNAL", "no journal entries in range", "perform actions first (fx_click_node …)")
            val appClass = applicationClass(Path.of(d.outputDir))
                ?: throw ToolException("NO_APPLICATION", "no javafx.application.Application subclass found in ${d.outputDir}", "compile the app")
            val export = TestExporter.export(name, pkg, appClass, entries)
            val dir = ctx.session.config.projectDir.resolve("src/test/java").resolve(pkg.replace('.', '/'))
            val out = Json.obj {
                put("className", export.className)
                put("steps", export.steps)
                put("todos", export.todos)
                put("appClass", appClass)
            }
            if (a.path("write").asBoolean(true)) {
                Files.createDirectories(dir)
                val test = dir.resolve("${export.className}.java")
                Files.writeString(test, export.testSource)
                val helper = dir.resolve("FxPath.java")
                if (!Files.exists(helper)) Files.writeString(helper, export.helperSource)
                out.put("file", ctx.visuals.relative(test))
                out.put("helperFile", ctx.visuals.relative(helper))
            } else {
                out.put("source", export.testSource)
            }
            ok("exported ${export.className}: ${export.steps} steps, ${export.todos} TODOs" + (out["file"]?.let { " → ${it.asText()}" } ?: ""), out)
        }
    }

    /** Finds the Application subclass in the compiled output (follows in-project superclass chains). */
    fun applicationClass(outputDir: Path): String? {
        if (!Files.isDirectory(outputDir)) return null
        val supers = HashMap<String, String?>()
        Files.walk(outputDir).use { s ->
            s.asSequence().filter { it.isRegularFile() && it.name.endsWith(".class") && '$' !in it.name }.forEach {
                val r = ClassReader(Files.readAllBytes(it))
                supers[r.className] = r.superName
            }
        }
        fun isApp(name: String?, depth: Int = 0): Boolean = when {
            name == null || depth > 10 -> false
            name == "javafx/application/Application" -> true
            else -> isApp(supers[name], depth + 1)
        }
        return supers.keys.filter { isApp(supers[it]) }.minByOrNull { it.length }?.replace('/', '.')
    }
}
