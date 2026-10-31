package dev.fxloop.server.see

import dev.fxloop.protocol.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PromptsTest {
    @Test
    fun `a waiter gets the next prompt once and sees listening`() {
        val q = Prompts()
        val waiting = CompletableFuture.supplyAsync { q.take(10_000) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!q.listening() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertThat(q.listening()).isTrue()
        q.submit("why is this grey?", Json.obj { put("path", "Stage[0]//Button#ok") })
        val p = waiting.get(5, TimeUnit.SECONDS)!!
        assertThat(p.text).isEqualTo("why is this grey?")
        assertThat(p.status).isEqualTo(Prompts.TAKEN)
        assertThat(q.listening()).isFalse()
        assertThat(q.take(20)).isNull()
    }

    @Test
    fun `replies, cancel and the history cap`() {
        val changes = ArrayList<String>()
        val q = Prompts(keep = 2)
        q.onChange = { changes += it["items"].joinToString(",") { i -> i["status"].asText() } }
        val a = q.submit("a", Json.obj())
        q.reply(a.id, "working on it", false)
        assertThat(a.status).isEqualTo(Prompts.PENDING)
        q.reply(a.id, "done", true)
        assertThat(a.status).isEqualTo(Prompts.ANSWERED)
        assertThat(q.cancel(a.id)).isFalse()
        val b = q.submit("b", Json.obj())
        assertThat(q.cancel(b.id)).isTrue()
        q.submit("c", Json.obj())
        assertThat(q.snapshot()["items"].map { it["text"].asText() }).containsExactly("b", "c")
        assertThat(q.take(10)!!.text).isEqualTo("c")
        assertThat(changes).isNotEmpty
    }
}
