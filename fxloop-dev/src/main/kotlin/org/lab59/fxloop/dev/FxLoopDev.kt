package org.lab59.fxloop.dev

import org.lab59.fxloop.agent.AgentRuntime
import org.lab59.fxloop.agent.Embedded
import javafx.fxml.FXMLLoader
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier
import javafx.scene.Node

/**
 * Embedded mode (PRD 02 §9, F9) and Plan B of the FXML load-registry (PRD 02 §4) — public API for apps launched
 * outside fxloop (IDE run configurations).
 *
 * ```java
 * public void start(Stage stage) throws Exception {
 *     FxLoopDev.install();                                   // no-op unless enabled, see below
 *     Parent root = FxLoopDev.load(getClass().getResource("main.fxml"));
 *     ...
 * }
 * ```
 * Then call `fx_connect_existing` from the MCP client. Guards: `-Dfxloop.enabled=false` always wins;
 * implicit installation from [load]/[track] additionally requires `-Dfxloop.dev=true`.
 * Keep this artifact out of production builds (dev profile or `provided` scope, see docs/security.md).
 */
object FxLoopDev {
    /**
     * Starts the agent in-process (idempotent). The handshake (with its own token, owner-only) is written to
     * `-Dfxloop.handshake=<path>`, else `build/fxloop/handshake.json` for Gradle projects, else `target/fxloop/handshake.json`.
     *
     * @return the handshake path, or `null` when disabled via `-Dfxloop.enabled=false`
     */
    @JvmStatic
    fun install(): Path? {
        val handshake = handshakePath()
        return if (Embedded.install(handshake, null) == null) null else handshake
    }

    /** Loads an FXML document and registers it for live swaps. */
    @JvmStatic
    fun <T> load(location: URL): T {
        val loader = FXMLLoader(location)
        val root = loader.load<T>()
        track(loader)
        return root
    }

    /** Registers an already-loaded [FXMLLoader] (its location, root, controller and loader settings) for live swaps. */
    @JvmStatic
    fun track(loader: FXMLLoader) {
        if (AgentRuntime.get() == null && System.getProperty("fxloop.dev").toBoolean()) install()
        val root = loader.getRoot<Any?>() ?: return
        AgentRuntime.get()?.fxml?.record(loader, root, "track")
    }

    /**
     * Registers a view factory for live remounting (PRD 07 §3) and returns the node it builds. After a hotswap the
     * agent calls [supplier] again and swaps the fresh node into the scene, so edits to the builder's body show up
     * without a restart. Plain passthrough (`supplier.get()`) when the agent is absent or disabled.
     *
     * Why method references: pass `this::buildEditForm`, not a lambda such as `() -> new EditForm()` — the lambda
     * body is compiled into a synthetic method of the enclosing class that a hotswap cannot always replace, and it
     * captures state at registration time. A method reference re-resolves to the live method on every call, so the
     * redefined body is what runs.
     *
     * ```java
     * root.setCenter(FxLoopDev.view("edit-form", this::buildEditForm));
     * ```
     * [key] identifies the mount; registering the same key again replaces it.
     */
    @JvmStatic
    fun <T : Node> view(key: String, supplier: Supplier<T>): T {
        val node = supplier.get()
        AgentRuntime.get()?.mounts?.register(key, supplier, node)
        return node
    }

    /** Runs [callback] on the FX thread after every successful hotswap or FXML tier (no-op without the agent). */
    @JvmStatic
    fun onReload(callback: Runnable) {
        AgentRuntime.get()?.mounts?.onReload(callback)
    }

    private fun handshakePath(): Path {
        System.getProperty("fxloop.handshake")?.let { return Path.of(it) }
        val cwd = Path.of(System.getProperty("user.dir"))
        val gradle = listOf("build.gradle", "build.gradle.kts").any { Files.exists(cwd.resolve(it)) }
        return cwd.resolve(if (gradle) "build" else "target").resolve("fxloop").resolve("handshake.json")
    }
}
