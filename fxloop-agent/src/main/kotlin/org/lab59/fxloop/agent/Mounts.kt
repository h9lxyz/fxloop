package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Json
import java.lang.instrument.Instrumentation
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import javafx.scene.Node

/**
 * E1 (PRD 07 §3): view factories registered through `FxLoopDev.view(key, supplier)` and reload callbacks from
 * `FxLoopDev.onReload(runnable)`. After a successful hotswap the server calls [remount] (FCP `remountViews`), which
 * re-invokes each live factory on the FX thread and swaps the old node for the new one with [Reloads.swap].
 */
class MountRegistry {
    private class Mount(val key: String, val supplier: Supplier<out Node>, node: Node) {
        @Volatile
        var ref: WeakReference<Node> = WeakReference(node)
    }

    private val mounts = LinkedHashMap<String, Mount>()
    private val callbacks = CopyOnWriteArrayList<Runnable>()

    /** Records (or replaces) the mount [key] and returns [node] unchanged. Safe from any thread. */
    fun register(key: String, supplier: Supplier<out Node>, node: Node): Node {
        synchronized(mounts) {
            mounts.remove(key) // re-registering moves the key to the end, like a fresh mount
            mounts[key] = Mount(key, supplier, node)
        }
        return node
    }

    /** Adds a callback run on the FX thread after every successful hotswap or FXML tier. */
    fun onReload(callback: Runnable) {
        callbacks.add(callback)
    }

    /** Live mounts in registration order; mounts whose node was garbage collected are pruned. */
    private fun live(): List<Pair<Mount, Node>> = synchronized(mounts) {
        mounts.values.removeIf { it.ref.get() == null }
        mounts.values.mapNotNull { m -> m.ref.get()?.let { m to it } }
    }

    /** `[{key, inScene}]` for `status`. */
    internal fun describe(): ArrayNode {
        val out = Json.array()
        for ((m, node) in live()) out.add(Json.obj().put("key", m.key).put("inScene", node.scene != null))
        return out
    }

    /**
     * FCP `remountViews`. Params `{remount=true, carryState=true, restage=false}`. Result:
     * `{mounts, remounted:[key], skipped:[{key,reason}], failed:[{key,error}], stateCarried:{restored, skipped:[…]},
     * callbacks:{ran, failed:[{error}]}, restaged?, restageError?}`. `callbacks.ran` counts every callback invoked,
     * including those that failed.
     */
    internal fun remount(p: ObjectNode): ObjectNode {
        val doRemount = p.path("remount").asBoolean(true)
        val carryState = p.path("carryState").asBoolean(true)
        val restage = p.path("restage").asBoolean(false)
        return Fx.call {
            val result = Json.obj()
            val remounted = Json.array()
            val skipped = Json.array()
            val failed = Json.array()
            val carried = ViewState.empty()
            val liveMounts = live()
            result.put("mounts", liveMounts.size)
            if (doRemount) {
                for ((mount, old) in liveMounts) remountOne(mount, old, carryState, remounted, skipped, failed, carried)
            }
            result.set<ArrayNode>("remounted", remounted)
            result.set<ArrayNode>("skipped", skipped)
            result.set<ArrayNode>("failed", failed)
            result.set<ObjectNode>("stateCarried", carried)
            result.set<ObjectNode>("callbacks", runCallbacks())
            if (restage && liveMounts.isEmpty()) restage(result)
            result
        }
    }

    private fun remountOne(
        mount: Mount,
        old: Node,
        carryState: Boolean,
        remounted: ArrayNode,
        skipped: ArrayNode,
        failed: ArrayNode,
        carried: ObjectNode,
    ) {
        if (old.scene == null) {
            skipped.add(Json.obj().put("key", mount.key).put("reason", "not in a scene"))
            return
        }
        val snapshot = if (carryState) guarded(mount.key, carried) { ViewState.capture(old) } else null
        val fresh = try {
            mount.supplier.get()
        } catch (t: Throwable) {
            failed.add(Json.obj().put("key", mount.key).put("error", describeCause(t)))
            return
        }
        try {
            Reloads.swap(old, fresh)
        } catch (t: Throwable) {
            val error = if (t is org.lab59.fxloop.protocol.FcpException) t.message ?: describeCause(t) else describeCause(t)
            failed.add(Json.obj().put("key", mount.key).put("error", error))
            return
        }
        mount.ref = WeakReference(fresh)
        remounted.add(mount.key)
        if (snapshot != null) {
            guarded(mount.key, carried) { ViewState.restore(snapshot, fresh) }?.let { ViewState.merge(carried, it) }
        }
    }

