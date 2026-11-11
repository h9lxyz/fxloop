package org.lab59.fxloop.agent

import com.fasterxml.jackson.databind.JsonNode
import org.lab59.fxloop.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import javafx.scene.Scene
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start

@ExtendWith(ApplicationExtension::class)
class GeometryStoreFxTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(VBox(), 300.0, 200.0)
        stage.show()
    }

    private fun stages(file: Path): JsonNode? =
        runCatching { Json.MAPPER.readTree(Files.readString(file)).path("stages") }.getOrNull()?.takeIf { it.size() > 0 }

    /** Blocks until the debounced save has written [file]; its async write may be seen half done. */
    private fun awaitSaved(file: Path): JsonNode {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            stages(file)?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("nothing saved to $file")
    }

    @Test
    fun overlayStagesAreNeitherSavedNorCounted(@TempDir dir: Path) {
        val file = dir.resolve("state.json")
        val saving = GeometryStore(file)
        val bar = Fx.call {
            val s = Stage()
            Overlays.mark(s)
            s.scene = Scene(VBox(), 400.0, 24.0)
            saving.install()
            s.show()
            stage.x += 10.0
            s
        }
        try {
            assertThat(awaitSaved(file).size()).isEqualTo(1)
        } finally {
            Fx.call {
                saving.uninstall()
                bar.hide()
            }
        }

        // a later run's second stage takes stage index 1, which must not hold the bar's 24 px height
        val restoring = GeometryStore(file)
        val second = Fx.call {
            restoring.install()
            Stage().apply {
                scene = Scene(VBox(), 500.0, 400.0)
                show()
            }
        }
        try {
            assertThat(Fx.call { second.height }).isGreaterThanOrEqualTo(400.0)
        } finally {
            Fx.call {
                restoring.uninstall()
                second.hide()
            }
        }
    }
}
