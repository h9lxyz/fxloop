package org.lab59.fxloop.server.see

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lab59.fxloop.protocol.Json
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Developer → agent prompts from the CSS inspector's *Ask agent* box: an in-memory queue. The inspector submits a
 * prompt together with the node (and declaration) it is about; the agent takes the oldest pending one with
 * `fx_wait_for_prompt` (a long poll) and answers with `fx_reply_to_prompt`. Delivery is at most once: a prompt is
 * `taken` when a waiter returns it. [onChange] receives a fresh [snapshot] after every change, for the SSE push.
 */
class Prompts(private val keep: Int = 50) {
    class Prompt(val id: Int, val text: String, val context: ObjectNode, val createdAt: Long) {
        var status = PENDING
        val replies = ArrayList<Reply>()
    }

    class Reply(val text: String, val at: Long)

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val items = ArrayDeque<Prompt>()
    private var nextId = 1
    private var waiters = 0

    @Volatile
    var onChange: ((ObjectNode) -> Unit)? = null

    /** Queues a prompt; [context] is what the inspector had selected (`window`, `h`, `path`, `property`, …). */
    fun submit(text: String, context: ObjectNode): Prompt {
        val p = lock.withLock {
            Prompt(nextId++, text, context, System.currentTimeMillis()).also {
                items.addLast(it)
                while (items.size > keep) items.removeFirst()
                arrived.signal()
            }
        }
        changed()
        return p
    }

    /** Takes the oldest pending prompt, waiting up to [timeoutMs]; null on timeout or interrupt. */
    fun take(timeoutMs: Long): Prompt? {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock { waiters++ }
        changed()
        try {
            return lock.withLock {
                var p = items.firstOrNull { it.status == PENDING }
                while (p == null && nanos > 0) {
                    nanos = arrived.awaitNanos(nanos)
                    p = items.firstOrNull { it.status == PENDING }
                }
                p?.also { it.status = TAKEN }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            lock.withLock { waiters-- }
            changed()
        }
    }

    /** Adds the agent's answer; [done] marks the prompt answered (otherwise it is a progress note). */
    fun reply(id: Int, text: String, done: Boolean): Prompt {
        val p = lock.withLock {
            val p = items.firstOrNull { it.id == id } ?: throw NoSuchElementException("no prompt #$id")
            p.replies += Reply(text, System.currentTimeMillis())
            if (done) p.status = ANSWERED
            p
        }
        changed()
        return p
    }

    /** The developer withdraws a prompt no agent has taken yet; false when it is gone or already taken. */
    fun cancel(id: Int): Boolean {
        val ok = lock.withLock {
            val p = items.firstOrNull { it.id == id && it.status == PENDING } ?: return@withLock false
            p.status = CANCELLED
            true
        }
        if (ok) changed()
        return ok
    }

    /** Whether an agent is blocked in [take] right now. */
    fun listening(): Boolean = lock.withLock { waiters > 0 }

    /** `{listening, items:[{id, text, status, context, createdAt, replies:[{text, at}]}]}`, oldest first. */
    fun snapshot(): ObjectNode = lock.withLock {
        Json.obj {
            put("listening", waiters > 0)
            putArray("items").also { arr -> items.forEach { arr.add(toJson(it)) } }
        }
    }

    fun toJson(p: Prompt): ObjectNode = Json.obj {
        put("id", p.id)
        put("text", p.text)
        put("status", p.status)
        put("createdAt", p.createdAt)
        set<JsonNode>("context", p.context.deepCopy())
        putArray("replies").also { arr -> p.replies.forEach { r -> arr.addObject().put("text", r.text).put("at", r.at) } }
    }

    private fun changed() {
        val cb = onChange ?: return
        runCatching { cb(snapshot()) }
    }

    companion object {
        const val PENDING = "pending"
        const val TAKEN = "taken"
        const val ANSWERED = "answered"
        const val CANCELLED = "cancelled"
    }
}
