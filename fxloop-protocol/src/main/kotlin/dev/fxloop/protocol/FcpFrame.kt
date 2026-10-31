package dev.fxloop.protocol

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * One NDJSON frame. Exactly one shape is populated: request `{id, method, params}`,
 * response `{id, result | error}`, or event `{event, payload}`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FcpFrame @JsonCreator constructor(
    @param:JsonProperty("id") val id: Long?,
    @param:JsonProperty("method") val method: String?,
    @param:JsonProperty("params") val params: ObjectNode?,
    @param:JsonProperty("result") val result: JsonNode?,
    @param:JsonProperty("error") val error: FcpError?,
    @param:JsonProperty("event") val event: String?,
    @param:JsonProperty("payload") val payload: JsonNode?,
) {
    @get:JsonIgnore
    val isRequest: Boolean get() = method != null

    @get:JsonIgnore
    val isEvent: Boolean get() = event != null

    @get:JsonIgnore
    val isResponse: Boolean get() = method == null && event == null && id != null

    companion object {
        @JvmStatic
        fun request(id: Long, method: String, params: ObjectNode?) = FcpFrame(id, method, params ?: Json.obj(), null, null, null, null)

        @JvmStatic
        fun result(id: Long?, result: JsonNode?) = FcpFrame(id, null, null, result ?: Json.obj(), null, null, null)

        @JvmStatic
        fun error(id: Long?, error: FcpError) = FcpFrame(id, null, null, null, error, null, null)

        @JvmStatic
        fun event(event: String, payload: JsonNode?) = FcpFrame(null, null, null, null, null, event, payload)
    }
}
