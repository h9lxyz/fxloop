package dev.fxloop.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavaFxModulesTest {

    @Test
    void derivesModuleNames() {
        assertThat(JavaFxModules.moduleName("javafx-controls")).isEqualTo("javafx.controls");
        assertThat(JavaFxModules.moduleName("javafx-base")).isEqualTo("javafx.base");
        assertThat(JavaFxModules.moduleName("guava")).isNull();
        assertThat(JavaFxModules.moduleName("javafx-")).isNull();
    }

    @Test
    void collapsesPlatformClassifierDuplicates() {
        List<String> ids = List.of(
                "javafx-controls", "javafx-controls", "javafx-graphics", "javafx-graphics", "javafx-base", "javafx-base");
        assertThat(JavaFxModules.moduleNames(ids)).containsExactly("javafx.controls", "javafx.graphics", "javafx.base");
    }

    @Test
    void parsesModuleInfoName() {
        assertThat(PrepareMojo.parseModuleName("/* module fake { */\n// module x {\nopen module my.app {\n requires a; }"))
                .isEqualTo("my.app");
    }
}
