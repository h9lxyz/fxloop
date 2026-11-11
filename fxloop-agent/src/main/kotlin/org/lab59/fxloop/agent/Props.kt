package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import org.lab59.fxloop.protocol.Paging
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.TreeMap
import javafx.beans.property.Property
import javafx.beans.property.ReadOnlyProperty
import javafx.beans.value.ObservableValue
import javafx.beans.value.WritableValue
import javafx.css.CssMetaData
import javafx.css.Styleable
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.TilePane
import javafx.scene.layout.VBox
import javafx.scene.paint.Paint
import kotlin.reflect.KClass

/** Reflective JavaFX property access with coercion (PRD 01 §7, PRD 02 §9). FX thread only. */
internal object Props {
    private val NAME = Regex("[a-zA-Z][a-zA-Z0-9]{0,63}")

    class Access(
        val owner: Any,
        val name: String,
        val observable: ObservableValue<*>?,
        val getter: Method?,
        val setter: Method?,
    ) {
        fun value(): Any? {
            try {
                if (observable != null) return observable.value
                return getter!!.invoke(owner)
            } catch (e: ReflectiveOperationException) {
                throw FcpException(Fcp.Error.INTERNAL, "cannot read $name: ${e.cause}")
            }
        }

        fun bound(): Boolean = observable is Property<*> && observable.isBound

        fun type(): Class<*> {
            if (setter != null) return setter.parameterTypes[0]
            if (getter != null) return getter.returnType
            val v = value()
            return if (v == null) Any::class.java else v.javaClass
        }
    }

    fun access(owner: Any, name: String): Access {
        if (!NAME.matches(name)) {
            throw FcpException(Fcp.Error.BAD_REQUEST, "invalid property name '$name'")
        }
        val cap = name[0].uppercaseChar() + name.substring(1)
        val propMethod = publicMethod(owner.javaClass, name + "Property")
        var getter = publicMethod(owner.javaClass, "get$cap")
        if (getter == null) getter = publicMethod(owner.javaClass, "is$cap")
        var setter: Method? = null
        if (getter != null) {
            setter = publicMethod(owner.javaClass, "set$cap", getter.returnType)
        }
        var obs: ObservableValue<*>? = null
        if (propMethod != null && ObservableValue::class.java.isAssignableFrom(propMethod.returnType)) {
            try {
                obs = propMethod.invoke(owner) as ObservableValue<*>?
            } catch (e: ReflectiveOperationException) {
                throw FcpException(Fcp.Error.INTERNAL, "cannot access ${name}Property(): ${e.cause}")
            }
        }
        if (obs == null && getter == null) {
            throw FcpException(
                Fcp.Error.PROPERTY_NOT_FOUND,
                "${NodePath.typeName0(owner)} has no property '$name'; list properties with fx_get_node_details",
            )
        }
        return Access(owner, name, obs, getter, setter)
    }

    /** Finds a public method callable from outside the (possibly non-exported) declaring class. */
    fun publicMethod(type: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = type
        while (c != null) {
            if (Modifier.isPublic(c.modifiers)) {
                try {
                    val m = c.getMethod(name, *params)
                    if (Modifier.isPublic(m.declaringClass.modifiers) && !Modifier.isStatic(m.modifiers)) return m
                } catch (ignored: NoSuchMethodException) {
                    // try superclass
                }
            }
            c = c.superclass
        }
        return null
    }

    /** `window.<prop>` addresses the target node's window (Stage), e.g. `window.width` to resize it (PRD 06 S5). */
    const val WINDOW_PREFIX = "window."

    /** The object that owns [name] for [node]: the node itself, or its window for `window.`-prefixed names. */
    private fun owner(node: Node, name: String): Pair<Any, String> {
        if (!name.startsWith(WINDOW_PREFIX)) return node to name
        val w = node.scene?.window ?: throw FcpException(Fcp.Error.UNSUPPORTED_TARGET, "${NodePath.of(node)} is not in a showing window")
        return w to name.removePrefix(WINDOW_PREFIX)
    }

