package org.lab59.fxloop.server.loop

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReloaderRebuildTest {
    private fun r(build: Boolean, sources: Boolean, compiled: Boolean) = Reloader.rebuildOnRestart(build, sources, compiled)

    @Test
    fun rejectedHotswapSkipsPrepare() {
        assertThat(r(false, true, true)).isFalse()
    }

    @Test
    fun resourceOnlyRestartSkipsPrepare() {
        assertThat(r(false, false, false)).isFalse()
    }

    @Test
    fun buildFileChangeRebuilds() {
        assertThat(r(true, true, true)).isTrue()
    }

    @Test
    fun uncompiledSourcesRebuild() {
        assertThat(r(false, true, false)).isTrue()
    }
}
