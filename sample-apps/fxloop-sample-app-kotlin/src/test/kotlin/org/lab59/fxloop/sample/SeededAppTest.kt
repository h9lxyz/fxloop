package org.lab59.fxloop.sample

import javafx.scene.control.Label
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start

@ExtendWith(ApplicationExtension::class)
class SeededAppTest {

    companion object {
        @JvmStatic @BeforeAll
        fun seedFlag() { System.setProperty("fxloop.sample.args", "--seed --broken-css") }

        @JvmStatic @AfterAll
        fun clearFlag() { System.clearProperty("fxloop.sample.args") }
    }

    @Start
    fun start(stage: Stage) = ContactApp().start(stage)

    @Test
    fun seedGivesThreeContacts(robot: FxRobot) {
        assertThat(robot.lookup("#statusLabel").queryAs(Label::class.java).text).isEqualTo("3 contacts")
        assertThat(robot.lookup("#contactTable").queryTableView<Contact>().items).hasSize(3)
        assertThat(robot.lookup("#hintLabel").queryAs(Label::class.java).textFill.toString()).isEqualTo("0xffffffff")
    }
}
