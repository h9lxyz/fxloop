package org.lab59.fxloop.server

import org.slf4j.LoggerFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/** Signals [onEof] once when the wrapped stream ends or fails (the MCP client closed stdio). */
class EofAwareInputStream(input: InputStream, private val onEof: () -> Unit) : FilterInputStream(input) {
    @Volatile private var signalled = false

    private fun eof() {
        if (!signalled) {
            signalled = true
            onEof()
        }
    }

    override fun read(): Int = guard { super.read() }.also { if (it < 0) eof() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = guard { super.read(b, off, len) }.also { if (it < 0) eof() }

    private inline fun guard(block: () -> Int): Int = try {
        block()
    } catch (e: IOException) {
        eof()
        throw e
    }
}

/** Fires [onParentGone] when the process that started the server dies (e.g. the MCP client host crashed). */
object ParentWatchdog {
    private val log = LoggerFactory.getLogger(ParentWatchdog::class.java)

    fun start(onParentGone: () -> Unit) {
        val parent = ProcessHandle.current().parent().orElse(null) ?: return
        thread(isDaemon = true, name = "fxloop-parent-watchdog") {
            while (parent.isAlive) Thread.sleep(2000)
            log.info("parent process {} is gone", parent.pid())
            onParentGone()
        }
    }
}
