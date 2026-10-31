package dev.fxloop.server.see

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Standalone HTML gallery of persisted frames, newest first. */
object Gallery {
    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun render(frames: List<Frame>, linkPrefix: String?, linkSuffix: String? = ""): String {
        val prefix = linkPrefix ?: ""
        val suffix = linkSuffix ?: ""
        val sorted = frames.asReversed()
        val sb = StringBuilder(4096)
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>FXLoop screens</title><style>")
            .append("body{font:14px system-ui,sans-serif;margin:0;padding:16px;background:#f4f5f7;color:#222}")
            .append("h1{font-size:18px;margin:0 0 12px}")
            .append(".grid{display:flex;flex-wrap:wrap;gap:12px}")
            .append(".card{background:#fff;border:1px solid #d8dbe0;border-radius:6px;padding:8px;max-width:320px}")
            .append(".card img{max-width:320px;width:100%;height:auto;display:block;border:1px solid #e3e5e8}")
            .append(".label{font-weight:600;margin-top:6px;word-break:break-all}")
            .append(".meta{color:#666;font-size:12px}a{color:#0b57d0}")
            .append("</style></head><body><h1>FXLoop screens (")
            .append(sorted.size).append(")</h1><div class=\"grid\">")
        for (f in sorted) {
            val name = f.file.fileName.toString()
            val href = esc(prefix + name + suffix)
            sb.append("<div class=\"card\"><a href=\"").append(href).append("\"><img src=\"").append(href)
                .append("\" loading=\"lazy\" alt=\"").append(esc(f.label)).append("\"></a>")
                .append("<div class=\"label\">").append(esc(f.label)).append("</div>")
                .append("<div class=\"meta\">#").append(f.seq).append(" &middot; ").append(esc(f.kind))
                .append(" &middot; ").append(f.w).append("&times;").append(f.h).append(" &middot; ")
                .append(TS.format(Instant.ofEpochMilli(f.ts))).append("</div>")
                .append("<div class=\"meta\"><a href=\"").append(href).append("\">full image</a></div></div>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    internal fun esc(s: String?): String {
        if (s == null) return ""
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