    /**
     * A layout constraint key such as `HBox.hgrow`: the static `get/set` pair of the layout class. [kind] is `enum`,
     * `number` or `insets`.
     */
    class Constraint(val key: String, val owner: Class<*>, val prop: String) {
        private val cap = prop[0].uppercaseChar() + prop.substring(1)
        private val setter: Method = owner.methods.first { it.name == "set$cap" && Modifier.isStatic(it.modifiers) && it.parameterCount == 2 }
        private val getter: Method = owner.getMethod("get$cap", Node::class.java)
        val type: Class<*> = setter.parameterTypes[1]
        val kind: String = if (type.isEnum) "enum" else if (type == Insets::class.java) "insets" else "number"
        val choices: List<String> get() = if (type.isEnum) type.enumConstants.map { (it as Enum<*>).name } else emptyList()

        fun get(node: Node): Any? = getter.invoke(null, node)

        fun set(node: Node, v: Any?) {
            setter.invoke(null, node, v)
        }

        /** The value as the string `set` accepts back (insets as `t r b l`), null when unset. */
        fun text(node: Node): String? = when (val v = get(node)) {
            null -> null
            is Insets -> "${v.top} ${v.right} ${v.bottom} ${v.left}"
            is Enum<*> -> v.name
            else -> v.toString()
        }
    }

    private fun c(owner: Class<*>, vararg props: String) = props.map { Constraint("${owner.simpleName}.$it", owner, it) }

    /** Constraint keys by layout class simple name, in display order. */
    val CONSTRAINTS: Map<String, List<Constraint>> = linkedMapOf(
        "HBox" to c(HBox::class.java, "hgrow", "margin"),
        "VBox" to c(VBox::class.java, "vgrow", "margin"),
        "GridPane" to c(GridPane::class.java, "columnIndex", "rowIndex", "columnSpan", "rowSpan", "halignment", "valignment", "hgrow", "vgrow", "margin"),
        "AnchorPane" to c(AnchorPane::class.java, "topAnchor", "rightAnchor", "bottomAnchor", "leftAnchor"),
        "BorderPane" to c(BorderPane::class.java, "alignment", "margin"),
        "StackPane" to c(StackPane::class.java, "alignment", "margin"),
        "FlowPane" to c(FlowPane::class.java, "margin"),
        "TilePane" to c(TilePane::class.java, "alignment", "margin"),
    )

    private val BY_KEY: Map<String, Constraint> = CONSTRAINTS.values.flatten().associateBy { it.key }

    fun constraint(name: String): Constraint? = BY_KEY[name]

    private fun constraintGet(node: Node, k: Constraint): ObjectNode {
        val o = Json.obj()
        o.put("name", k.key)
        o.put("value", k.text(node))
        o.put("type", k.type.simpleName)
        o.put("bound", false)
        o.put("writable", true)
        o.put("path", NodePath.of(node))
        return o
    }

    private fun constraintSet(node: Node, k: Constraint, value: JsonNode?): ObjectNode {
        val out = Json.obj()
        out.put("valueBefore", k.text(node))
        val clear = value == null || value.isNull || value.asText().isBlank()
        try {
            k.set(node, if (clear) null else coerce(value, k.type))
        } catch (e: ReflectiveOperationException) {
            throw FcpException(Fcp.Error.COERCION_FAILED, "setting ${k.key} failed: ${e.cause}")
        }
        node.parent?.requestLayout()
        out.put("set", true)
        out.put("name", k.key)
        out.put("path", NodePath.of(node))
        out.put("valueAfter", k.text(node))
        return out
    }

    fun get(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        constraint(p.path("name").asText())?.let { return@call constraintGet(node, it) }
        val (owner, prop) = owner(node, p.path("name").asText())
        describe(access(owner, prop), node).put("name", p.path("name").asText())
    }

    fun describe(a: Access, node: Node?): ObjectNode {
        val o = Json.obj()
        o.put("name", a.name)
        NodeInfo.putValue(o, "value", a.value())
        o.put("type", a.type().simpleName)
        o.put("bound", a.bound())
        o.put("writable", a.setter != null || a.observable is WritableValue<*>)
        if (node != null) o.put("path", NodePath.of(node))
        return o
    }

