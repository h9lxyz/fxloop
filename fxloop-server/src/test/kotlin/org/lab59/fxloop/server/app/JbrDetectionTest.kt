package org.lab59.fxloop.server.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JbrDetectionTest {
    @Test
    fun jetBrainsRuntimeIsDetected(@TempDir home: Path) {
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"JetBrains s.r.o.\"\nJAVA_VERSION=\"21.0.5\"\n")
        assertThat(AppSession.isJbr(home)).isTrue()
    }

    @Test
    fun otherVendorIsNot(@TempDir home: Path) {
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"25\"\n")
        assertThat(AppSession.isJbr(home)).isFalse()
    }

    @Test
    fun missingReleaseFileIsNot(@TempDir home: Path) {
        assertThat(AppSession.isJbr(home)).isFalse()
    }
}
