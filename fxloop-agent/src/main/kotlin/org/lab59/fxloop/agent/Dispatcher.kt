package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.agent.css.CssInspect
import org.lab59.fxloop.protocol.Fcp
import org.lab59.fxloop.protocol.Fcp.Method
import org.lab59.fxloop.protocol.FcpException
import org.lab59.fxloop.protocol.Json

/** FCP method table. Each handler marshals to the FX thread itself, so waits/settles happen off it. */
internal class Dispatcher(private val rt: AgentRuntime) {
    private val handlers = HashMap<String, (ObjectNode) -> JsonNode>()

    init {
        handlers[Method.STATUS] = { status() }
        handlers[Method.GET_SCENE_GRAPH] = { sceneGraph(it) }
        handlers[Method.FIND_NODES] = { p -> Fx.call { Finder.find(p) } }
        handlers[Method.CLICK] = Interaction::click
        handlers[Method.TYPE_KEYS] = Interaction::typeKeys
        handlers[Method.SET_TEXT] = Interaction::setText
        handlers[Method.GET_PROPERTY] = Props::get
        handlers[Method.SET_PROPERTY] = Props::set
        handlers[Method.NODE_DETAILS] = Props::details
        handlers[Method.SCREENSHOT] = Snapshots::screenshot
        handlers[Method.CAPTURE_STATES] = Snapshots::captureStates
        handlers[Method.GET_UI_ERRORS] = { p ->
            Json.obj().setNode("errors", rt.errors.since(p.path("sinceSeq").asLong(0)))
        }
        handlers[Method.SNAPSHOT_STATE] = { p ->
            if (p.path("settleMs").asLong(0) > 0) {
                Fx.sleep(p.get("settleMs").asLong())
            }
            Fx.call { StateSnapshot.capture(p.path("maxNodes").asInt(2000)) }
        }
        handlers[Method.PATH_VISIBLE] = { p ->
            Fx.call {
                try {
                    val n = NodePath.resolve(p.path("path").asText())
                    Json.obj().put("visible", n.isVisible && n.scene != null && n.localToScreen(0.0, 0.0) != null)
                } catch (e: FcpException) {
                    Json.obj().put("visible", false).put("reason", e.message)
                }
            }
        }
        handlers[Method.DESCRIBE_AT] = Probes::describeAt
        handlers[Method.RELOAD_CSS] = Reloads::reloadCss
        handlers[Method.RELOAD_FXML] = Reloads::reloadFxml
        handlers[Method.REDEFINE_CLASSES] = Reloads::redefine
        handlers[Method.LINT_VISUAL] = VisualLint::lint
        handlers[Method.EXPLAIN_PROPERTY] = Introspect::explainProperty
        handlers[Method.EXPLAIN_STYLE] = Introspect::explainStyle
        handlers[Method.CSS_CASCADE] = CssInspect::cascade
        handlers[Method.CSS_SUGGEST] = CssInspect::suggest
        handlers[Method.CSS_SELECTOR] = CssInspect::selectorMatches
        handlers[Method.CSS_SCRATCH_SET] = org.lab59.fxloop.agent.css.Scratch::set
        handlers[Method.CSS_SCRATCH_CLEAR] = org.lab59.fxloop.agent.css.Scratch::clear
        handlers[Method.CSS_SCRATCH_PLAN] = org.lab59.fxloop.agent.css.ScratchApply::plan
        handlers[Method.EXPLAIN_LAYOUT] = Introspect::explainLayout
        handlers[Method.EVAL_JS] = Probes::evalJs
        handlers[Method.BLOCK_FX_THREAD] = Probes::blockFxThread
        handlers[Method.REMOUNT_VIEWS] = { p -> rt.mounts.remount(p) }
        handlers[Method.SET_BAR_STATE] = { p ->
            p["config"]?.let(rt.human::configure)
            rt.bar.post(p)
        }
        handlers[Method.GET_BAR_STATE] = { Fx.call { rt.bar.describe() } }
        handlers[Method.GET_PICKED] = { p -> rt.pick.get(p) }
        handlers[Method.SET_PICK_MODE] = { p -> rt.pick.set(p) }
        handlers[Method.TEST_HUMAN_INPUT] = { p ->
            if (!java.lang.Boolean.getBoolean("fxloop.test")) {
                throw FcpException(Fcp.Error.DISABLED, "testHumanInput is only available with -Dfxloop.test=true")
            }
            rt.human.testInput(p)
        }
        handlers[Method.HIGHLIGHT] = Overlay::highlight
        handlers[Method.FORCE_STATES] = ForcedStates::force
        handlers[Method.NODE_HANDLERS] = Handlers::describe
        handlers[Method.CSS_LINT] = org.lab59.fxloop.agent.css.CssLint::lint
        handlers[Method.CSS_SHEETS] = org.lab59.fxloop.agent.css.CssLint::sheets
        handlers[Method.FXML_EDIT_PLAN] = FxmlEdit::plan
        handlers[Method.PICK_START] = { p ->
            if (p.path("cancel").asBoolean(false)) rt.pick.set(Json.obj().put("on", false))
            else rt.pick.set(Json.obj().put("on", true).also { o -> p.intOrNull("windowIndex")?.let { o.put("windowIndex", it) } })
        }
    }

    fun dispatch(method: String, params: ObjectNode?): JsonNode {
        val h = handlers[method] ?: throw FcpException(Fcp.Error.UNKNOWN_METHOD, "unknown FCP method '$method'")
        return h(params ?: Json.obj())
    }

    private fun status(): ObjectNode = Fx.call {
        val o = Json.obj()
        o.put("mode", rt.mode)
        o.put("uptimeMs", System.currentTimeMillis() - rt.startedAt)
        o.setNode("windows", Windows.describeAll())
        o.setNode("fxml", rt.fxml.describe())
        o.put("handles", rt.handles.size())
        o.put("redefineSupported", rt.instrumentation != null && rt.instrumentation.isRedefineClassesSupported)
        o.setNode("mounts", rt.mounts.describe())
        o.setNode("human", rt.human.describe())
        o.put("pickActive", rt.pick.active)
        ForcedStates.describe()?.let { o.setNode("forcedStates", it) }
        if (org.lab59.fxloop.agent.css.Scratch.active) o.setNode("scratchCss", org.lab59.fxloop.agent.css.Scratch.describe())
        o
    }

    private fun sceneGraph(p: ObjectNode): ObjectNode {
        val maxDepth = maxOf(1, minOf(64, p.path("maxDepth").asInt(12)))
        val maxNodes = maxOf(1, minOf(5000, p.path("maxNodes").asInt(400)))
        return Fx.call {
            val root: javafx.scene.Node
            val window: javafx.stage.Window
            if (Targets.hasTarget(p) || p.hasNonNull("rootHandle")) {
                if (p.hasNonNull("rootHandle")) {
                    p.put("handle", p.get("rootHandle").asInt())
                }
                root = Targets.resolve(p)
                window = root.scene.window
            } else {
                window = Windows.byIndex(p.intOrNull("windowIndex"))
                root = window.scene.root
            }
            val s = SceneSerializer(maxDepth, maxNodes, skin = p.path("skin").asBoolean(false))
            val out = Json.obj()
            out.setNode("window", Windows.describe(window, Windows.indexOf(window)))
            out.setNode("root", s.serialize(root))
            out.put("truncated", s.truncated)
            out.put("nodeCount", s.emitted)
            out
        }
    }
}