    @Suppress("UNCHECKED_CAST")
    fun set(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        val name = p.path("name").asText()
        constraint(name)?.let { return@call constraintSet(node, it, p.get("value")) }
        val (owner, prop) = owner(node, name)
        val a = access(owner, prop)
        if (a.bound()) {
            val data = Json.obj().put("path", NodePath.of(node)).put("name", name)
            throw FcpException(
                Fcp.Error.PROPERTY_BOUND,
                "$name of ${NodePath.of(node)} is bound — inspect the binding (fx_explain_property) and write to its source", data,
            )
        }
        val value = coerce(p.get("value"), a.type())
        val out = Json.obj()
        NodeInfo.putValue(out, "valueBefore", a.value())
        try {
            if (a.setter != null) {
                a.setter.invoke(owner, value)
            } else if (a.observable is WritableValue<*>) {
                (a.observable as WritableValue<Any?>).value = value
            } else {
                throw FcpException(Fcp.Error.UNSUPPORTED_TARGET, "$name is read-only" + if (name == "width" || name == "height") " (to resize the window use window.$name)" else "")
            }
        } catch (e: ReflectiveOperationException) {
            throw FcpException(Fcp.Error.COERCION_FAILED, "setting $name failed: ${e.cause}")
        }
        out.put("set", true)
        out.put("name", name)
        out.put("path", NodePath.of(node))
        NodeInfo.putValue(out, "valueAfter", a.value())
        out
    }

    private fun isType(type: Class<*>, k: KClass<*>): Boolean = type == k.javaPrimitiveType || type == k.javaObjectType

    fun coerce(v: JsonNode?, type: Class<*>): Any? {
        if (v == null || v.isNull) {
            if (type.isPrimitive) {
                throw FcpException(Fcp.Error.COERCION_FAILED, "null is not valid for ${type.simpleName}")
            }
            return null
        }
        val s = v.asText()
        try {
            if (isType(type, Boolean::class)) {
                if (!s.equals("true", true) && !s.equals("false", true)) {
                    throw IllegalArgumentException("expected true/false")
                }
                return s.equals("true", true)
            }
            if (isType(type, Int::class)) return s.trim().toInt()
            if (isType(type, Long::class)) return s.trim().toLong()
            if (isType(type, Double::class)) return s.trim().toDouble()
            if (isType(type, Float::class)) return s.trim().toFloat()
            if (type == String::class.java || type == Any::class.java) return s
            if (type.isEnum) {
                val n = s.trim().uppercase(Locale.ROOT)
                return type.enumConstants.firstOrNull { (it as Enum<*>).name == n }
                    ?: throw IllegalArgumentException("No enum constant ${type.canonicalName}.$n")
            }
            if (type == Color::class.java || type == Paint::class.java) return Color.web(s.trim())
            if (type == Insets::class.java) {
                val parts = Regex("[ ,]+").split(s.trim()).dropLastWhile { it.isEmpty() }
                val d = DoubleArray(parts.size) { parts[it].toDouble() }
                return if (parts.size == 1) Insets(d[0]) else Insets(d[0], d[1], d[2], d[3])
            }
        } catch (e: RuntimeException) {
            throw FcpException(Fcp.Error.COERCION_FAILED, "cannot convert '$s' to ${type.simpleName}: ${e.message}")
        }
        throw FcpException(
            Fcp.Error.COERCION_FAILED,
            "values of type ${type.simpleName} cannot be set from JSON (supported: boolean, numbers, String, enums, Color/Paint, Insets)",
        )
    }

    /** `fx_get_node_details`: every readable property, one-line values, paginated. */
    @Suppress("UNCHECKED_CAST")
    fun details(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        val props = TreeMap<String, ObjectNode>()
        val styleable = HashSet<String>()
        for (meta in node.cssMetaData) {
            val sp = (meta as CssMetaData<Styleable, *>).getStyleableProperty(node)
            if (sp is ReadOnlyProperty<*>) styleable.add(sp.name)
        }
        for (m in node.javaClass.methods) {
            if (m.parameterCount == 0 && m.name.endsWith("Property") && !Modifier.isStatic(m.modifiers) &&
                ObservableValue::class.java.isAssignableFrom(m.returnType)
            ) {
                val name = m.name.substring(0, m.name.length - "Property".length)
                if (props.containsKey(name)) continue
                try {
                    val a = access(node, name)
                    val o = describe(a, null)
                    o.put("styleable", styleable.contains(name))
                    props[name] = o
                } catch (ignored: RuntimeException) {
                    // inaccessible property: skip
                }
            }
        }
        val items = ArrayList<JsonNode>(props.values)
        val page = Paging.page(items, p.path("offset").asInt(0), minOf(200, p.path("limit").asInt(60)))
        page.setNode("target", Targets.summary(node))
        page.put("class", node.javaClass.name)
        page
    }
}
