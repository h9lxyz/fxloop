package dev.fxloop.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One NDJSON frame. Exactly one shape is populated:
 * request {@code {id, method, params}}, response {@code {id, result | error}},
 * or event {@code {event, payload}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FcpFrame(Long id, String method, ObjectNode params, JsonNode result, FcpError error, String event,
        JsonNode payload) {

    public static FcpFrame request(long id, String method, ObjectNode params) {
        return new FcpFrame(id, method, params == null ? Json.object() : params, null, null, null, null);
    }

    public static FcpFrame result(long id, JsonNode result) {
        return new FcpFrame(id, null, null, result == null ? Json.object() : result, null, null, null);
    }

    public static FcpFrame error(Long id, FcpError error) {
        return new FcpFrame(id, null, null, null, error, null, null);
    }

    public static FcpFrame event(String event, JsonNode payload) {
        return new FcpFrame(null, null, null, null, null, event, payload);
    }

    @JsonIgnore
    public boolean isRequest() {
        return method != null;
    }

    @JsonIgnore
    public boolean isEvent() {
        return event != null;
    }

    @JsonIgnore
    public boolean isResponse() {
        return method == null && event == null && id != null;
    }
}