    /** State carry-over must never fail a remount: a failure is recorded as a skipped entry. */
    private fun <T> guarded(key: String, carried: ObjectNode, work: () -> T): T? = try {
        work()
    } catch (t: Throwable) {
        (carried.get("skipped") as ArrayNode).add(Json.obj().put("path", key).put("reason", "state carry-over failed: ${describeCause(t)}"))
        null
    }

    private fun runCallbacks(): ObjectNode {
        val out = Json.obj()
        val failures = Json.array()
        var ran = 0
        for (cb in callbacks) {
            ran++
            try {
                cb.run()
            } catch (t: Throwable) {
                failures.add(Json.obj().put("error", describeCause(t)))
            }
        }
        out.put("ran", ran)
        out.set<ArrayNode>("failed", failures)
        return out
    }

    private fun restage(result: ObjectNode) {
        try {
            val (app, stage) = RestageHook.captured()
                ?: throw IllegalStateException("Application.start(Stage) was not observed; restage needs the agent loaded before the app starts")
            val stg = stage as javafx.stage.Stage
            stg.scene = null
            var type: Class<*>? = app.javaClass
            var start: java.lang.reflect.Method? = null
            while (type != null && start == null) {
                start = type.declaredMethods.firstOrNull {
                    it.name == "start" && it.parameterCount == 1 && it.parameterTypes[0] == javafx.stage.Stage::class.java &&
                        !java.lang.reflect.Modifier.isAbstract(it.modifiers)
                }
                type = type.superclass
            }
            checkNotNull(start) { "no start(Stage) method found on ${app.javaClass.name}" }
            start.isAccessible = true
            start.invoke(app, stg)
            result.put("restaged", true)
        } catch (t: Throwable) {
            result.put("restageError", describeCause(t))
        }
    }

    companion object {
        private val INFRA = listOf("java.", "javax.", "jdk.", "sun.", "javafx.", "com.sun.", "kotlin.", "org.lab59.fxloop.agent.", "fxloop.internal.")

        /** Installs the opt-in `restage` support (records the `Application` instance); never throws. */
        internal fun installHooks(inst: Instrumentation) {
            RestageHook.install(inst)
        }

        /** `"NullPointerException at EditForm.java:42"`: root cause (reflective wrappers unwrapped) plus first app frame. */
        internal fun describeCause(thrown: Throwable): String {
            val ex = rootCause(thrown)
            val frames = ex.stackTrace
            val frame = frames.firstOrNull { f -> INFRA.none { f.className.startsWith(it) } } ?: frames.firstOrNull()
            val name = ex.javaClass.simpleName.ifEmpty { ex.javaClass.name }
            val where = frame?.let { " at ${it.fileName ?: it.className}${if (it.lineNumber >= 0) ":${it.lineNumber}" else ""}" } ?: ""
            return name + where
        }

        private fun rootCause(t: Throwable): Throwable {
            var cur = t
            var depth = 0
            while (depth++ < 10) {
                val cause = cur.cause ?: break
                if (cause === cur) break
                val wrapper = cur is java.lang.reflect.InvocationTargetException ||
                    (cur.javaClass == RuntimeException::class.java && cur.message == cause.toString()) ||
                    cur is java.lang.reflect.UndeclaredThrowableException ||
                    cur is java.util.concurrent.ExecutionException
                if (!wrapper && cause !is java.lang.reflect.InvocationTargetException) break
                cur = cause
            }
            return cur
        }
    }
}
