package org.lab59.fxloop.server.see

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path

class ScreenStoreTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }

        fun png(): ByteArray = ImageOpsTest.png(20, 10, Color.WHITE, 0, 0, 0, 0)
    }

    @Test
    fun saveAssignsSeqAndFiles(@TempDir dir: Path) {
        val s = ScreenStore(dir.resolve("screens"), 10)
        val f1 = s.save(png(), "Login Page!", "screenshot")
        val f2 = s.save(png(), "x", "screenshot")
        assertThat(f2.seq).isGreaterThan(f1.seq)
        assertThat(f1.file.fileName.toString()).isEqualTo("0001-login-page.png")
        assertThat(f1.w).isEqualTo(20)
        assertThat(Files.readString(dir.resolve("screens/index.html"))).contains("0002-x.png")
    }

    @Test
    fun retentionPrunes(@TempDir dir: Path) {
        val s = ScreenStore(dir, 2)
        val f1 = s.save(png(), "a", "screenshot")
        s.save(png(), "b", "screenshot")
        s.save(png(), "c", "screenshot")
        assertThat(s.list()).hasSize(2)
        assertThat(Files.exists(f1.file)).isFalse()
        assertThat(s.bySeq(f1.seq)).isNull()
    }

    @Test
    fun reopenContinuesSeq(@TempDir dir: Path) {
        val s = ScreenStore(dir, 5)
        s.save(png(), "a", "screenshot")
        s.save(png(), "b", "screenshot")
        val s2 = ScreenStore(dir, 5)
        assertThat(s2.list()).hasSize(2)
        assertThat(s2.save(png(), "c", "screenshot").seq).isEqualTo(3)
    }
}
