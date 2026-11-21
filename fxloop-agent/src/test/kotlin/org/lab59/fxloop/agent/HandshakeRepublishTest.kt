package org.lab59.fxloop.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lab59.fxloop.protocol.Handshake
import java.lang.management.ManagementFactory
import java.nio.file.Path

/** `attach` loading the agent again into a JVM where it already runs (PRD attach command, agent changes). */
class HandshakeRepublishTest {
    private fun options(handshake: Path, token: String? = null) = AgentOptions(handshake, token, null, false, false, inspectorEdit = true)

    @Test
    fun `a repeated agentmain writes the same handshake to the requested path`(@TempDir dir: Path) {
        val first = dir.resolve("a/handshake.json").also { it.parent.toFile().mkdirs() }
        val again = dir.resolve("b/handshake.json").also { it.parent.toFile().mkdirs() }
        val rt = AgentRuntime.forTest(options(first), "attach")
        AgentRuntime.listenedForTest(rt, 41234)
        rt.republish(again)
        val a = Handshake.readFrom(first)
        val b = Handshake.readFrom(again)
        assertThat(b).isEqualTo(a)
        assertThat(b.port).isEqualTo(41234)
        assertThat(b.token).isEqualTo("test-token")
        assertThat(b.inspectorEdit).isTrue()
    }

    @Test
    fun `a request before the listener is up is written once it is`(@TempDir dir: Path) {
        val first = dir.resolve("handshake.json")
        val again = dir.resolve("again.json")
        val rt = AgentRuntime.forTest(options(first), "attach")
        rt.republish(again)
        assertThat(again).doesNotExist()
        AgentRuntime.listenedForTest(rt, 40001)
        assertThat(Handshake.readFrom(again).port).isEqualTo(40001)
        assertThat(Handshake.readFrom(first).port).isEqualTo(40001)
    }

    @Test
    fun `a supervised launch's handshake never carries a token, re-published or not`(@TempDir dir: Path) {
        val rt = AgentRuntime.forTest(options(dir.resolve("handshake.json"), token = "server-owned"), "javaagent", port = 40002)
        rt.republish(dir.resolve("again.json"))
        assertThat(Handshake.readFrom(dir.resolve("again.json")).token).isNull()
    }

    @Test
    fun `agentmain on a JVM where the agent already runs re-publishes through AgentRuntime start`(@TempDir dir: Path) {
        val rt = AgentRuntime.get() ?: AgentRuntime.startForTest()
        val file = dir.resolve("handshake.json")
        FxLoopAgent.agentmain("handshake=$file,logs=true,bar=on,inspectorEdit=true", null)
        assertThat(AgentRuntime.get()).isSameAs(rt) // no second runtime
        AgentRuntime.listenedForTest(rt, 40003) // the test runtime has no listener: bring it "up" so the queued copy is written
        val hs = Handshake.readFrom(file)
        assertThat(hs.pid).isEqualTo(ManagementFactory.getRuntimeMXBean().pid)
        assertThat(hs.port).isGreaterThan(0)
    }

    @Test
    fun `a copy that cannot be written does not stop the others`(@TempDir dir: Path) {
        val rt = AgentRuntime.forTest(options(dir.resolve("handshake.json")), "attach")
        dir.resolve("blocked").toFile().writeText("a file, not a dir")
        rt.republish(dir.resolve("blocked/handshake.json"))
        rt.republish(dir.resolve("ok.json"))
        AgentRuntime.listenedForTest(rt, 40004)
        assertThat(Handshake.readFrom(dir.resolve("handshake.json")).port).isEqualTo(40004)
        assertThat(Handshake.readFrom(dir.resolve("ok.json")).port).isEqualTo(40004)
    }

    @Test
    fun `attach mode reports the JVM's start time, not the moment the agent was loaded`(@TempDir dir: Path) {
        val jvmStart = ManagementFactory.getRuntimeMXBean().startTime
        // read from the process table: the same moment as the MXBean reports, to its clock tick
        assertThat(AgentRuntime.forTest(options(dir.resolve("h.json")), "attach").startedAt).isCloseTo(jvmStart, org.assertj.core.data.Offset.offset(1000L))
        val before = System.currentTimeMillis()
        assertThat(AgentRuntime.forTest(options(dir.resolve("h.json")), "javaagent").startedAt).isGreaterThanOrEqualTo(before)
    }
}
