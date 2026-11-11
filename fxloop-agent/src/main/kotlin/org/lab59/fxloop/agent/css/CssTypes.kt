package org.lab59.fxloop.agent.css

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Json
import java.util.Locale
import javafx.css.CssMetaData
import javafx.css.Styleable
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.text.Font
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

    /**
     * The live value of [v] as CSS text (`22px`, `"System"`, `bold`, `#ff0000`), to show and to prefill an override;
     * null when it has no short spelling (gradients, effects, images, background parts other than the color).
     */
    fun live(node: Node, v: PropertyView): String? {
        val value = v.property.value ?: return null
        if (value is Font) {
            val italic = value.style.contains("italic", ignoreCase = true) || value.style.contains("oblique", ignoreCase = true)
            return when (v.cssProperty) {
                "-fx-font-size" -> num(value.size) + "px"
                "-fx-font-family" -> "\"${value.family}\""
                "-fx-font-weight" -> weight(value)
                "-fx-font-style" -> if (italic) "italic" else "normal"
                else -> listOfNotNull("italic".takeIf { italic }, weight(value).takeIf { it != "normal" }, num(value.size) + "px", "\"${value.family}\"")
                    .joinToString(" ")
            }
        }
        if (v.shorthand != null) {
            val paints = when {
                node is Region && v.cssProperty == "-fx-background-color" -> node.background?.fills?.map { it.fill }
                node is Region && v.cssProperty == "-fx-border-color" -> node.border?.strokes?.map { it.topStroke }
                else -> null
            }
            return paints?.takeIf { it.isNotEmpty() }?.map { format(it) ?: return null }?.joinToString(", ")
        }
        return format(value)
    }

    /** CSS `font-weight` of a font, read from its style name (`Bold Italic` → `bold`, `SemiBold` → `600`). */
    private fun weight(f: Font): String {
        val s = f.style.lowercase(Locale.ROOT).replace(" ", "").replace("-", "")
        return when {
            "extrabold" in s || "ultrabold" in s -> "800"
            "semibold" in s || "demibold" in s -> "600"
            "black" in s || "heavy" in s -> "900"
            "bold" in s -> "bold"
            "extralight" in s || "ultralight" in s -> "200"
            "thin" in s || "hairline" in s -> "100"
            "light" in s -> "300"
            "medium" in s -> "500"
            else -> "normal"
        }
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
