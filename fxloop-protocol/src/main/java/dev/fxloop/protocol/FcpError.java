package dev.fxloop.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** Error payload of an FCP response: machine code, one-line message, optional structured data. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FcpError(String code, String message, JsonNode data) {
    public FcpError(String code, String message) {
        this(code, message, null);
    }
}
