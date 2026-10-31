package dev.fxloop.server.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Bounded line ring for process stdout/stderr (2000 lines per stream). */
class LogRing(private val capacity: Int) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
    }

    @Synchronized
    fun tail(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun clear() = lines.clear()
}

/**
 * Server-side ring for agent events (UI errors, app log records). Entries get a server sequence so numbering stays
 * monotonic across app restarts; per-launch agent sequences are de-duplicated.
 */
class EventRing(private val capacity: Int) {
    private val ring = ArrayDeque<ObjectNode>()
    private var seq = 0L
    private var launch = 0L
    private var lastAgentSeq = 0L

    @Synchronized
    fun newLaunch(launchId: Long) {
        launch = launchId
        lastAgentSeq = 0
    }

    @Synchronized
    fun add(agentEntry: JsonNode) {
        val agentSeq = agentEntry.path("seq").asLong()
        if (agentSeq <= lastAgentSeq) {
            // a re-sent entry or a dedupCount update for this launch
            ring.filter { it.path("launch").asLong() == launch && it.path("agentSeq").asLong() == agentSeq }
                .forEach { it.put("dedupCount", agentEntry.path("dedupCount").asInt(1)) }
            return
        }
        lastAgentSeq = agentSeq
        val e = (agentEntry as ObjectNode).deepCopy()
        e.put("agentSeq", agentSeq)
        e.put("seq", ++seq)
        e.put("launch", launch)
        ring.addLast(e)
        while (ring.size > capacity) ring.removeFirst()
    }

    @Synchronized
    fun since(sinceSeq: Long): List<ObjectNode> = ring.filter { it["seq"].asLong() > sinceSeq }.map { it.deepCopy() }

    @Synchronized
    fun lastSeq() = seq
}
