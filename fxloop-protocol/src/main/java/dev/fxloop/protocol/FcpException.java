package dev.fxloop.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** Thrown by either side to surface an {@link FcpError}. */
public class FcpException extends RuntimeException {
    private final String code;
    private final transient JsonNode data;

    public FcpException(String code, String message) {
        this(code, message, null);
    }

    public FcpException(String code, String message, JsonNode data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public FcpException(FcpError error) {
        this(error.code(), error.message(), error.data());
    }

    public String code() {
        return code;
    }

    public JsonNode data() {
        return data;
    }

    public FcpError toError() {
        return new FcpError(code, getMessage(), data);
    }
}
