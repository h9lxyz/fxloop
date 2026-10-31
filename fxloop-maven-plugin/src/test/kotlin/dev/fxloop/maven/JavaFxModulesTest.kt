package dev.fxloop.maven

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JavaFxModulesTest {

    @Test
    fun derivesModuleNames() {
        assertThat(JavaFxModules.moduleName("javafx-controls")).isEqualTo("javafx.controls")
        assertThat(JavaFxModules.moduleName("javafx-base")).isEqualTo("javafx.base")
        assertThat(JavaFxModules.moduleName("guava")).isNull()
        assertThat(JavaFxModules.moduleName("javafx-")).isNull()
    }

    @Test
    fun collapsesPlatformClassifierDuplicates() {
        val ids = listOf(
            "javafx-controls", "javafx-controls", "javafx-graphics", "javafx-graphics", "javafx-base", "javafx-base",
        )
        assertThat(JavaFxModules.moduleNames(ids)).containsExactly("javafx.controls", "javafx.graphics", "javafx.base")
    }

    @Test
    fun parsesModuleInfoName() {
        assertThat(PrepareMojo.parseModuleName("/* module fake { */\n// module x {\nopen module my.app {\n requires a; }"))
            .isEqualTo("my.app")
    }
}
