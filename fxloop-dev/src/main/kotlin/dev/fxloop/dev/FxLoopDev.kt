package dev.fxloop.dev

import dev.fxloop.agent.AgentRuntime
import dev.fxloop.agent.Embedded
import javafx.fxml.FXMLLoader
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

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

    /** Registers an already-loaded [FXMLLoader] (its location, root and controller) for live swaps. */
    @JvmStatic
    fun track(loader: FXMLLoader) {
        if (AgentRuntime.get() == null && System.getProperty("fxloop.dev").toBoolean()) install()
        val location = loader.location ?: return
        val root = loader.getRoot<Any?>() ?: return
        AgentRuntime.get()?.fxml?.record(location, root, loader.getController<Any?>(), "track")
    }

    private fun handshakePath(): Path {
        System.getProperty("fxloop.handshake")?.let { return Path.of(it) }
        val cwd = Path.of(System.getProperty("user.dir"))
        val gradle = listOf("build.gradle", "build.gradle.kts").any { Files.exists(cwd.resolve(it)) }
        return cwd.resolve(if (gradle) "build" else "target").resolve("fxloop").resolve("handshake.json")
    }
}
