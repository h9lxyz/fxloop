package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Json
import java.util.TreeMap
import javafx.beans.value.ObservableValue
import javafx.scene.Node

/** The `onXxx` handler properties a node has set (PRD 09 §10). FX thread. */
internal object Handlers {
    private val ON = Regex("on[A-Z][A-Za-z0-9]*Property")
    private val LAMBDA = Regex("""^(.*?)\${'$'}\${'$'}Lambda.*""")

    /** FCP `nodeHandlers`. */
    fun describe(p: ObjectNode): ObjectNode = Fx.call {
        val node = Targets.resolve(p)
        val out = Json.obj()
        out.setNode("target", Targets.summary(node))
        val list = out.putArray("handlers")
        for ((name, handler) in handlersOf(node)) {
            list.addObject().put("property", name).put("handler", describeClass(handler.javaClass)).put("class", handler.javaClass.name)
        }
        out.put("note", "handlers added with addEventHandler/addEventFilter are not listed (not public API)")
        out
    }

    private fun handlersOf(node: Node): List<Pair<String, Any>> {
        val found = TreeMap<String, Any>()
        for (m in node.javaClass.methods) {
            if (m.parameterCount != 0 || !ON.matches(m.name) || !ObservableValue::class.java.isAssignableFrom(m.returnType)) continue
            val pm = Props.publicMethod(node.javaClass, m.name) ?: continue
            val v = try {
                (pm.invoke(node) as ObservableValue<*>?)?.value
            } catch (e: ReflectiveOperationException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            if (v != null) found[m.name.removeSuffix("Property")] = v
        }
        return found.map { it.key to it.value }
    }

    /** "lambda in Foo" for synthetic lambda classes, else the class name. */
    internal fun describeClass(type: Class<*>): String {
        val m = LAMBDA.matchEntire(type.name)
        return if (m != null) "lambda in ${m.groupValues[1]}" else type.name
    }
}
