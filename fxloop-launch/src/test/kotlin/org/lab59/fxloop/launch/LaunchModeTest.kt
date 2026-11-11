package org.lab59.fxloop.launch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LaunchModeTest {

    @Test
    fun autoFollowsModuleInfo() {
        assertThat(LaunchMode.resolve(null, true)).isEqualTo("modulepath")
        assertThat(LaunchMode.resolve("auto", false)).isEqualTo("classpath")
        assertThat(LaunchMode.resolve(" AUTO ", true)).isEqualTo("modulepath")
    }

    @Test
    fun explicitModesWinOverModuleInfo() {
        assertThat(LaunchMode.resolve("classpath", true)).isEqualTo("classpath")
        assertThat(LaunchMode.resolve("ModulePath", false)).isEqualTo("modulepath")
    }

    @Test
    fun invalidModeFails() {
        assertThatThrownBy { LaunchMode.resolve("bogus", false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Invalid fxloop.launchMode 'bogus' (expected auto|classpath|modulepath)")
    }

    @Test
    fun parsesModuleInfoName() {
        assertThat(LaunchMode.parseModuleName("/* module fake { */\n// module x {\nopen module my.app {\n requires a; }"))
            .isEqualTo("my.app")
        assertThat(LaunchMode.parseModuleName("no module here")).isNull()
    }
}
