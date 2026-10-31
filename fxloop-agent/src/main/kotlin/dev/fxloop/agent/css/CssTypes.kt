package dev.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import java.util.Locale
import javafx.css.CssMetaData
import javafx.css.Styleable
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.util.Duration

/**
 * Value types of a node's styleable properties, read from each [CssMetaData]'s converter: the inspector's value
 * editors use them for suggestions, number steppers and the color picker (contract "Cascade JSON" `types`).
 */
internal object CssTypes {
    /** `{"-fx-alignment": {"kind":"enum","list":false,"choices":["center", …],"initial":"top-left"}, …}` for every view. */
    fun of(node: Node, views: List<PropertyView>): ObjectNode {
        val out = Json.obj()
        for (v in views) {
            if (out.has(v.cssProperty)) continue
            val o = out.putObject(v.cssProperty)
            val conv = v.meta.converter?.javaClass?.name ?: ""
            o.put("kind", kind(conv))
            if ("Sequence" in conv || "Layered" in conv) o.put("list", true)
            val initial = initial(v.meta, node)
            enumChoices(v, initial)?.let { o.set<ObjectNode>("choices", Json.strings(it)) }
            format(initial)?.let { o.put("initial", it) }
            if (v.inherits) o.put("inherits", true)
        }
        return out
    }

    /** Converter class → editor kind; unknown converters fall back to `other` (free text, no suggestions). */
    fun kind(conv: String): String {
        val c = conv.substringAfterLast('.')
        return when {
            "FontSizeConverter" in c -> "size"
            "FontWeightConverter" in c -> "font-weight"
            "FontStyleConverter" in c -> "font-style"
            c.startsWith("FontConverter") -> "font"
            "Paint" in c -> "paint"
            "Color" in c || c.startsWith("LadderConverter") -> "color"
            "Insets" in c || "CornerRadii" in c || "BorderImageWidth" in c || "Slice" in c -> "insets"
            "Size" in c && "Background" !in c -> "size"
            c.startsWith("BooleanConverter") -> "boolean"
            c.startsWith("EnumConverter") -> "enum"
            c.startsWith("DurationConverter") -> "duration"
            c.startsWith("EffectConverter") -> "effect"
            c.startsWith("CursorConverter") -> "cursor"
            c.startsWith("URLConverter") -> "url"
            "BorderStyle" in c || "BorderStrokeStyle" in c -> "border-style"
            c.startsWith("ShapeConverter") -> "shape"
            "BackgroundPosition" in c -> "background-position"
            "BackgroundSize" in c -> "background-size"
            "RepeatStruct" in c -> "background-repeat"
            c.startsWith("StringConverter") -> "string"
            else -> "other"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun initial(meta: CssMetaData<out Styleable, *>, node: Node): Any? =
        try { (meta as CssMetaData<Styleable, Any?>).getInitialValue(node) } catch (_: Exception) { null }

    /** Constants of an enum property, from its live or initial value, else from the converter's enum class. */
    private fun enumChoices(v: PropertyView, initial: Any?): List<String>? {
        val cls = (v.property.value as? Enum<*>)?.declaringJavaClass
            ?: (initial as? Enum<*>)?.declaringJavaClass
            ?: converterEnum(v.meta.converter)
            ?: return null
        return cls.enumConstants?.map { cssName(it as Enum<*>) }
    }

    private fun converterEnum(conv: Any?): Class<*>? {
        if (conv == null || !conv.javaClass.name.endsWith("EnumConverter")) return null
        return try {
            conv.javaClass.getDeclaredField("enumClass").apply { isAccessible = true }.get(conv) as? Class<*>
        } catch (_: Exception) { null } // javafx.graphics does not open javafx.css.converter on the module path
    }

    /** `Pos.CENTER_LEFT` → `center-left`, the spelling `EnumConverter` reads back. */
    fun cssName(e: Enum<*>): String = e.name.lowercase(Locale.ROOT).replace('_', '-')

    /** An initial value as CSS text, or null when it has no short CSS spelling (fonts, gradients, effects). */
    fun format(v: Any?): String? = when (v) {
        null -> null
        is Enum<*> -> cssName(v)
        is Boolean -> v.toString()
        is Number -> num(v.toDouble())
        is Color -> hex(v)
        is Insets -> listOf(v.top, v.right, v.bottom, v.left).let { s -> if (s.distinct().size == 1) num(s[0]) else s.joinToString(" ") { num(it) } }
        is Duration -> if (v.isIndefinite || v.isUnknown) null else num(v.toMillis()) + "ms"
        is String -> v.ifEmpty { null }
        else -> null
    }

    private fun num(d: Double): String = if (d == Math.rint(d) && !d.isInfinite()) d.toLong().toString() else "%.3f".format(Locale.ROOT, d).trimEnd('0').trimEnd('.')

    fun hex(c: Color): String {
        fun b(x: Double) = "%02x".format(Locale.ROOT, Math.round(x * 255).toInt())
        val rgb = "#" + b(c.red) + b(c.green) + b(c.blue)
        return when (c.opacity) {
            1.0 -> rgb
            0.0 -> if (c.red == 0.0 && c.green == 0.0 && c.blue == 0.0) "transparent" else rgb + b(0.0)
            else -> rgb + b(c.opacity)
        }
    }
}
