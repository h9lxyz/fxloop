package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json
import java.lang.reflect.InvocationTargetException
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Parent
import javafx.stage.PopupWindow

/** Point probing (`fx_describe_at`), WebView JS bridge, test-only FX blocker. */
internal object Probes {
    fun describeAt(p: ObjectNode): ObjectNode {
        val x = p.path("x").asDouble()
        val y = p.path("y").asDouble()
        return Fx.call {
            val windows = Windows.showing().sortedWith { a, b -> (b is PopupWindow).compareTo(a is PopupWindow) }
            for (w in windows) {
                val root = w.scene.root
                val local = root.screenToLocal(x, y)
                if (local == null || !root.contains(local)) continue
                val hit = deepest(root, x, y)
                val logical = LogicalTree.owner(hit)!!
                val out = Json.obj()
                out.setNode("node", Targets.summary(logical))
                out.put("pickedType", NodePath.typeName(hit))
                NodeInfo.screenBounds(logical)?.let { out.setNode("boundsScreen", it) }
                val chain = out.putArray("ancestors")
                var cur = LogicalTree.parent(logical)
                while (cur != null) {
                    chain.add(NodePath.typeName(cur) + (if (cur.id != null) "#" + cur.id else ""))
                    cur = LogicalTree.parent(cur)
                }
                out.put("windowIndex", Windows.indexOf(w))
                return@call out
            }
            throw FcpException(Fcp.Error.NODE_NOT_FOUND, "no application window at screen point $x,$y")
        }
    }

    private fun deepest(n: Node, x: Double, y: Double): Node {
        if (n is Parent) {
            val kids = n.childrenUnmodifiable
            for (i in kids.indices.reversed()) {
                val k = kids[i]
                if (!k.isVisible || k.isMouseTransparent) continue
                val lp = k.screenToLocal(x, y)
                if (lp != null && k.contains(lp)) return deepest(k, x, y)
            }
        }
        return n
    }

    fun evalJs(p: ObjectNode): ObjectNode {
        if (!"true".equals(p.path("enabled").asText("false"), ignoreCase = true)) {
            throw FcpException(Fcp.Error.DISABLED, "fx_eval_js is disabled; start the server with FXLOOP_WEBVIEW=true")
        }
        val script = p.path("script").asText()
        val timeout = p.path("timeoutMs").asLong(3000)
        return Fx.call(timeout) {
            val node = Targets.resolve(p)
            if (node.javaClass.name != "javafx.scene.web.WebView") {
                throw FcpException(
                    Fcp.Error.UNSUPPORTED_TARGET,
                    "fx_eval_js targets WebView nodes only, got ${NodePath.typeName(node)}",
                )
            }
            try {
                val engine = node.javaClass.getMethod("getEngine").invoke(node)
                val exec = engine.javaClass.getMethod("executeScript", String::class.java)
                val json = exec.invoke(
                    engine,
                    "(function(){var __r=(function(){return (" + script +
                        ");})();try{return JSON.stringify(__r);}catch(e){return String(__r);}})()",
                )
                val out = Json.obj()
                val s = json?.toString()
                try {
                    out.setNode("result", if (s == null || "undefined" == s) Json.nullNode() else Json.MAPPER.readTree(s))
                } catch (notJson: Exception) {
                    out.put("result", s)
                }
                out
            } catch (e: InvocationTargetException) {
                throw FcpException(Fcp.Error.BAD_REQUEST, "JavaScript error: ${e.cause!!.message}")
            }
        }
    }

    fun blockFxThread(p: ObjectNode): ObjectNode {
        if (!"true".equals(System.getProperty("fxloop.test"), ignoreCase = true)) {
            throw FcpException(Fcp.Error.DISABLED, "blockFxThread is test-only (-Dfxloop.test=true)")
        }
        val ms = minOf(30_000L, p.path("ms").asLong(8000))
        Platform.runLater { Fx.sleep(ms) }
        return Json.obj().put("blocking", ms)
    }
}
