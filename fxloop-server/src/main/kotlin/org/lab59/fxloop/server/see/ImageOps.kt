package org.lab59.fxloop.server.see

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** PNG helpers for the "see" pipeline: downscale, Set-of-Mark annotation, visual diff, strips. */
object ImageOps {
    private val PALETTE = intArrayOf(
        0xE6194B, 0x3CB44B, 0x4363D8, 0xF58231, 0x911EB4, 0x00A3A3, 0xC2185B, 0x8D6E00,
    ).map { Color(it) }

    data class Mark(val n: Int, val x: Double, val y: Double, val w: Double, val h: Double)

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    data class DiffResult(
        val changedPct: Double,
        val regions: List<Rect>,
        val diffPng: ByteArray,
        val verdict: String,
        val w: Int,
        val h: Int,
        val cropped: Boolean,
    )

    fun size(png: ByteArray): IntArray {
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(png)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (readers.hasNext()) {
                    val r = readers.next()
                    try {
                        r.setInput(input, true, true)
                        return intArrayOf(r.getWidth(0), r.getHeight(0))
                    } finally {
                        r.dispose()
                    }
                }
            }
        } catch (_: IOException) {
            // fall through to full decode
        }
        val img = decode(png)
        return intArrayOf(img.width, img.height)
    }

    internal fun decode(png: ByteArray): BufferedImage = try {
        ImageIO.read(ByteArrayInputStream(png)) ?: throw IllegalArgumentException("not a decodable image")
    } catch (e: IOException) {
        throw UncheckedIOException(e)
    }

    internal fun encode(img: BufferedImage): ByteArray = try {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        out.toByteArray()
    } catch (e: IOException) {
        throw UncheckedIOException(e)
    }

    private fun argb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val c = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = c.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return c
    }

    private fun scaled(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    fun downscale(png: ByteArray, maxWidth: Int): ByteArray {
        val img = decode(png)
        if (img.width <= maxWidth || maxWidth <= 0) return png
        val h = max(1, (img.height * (maxWidth / img.width.toDouble())).let { Math.round(it).toInt() })
        // step down by halves for better quality
        var cur = argb(img)
        while (cur.width / 2 >= maxWidth) {
            cur = scaled(cur, cur.width / 2, max(1, cur.height / 2))
        }
        return encode(scaled(cur, maxWidth, h))
    }

    fun annotate(png: ByteArray, marks: List<Mark>): ByteArray {
        val img = argb(decode(png))
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val fontSize = max(11, min(18, img.width / 60))
        g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
        val fm = g.fontMetrics
        for ((i, m) in marks.withIndex()) {
            val c = PALETTE[i % PALETTE.size]
            val x = Math.round(m.x).toInt()
            val y = Math.round(m.y).toInt()
            val w = max(1, Math.round(m.w).toInt())
            val h = max(1, Math.round(m.h).toInt())
            g.color = Color(c.red, c.green, c.blue, 40)
            g.fillRect(x, y, w, h)
            g.color = Color(c.red, c.green, c.blue, 230)
            g.stroke = BasicStroke(2f)
            g.drawRect(x, y, w, h)
            val label = m.n.toString()
            val bw = max(fm.height, fm.stringWidth(label) + 8)
            val bh = fm.height
            val bx = max(0, min(x, img.width - bw))
            val by = max(0, min(y, img.height - bh))
            g.color = c
            g.fill(RoundRectangle2D.Double(bx.toDouble(), by.toDouble(), bw.toDouble(), bh.toDouble(), 8.0, 8.0))
            g.color = Color.WHITE
            g.stroke = BasicStroke(1f)
            g.draw(RoundRectangle2D.Double(bx.toDouble(), by.toDouble(), bw.toDouble(), bh.toDouble(), 8.0, 8.0))
            g.drawString(label, bx + (bw - fm.stringWidth(label)) / 2f, (by + fm.ascent).toFloat())
        }
        g.dispose()
        return encode(out)
    }

    fun diff(before: ByteArray, after: ByteArray, threshold: Double, internalWidth: Int): DiffResult {
        var a = argb(decode(before))
        var b = argb(decode(after))
        val cropped = a.width != b.width || a.height != b.height
        if (cropped) {
            val w = min(a.width, b.width)
            val h = min(a.height, b.height)
            a = a.getSubimage((a.width - w) / 2, (a.height - h) / 2, w, h)
            b = b.getSubimage((b.width - w) / 2, (b.height - h) / 2, w, h)
        }
        val fullW = b.width
        val fullH = b.height
        var iw = fullW
        var ih = fullH
        if (internalWidth > 0 && fullW > internalWidth) {
            iw = internalWidth
            ih = max(1, Math.round(fullH * (iw / fullW.toDouble())).toInt())
            a = scaled(a, iw, ih)
            b = scaled(b, iw, ih)
        }
        val changed = BooleanArray(iw * ih)
        var count = 0L
        val norm = sqrt(3.0) * 255.0
        for (y in 0 until ih) {
            for (x in 0 until iw) {
                val p = a.getRGB(x, y)
                val q = b.getRGB(x, y)
                val dr = ((p shr 16) and 255) - ((q shr 16) and 255)
                val dg = ((p shr 8) and 255) - ((q shr 8) and 255)
                val db = (p and 255) - (q and 255)
                val d = sqrt(dr.toDouble() * dr + dg.toDouble() * dg + db.toDouble() * db) / norm
                if (d > threshold) {
                    changed[y * iw + x] = true
                    count++
                }
            }
        }
        val pct = 100.0 * count / (iw.toDouble() * ih)

        // cell clustering
        val cell = 8
        val cw = (iw + cell - 1) / cell
        val ch = (ih + cell - 1) / cell
        val cells = BooleanArray(cw * ch)
        for (y in 0 until ih) {
            for (x in 0 until iw) {
                if (changed[y * iw + x]) cells[(y / cell) * cw + (x / cell)] = true
            }
        }
        val seen = BooleanArray(cw * ch)
        var boxes = ArrayList<IntArray>() // x0,y0,x1,y1 exclusive, internal px
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                val idx = cy * cw + cx
                if (!cells[idx] || seen[idx]) continue
                var minX = cx
                var maxX = cx
                var minY = cy
                var maxY = cy
                val stack = ArrayList<Int>()
                stack.add(idx)
                seen[idx] = true
                while (stack.isNotEmpty()) {
                    val cur = stack.removeAt(stack.size - 1)
                    val ux = cur % cw
                    val uy = cur / cw
                    minX = min(minX, ux)
                    maxX = max(maxX, ux)
                    minY = min(minY, uy)
                    maxY = max(maxY, uy)
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = ux + dx
                            val ny = uy + dy
                            if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue
                            val ni = ny * cw + nx
                            if (cells[ni] && !seen[ni]) {
                                seen[ni] = true
                                stack.add(ni)
                            }
                        }
                    }
                }
                boxes.add(intArrayOf(minX * cell, minY * cell, min(iw, (maxX + 1) * cell), min(ih, (maxY + 1) * cell)))
            }
        }
        // merge overlapping/touching
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) {
                    val p = boxes[i]
                    val q = boxes[j]
                    if (p[0] <= q[2] && q[0] <= p[2] && p[1] <= q[3] && q[1] <= p[3]) {
                        boxes[i] = intArrayOf(min(p[0], q[0]), min(p[1], q[1]), max(p[2], q[2]), max(p[3], q[3]))
                        boxes.removeAt(j)
                        merged = true
                        break@outer
                    }
                }
            }
        }
        boxes.sortBy { -((it[2] - it[0]).toLong() * (it[3] - it[1])) }
        if (boxes.size > 20) boxes = ArrayList(boxes.subList(0, 20))

        // diff image at internal resolution
        val dimg = BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until ih) {
            for (x in 0 until iw) {
                if (changed[y * iw + x]) {
                    dimg.setRGB(x, y, 0xFFFF00AA.toInt())
                } else {
                    val q = b.getRGB(x, y)
                    val gray = 0.299 * ((q shr 16) and 255) + 0.587 * ((q shr 8) and 255) + 0.114 * (q and 255)
                    val v = Math.round(255 - (255 - gray) * 0.4).toInt() // dim toward white
                    dimg.setRGB(x, y, 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
                }
            }
        }
        val g = dimg.createGraphics()
        g.color = Color(0xD50000)
        g.stroke = BasicStroke(1f)
        for (r in boxes) g.drawRect(r[0], r[1], max(0, r[2] - r[0] - 1), max(0, r[3] - r[1] - 1))
        g.dispose()

        val sx = fullW / iw.toDouble()
        val sy = fullH / ih.toDouble()
        val regions = boxes.map { r ->
            val x0 = (r[0] * sx).roundToInt()
            val y0 = (r[1] * sy).roundToInt()
            val x1 = min(fullW, (r[2] * sx).roundToInt())
            val y1 = min(fullH, (r[3] * sy).roundToInt())
            Rect(x0, y0, x1 - x0, y1 - y0)
        }
        val verdict = if (pct < 0.01) "none" else if (pct < 0.5) "minor" else "major"
        return DiffResult(pct, regions, encode(dimg), verdict, fullW, fullH, cropped)
    }

    fun strip(pngs: List<ByteArray>, captions: List<String>?): ByteArray {
        val gap = 12
        val pad = 12
        val imgs = pngs.map { argb(decode(it)) }
        val capH = 22
        var totalW = pad * 2 + max(0, imgs.size - 1) * gap
        var maxH = 1
        for (i in imgs) {
            totalW += i.width
            maxH = max(maxH, i.height)
        }
        totalW = max(totalW, 1)
        val out = BufferedImage(totalW, pad * 2 + maxH + capH, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.composite = AlphaComposite.SrcOver
        g.color = Color(0xF2F2F2)
        g.fillRect(0, 0, out.width, out.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        var x = pad
        for ((i, im) in imgs.withIndex()) {
            g.drawImage(im, x, pad, null)
            val cap = captions?.getOrNull(i)
            if (cap != null) {
                g.color = Color(0x222222)
                val old = g.clip
                g.setClip(x, pad + maxH, max(im.width, 1), capH)
                g.drawString(cap, x, pad + maxH + 16)
                g.clip = old
            }
            x += im.width + gap
        }
        g.dispose()
        return encode(out)
    }
}
