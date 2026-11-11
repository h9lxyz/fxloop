package org.lab59.fxloop.sample

import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start

@ExtendWith(ApplicationExtension::class)
class ContactAppTest {

    @Start
    fun start(stage: Stage) = ContactApp().start(stage)

    @Test
    fun addDisabledWhenNameEmpty(robot: FxRobot) {
        assertThat(robot.lookup("#addBtn").queryAs(Button::class.java).isDisabled).isTrue()
        assertThat(robot.lookup("#statusLabel").queryAs(Label::class.java).text).isEqualTo("0 contacts")
    }

    @Test
    fun addingContactUpdatesTableAndStatus(robot: FxRobot) {
        robot.clickOn("#nameField").write("Linus")
        assertThat(robot.lookup("#addBtn").queryAs(Button::class.java).isDisabled).isFalse()
        robot.clickOn("#addBtn")

        assertThat(robot.lookup("#contactTable").queryTableView<Contact>().items).hasSize(1)
        assertThat(robot.lookup("#statusLabel").queryAs(Label::class.java).text).isEqualTo("1 contacts")
        assertThat(robot.lookup("#nameField").queryTextInputControl().text).isEmpty()
    }

    @Test
    fun categoryItemsAreFixed(robot: FxRobot) {
        val box = robot.lookup("#categoryBox").queryComboBox<String>()
        assertThat(box.items).containsExactly("Work", "Family", "Friends")
        assertThat(box.value).isNull()
    }
}
