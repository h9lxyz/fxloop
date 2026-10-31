package dev.fxloop.server.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Duration

/** Real stdio round trip with the official MCP client: initialize, list tools, call a tool that needs no app. */
class StdioSmokeTest {
    @Test
    fun `server speaks MCP over stdio and keeps stdout clean`(@TempDir dir: Path) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val params = ServerParameters.builder(java)
            .args("-cp", System.getProperty("java.class.path"), "dev.fxloop.server.MainKt", "--project-dir=$dir")
            .build()
        val transport = StdioClientTransport(params, JacksonMcpJsonMapper(ObjectMapper()))
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build().use { client ->
            val init = client.initialize()
            assertThat(init.serverInfo().name()).isEqualTo("fxloop")
            assertThat(init.instructions()).contains("fx_reload")
            val tools = client.listTools().tools()
            assertThat(tools.map { it.name() }).contains("fx_take_screenshot", "fx_reload")
            val r = client.callTool(McpSchema.CallToolRequest.builder("fx_get_journal").arguments(mapOf<String, Any>()).build())
            assertThat(r.isError()).isFalse()
            assertThat((r.content()[0] as McpSchema.TextContent).text()).contains("journal is empty")
        }
        assertThat(File.pathSeparator).isNotEmpty()
    }
}
