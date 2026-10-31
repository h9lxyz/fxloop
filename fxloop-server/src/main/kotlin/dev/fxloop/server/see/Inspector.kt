package dev.fxloop.server.see

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.fxloop.protocol.Json
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Opens the browser CSS inspector (PRD 08 §3.4): builds the `/inspect` URL on the (lazily started) viewer and
 * launches the system browser unless disabled. [launcher] returns whether a browser was started; it is injectable
 * so tests never open one.
 */
class Inspector(
    private val viewer: () -> Viewer,
    private val browserEnabled: Boolean,
    private val launcher: (String) -> Boolean = ::launchBrowser,
) {
    /** Returns `{url, opened, reason?}`; waits at most 2 s for the launcher and never throws for launch failures. */
    fun open(h: Int?, path: String?): ObjectNode {
        val url = buildUrl(viewer().inspectUrl(), h, path)
        if (!browserEnabled) return Json.obj { put("url", url); put("opened", false); put("reason", "FXLOOP_BROWSER=none") }
        val ok = try {
            CompletableFuture.supplyAsync { runCatching { launcher(url) }.getOrDefault(false) }.get(2, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            true // still starting; Desktop.browse can be slow but does not fail late
        } catch (_: Exception) {
            false
        }
        return Json.obj {
            put("url", url)
            put("opened", ok)
            if (!ok) put("reason", "no browser could be launched; open the URL manually")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Inspector::class.java)

        /** Appends `h` / `path` (URL-encoded) to an `…/inspect?t=…` base. */
        fun buildUrl(base: String, h: Int?, path: String?): String = buildString {
            append(base)
            if (h != null) append("&h=").append(h)
            if (!path.isNullOrEmpty()) append("&path=").append(URLEncoder.encode(path, StandardCharsets.UTF_8))
        }

        /** Desktop.browse when available, else the platform opener; output discarded. */
        fun launchBrowser(url: String): Boolean {
            val desktop = !java.awt.GraphicsEnvironment.isHeadless() && java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)
            if (desktop) {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url))
                    return true
                } catch (e: Exception) {
                    log.debug("Desktop.browse failed", e)
                }
            }
            val os = System.getProperty("os.name", "").lowercase()
            val cmd = when {
                "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                "mac" in os -> listOf("open", url)
                else -> listOf("xdg-open", url)
            }
            return try {
                ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                true
            } catch (e: Exception) {
                log.debug("{} failed", cmd[0], e)
                false
            }
        }
    }
}
