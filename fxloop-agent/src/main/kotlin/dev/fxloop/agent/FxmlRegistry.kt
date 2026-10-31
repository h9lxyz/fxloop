package dev.fxloop.agent

import com.fasterxml.jackson.databind.node.ArrayNode
import dev.fxloop.protocol.Json
import java.lang.ref.WeakReference
import java.net.URL
import java.nio.charset.Charset
import java.util.ResourceBundle
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.util.BuilderFactory
import javafx.util.Callback

/**
 * FXML load-registry (PRD 02 §4): `location → weak(root), controllerClass`. Fed by the ByteBuddy hook
 * (Plan A) and by `FxLoopDev.track/load` (Plan B).
 */
class FxmlRegistry {
    /** @property loader the original loader's settings, re-applied on a live swap; null when only the location is known */
    data class Entry(
        val location: URL,
        val root: WeakReference<Any>,
        val controllerClass: String?,
        val source: String,
        val loader: LoaderSettings? = null,
    )

    /**
     * What an app sets on its [FXMLLoader] beyond the location. A swap that drops these fails for controllers built by
     * a factory (`NoSuchMethodException: <init>()`) and for `%key` strings (no resources).
     */
    class LoaderSettings(
        val resources: ResourceBundle?,
        val controllerFactory: Callback<Class<*>, Any>?,
        val builderFactory: BuilderFactory?,
        val charset: Charset?,
        val classLoader: ClassLoader?,
    ) {
        /** A fresh loader for [location] configured like the original. */
        fun newLoader(location: URL): FXMLLoader = FXMLLoader(location).also { l ->
            resources?.let { l.resources = it }
            controllerFactory?.let { l.controllerFactory = it }
            builderFactory?.let { l.builderFactory = it }
            charset?.let { l.charset = it }
            classLoader?.let { l.classLoader = it }
        }

        companion object {
            fun of(l: FXMLLoader) = LoaderSettings(l.resources, l.controllerFactory, l.builderFactory, l.charset, l.classLoader)
        }
    }

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun record(location: URL, root: Any, controller: Any?, source: String, loader: LoaderSettings? = null) {
        entries.removeIf { it.root.get() == null || it.root.get() === root }
        entries.add(Entry(location, WeakReference(root), controller?.javaClass?.name, source, loader))
    }

    /** Records a finished load with the loader's settings, so a live swap re-creates it the same way. */
    fun record(loader: FXMLLoader, root: Any, source: String) {
        val location = loader.location ?: return
        record(location, root, loader.getController<Any?>(), source, LoaderSettings.of(loader))
    }

    @Synchronized
    fun live(): List<Entry> {
        entries.removeIf { it.root.get() == null }
        return entries.toList()
    }

    @Synchronized
    fun replace(old: Entry, newRoot: Any, controller: Any?) {
        entries.remove(old)
        entries.add(Entry(old.location, WeakReference(newRoot), controller?.javaClass?.name, old.source, old.loader))
    }

    @Synchronized
    internal fun describe(): ArrayNode {
        val out = Json.array()
        for (e in live()) {
            val o = out.addObject()
            o.put("location", e.location.toString())
            o.put("controller", e.controllerClass)
            o.put("source", e.source)
            val root = e.root.get()
            if (root is Node) {
                o.put("rootPath", NodePath.of(root))
            }
        }
        return out
    }

    companion object {
        /** Called from instrumented `FXMLLoader` code; must never throw. */
        @JvmStatic
        fun recordFromHook(loader: Any?, root: Any?) {
            try {
                val rt = AgentRuntime.get()
                if (rt != null && loader is FXMLLoader && root != null) {
                    rt.fxml.record(loader, root, "hook")
                }
            } catch (ignored: Throwable) {
                // never disturb FXMLLoader
            }
        }
    }
}
