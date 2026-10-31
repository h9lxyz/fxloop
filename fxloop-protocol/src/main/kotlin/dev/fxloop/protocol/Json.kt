package dev.fxloop.protocol

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode

/** Shared Jackson configuration for every FCP / descriptor payload. */
object Json {
    @JvmField
    val MAPPER: ObjectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    @JvmStatic fun obj(): ObjectNode = MAPPER.createObjectNode()
    @JvmStatic fun array(): ArrayNode = MAPPER.createArrayNode()
    @JvmStatic fun tree(value: Any?): JsonNode = MAPPER.valueToTree(value)
    @JvmStatic fun text(value: String): JsonNode = TextNode.valueOf(value)
    @JvmStatic fun nullNode(): JsonNode = NullNode.instance
    @JvmStatic fun <T> convert(value: Any?, type: Class<T>): T = MAPPER.convertValue(value, type)
    @JvmStatic fun write(value: Any?): String = MAPPER.writeValueAsString(value)
    @JvmStatic fun pretty(value: Any?): String = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)
    @JvmStatic fun parse(text: String): JsonNode = MAPPER.readTree(text)

    /** `{"a":1}` builder sugar for Kotlin call sites. */
    inline fun obj(build: ObjectNode.() -> Unit): ObjectNode = obj().apply(build)

    @JvmStatic
    fun strings(values: Iterable<String>): ArrayNode = array().also { a -> values.forEach { a.add(it) } }
}
