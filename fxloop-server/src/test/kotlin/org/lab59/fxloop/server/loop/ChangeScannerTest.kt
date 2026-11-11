package org.lab59.fxloop.server.loop

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ChangeScannerTest {
    @Test
    fun `detects modified, created and deleted files since checkpoint`(@TempDir dir: Path) {
        val a = Files.writeString(dir.resolve("a.css"), "a")
        val b = Files.writeString(dir.resolve("b.java"), "b")
        val s = ChangeScanner()
        s.checkpoint(listOf(dir))
        assertThat(s.changes(listOf(dir))).isEmpty()
        a.toFile().setLastModified(System.currentTimeMillis() + 5000)
        Files.writeString(dir.resolve("c.fxml"), "c")
        Files.delete(b)
        val changes = s.changes(listOf(dir)).associate { it.file.fileName.toString() to it.deleted }
        assertThat(changes).containsEntry("a.css", false).containsEntry("c.fxml", false).containsEntry("b.java", true)
    }

    @Test
    fun `unchanged is true only for a file still at its checkpointed mtime`(@TempDir dir: Path) {
        val a = Files.writeString(dir.resolve("a.kt"), "a")
        val s = ChangeScanner()
        s.checkpoint(listOf(dir))
        assertThat(s.unchanged(a)).isTrue()
        assertThat(s.unchanged(dir.resolve("new.kt"))).isFalse()
        a.toFile().setLastModified(System.currentTimeMillis() + 5000)
        assertThat(s.unchanged(a)).isFalse()
    }
}
