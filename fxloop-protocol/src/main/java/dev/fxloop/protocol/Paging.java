package dev.fxloop.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Standard pagination envelope: {@code {total, count, offset, items, has_more, next_offset}}. */
public final class Paging {
    private Paging() {}

    public static ObjectNode page(List<? extends JsonNode> all, int offset, int limit) {
        int total = all.size();
        int from = Math.max(0, Math.min(offset, total));
        int to = Math.min(total, from + Math.max(0, limit));
        ArrayNode items = Json.array();
        all.subList(from, to).forEach(items::add);
        ObjectNode page = Json.object();
        page.put("total", total);
        page.put("count", to - from);
        page.put("offset", from);
        page.set("items", items);
        page.put("has_more", to < total);
        if (to < total) {
            page.put("next_offset", to);
        }
        return page;
    }
}
