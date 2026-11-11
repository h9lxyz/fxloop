package org.lab59.fxloop.server.see

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class ImageOpsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }

        @JvmStatic
        fun png(w: Int, h: Int, bg: Color, bx: Int, by: Int, bw: Int, bh: Int): ByteArray {
            val i = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = i.createGraphics()
            g.color = bg
            g.fillRect(0, 0, w, h)
            if (bw > 0) {
                g.color = Color.BLACK
                g.fillRect(bx, by, bw, bh)
            }
            g.dispose()
            return ImageOps.encode(i)
        }
    }

    @Test
    fun sizeAndDownscale() {
        val p = png(400, 200, Color.WHITE, 0, 0, 0, 0)
        assertThat(ImageOps.size(p)).containsExactly(400, 200)
        assertThat(ImageOps.downscale(p, 500)).isSameAs(p)
        assertThat(ImageOps.size(ImageOps.downscale(p, 100))).containsExactly(100, 50)
    }

    @Test
    fun identicalIsNone() {
        val p = png(200, 100, Color.WHITE, 0, 0, 0, 0)
        val r = ImageOps.diff(p, p, 0.05, 800)
        assertThat(r.changedPct).isZero()
        assertThat(r.verdict).isEqualTo("none")
        assertThat(r.regions).isEmpty()
        assertThat(r.cropped).isFalse()
    }

    @Test
    fun blockDetected() {
        val a = png(400, 300, Color.WHITE, 0, 0, 0, 0)
        val b = png(400, 300, Color.WHITE, 100, 100, 40, 40)
        val r = ImageOps.diff(a, b, 0.05, 800)
        assertThat(r.changedPct).isBetween(1.0, 2.0) // 1600/120000 = 1.33
        assertThat(r.verdict).isEqualTo("major")
        assertThat(r.regions).hasSize(1)
        val rect = r.regions[0]
        assertThat(rect.x).isLessThanOrEqualTo(100)
        assertThat(rect.y).isLessThanOrEqualTo(100)
        assertThat(rect.x + rect.w).isGreaterThanOrEqualTo(140)
        assertThat(rect.y + rect.h).isGreaterThanOrEqualTo(140)
        assertThat(ImageOps.size(r.diffPng)).containsExactly(400, 300)
    }

    @Test
    fun scaledDiffRegionsInFullResolution() {
        val a = png(1600, 1000, Color.WHITE, 0, 0, 0, 0)
        val b = png(1600, 1000, Color.WHITE, 800, 400, 200, 200)
        val r = ImageOps.diff(a, b, 0.05, 800)
        assertThat(r.w).isEqualTo(1600)
        val rect = r.regions[0]
        assertThat(rect.x).isLessThanOrEqualTo(810)
        assertThat(rect.x + rect.w).isGreaterThanOrEqualTo(990)
    }

    @Test
    fun sizeMismatchCrops() {
        val a = png(300, 200, Color.WHITE, 0, 0, 0, 0)
        val b = png(200, 150, Color.WHITE, 0, 0, 0, 0)
        val r = ImageOps.diff(a, b, 0.05, 800)
        assertThat(r.cropped).isTrue()
        assertThat(r.w).isEqualTo(200)
        assertThat(r.h).isEqualTo(150)
    }

    @Test
    fun annotateChangesImage() {
        val a = png(300, 200, Color.WHITE, 0, 0, 0, 0)
        val out = ImageOps.annotate(a, listOf(ImageOps.Mark(1, 10.0, 10.0, 80.0, 30.0), ImageOps.Mark(12, 290.0, 190.0, 40.0, 40.0)))
        assertThat(out).isNotEqualTo(a)
        assertThat(ImageOps.size(out)).containsExactly(300, 200)
    }

    @Test
    fun stripWidth() {
        val a = png(100, 50, Color.WHITE, 0, 0, 0, 0)
        val b = png(120, 80, Color.WHITE, 0, 0, 0, 0)
        val out = ImageOps.strip(listOf(a, b), listOf("one", "two"))
        val s = ImageOps.size(out)
        assertThat(s[0]).isBetween(220 + 12, 220 + 12 + 60)
        assertThat(s[1]).isGreaterThan(80)
    }
}
