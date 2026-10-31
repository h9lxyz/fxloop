package dev.fxloop.protocol

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/** Error payload of an FCP response: machine code, one-line message, optional structured data. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FcpError @JsonCreator constructor(
    @param:JsonProperty("code") val code: String,
    @param:JsonProperty("message") val message: String,
    @param:JsonProperty("data") val data: JsonNode? = null,
)

/** Thrown by either side to surface an [FcpError]. */
open class FcpException(val code: String, message: String, val data: JsonNode? = null) : RuntimeException(message) {
    constructor(error: FcpError) : this(error.code, error.message, error.data)

    fun toError(): FcpError = FcpError(code, message ?: code, data)
}
