package dev.fxloop.sample;

import static org.assertj.core.api.Assertions.assertThat;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

@ExtendWith(ApplicationExtension.class)
class ContactAppTest {

    @Start
    void start(Stage stage) throws Exception {
        new ContactApp().start(stage);
    }

    @Test
    void addDisabledWhenNameEmpty(FxRobot robot) {
        assertThat(robot.lookup("#addBtn").queryAs(Button.class).isDisabled()).isTrue();
        assertThat(robot.lookup("#statusLabel").queryAs(Label.class).getText()).isEqualTo("0 contacts");
    }

    @Test
    void addingContactUpdatesTableAndStatus(FxRobot robot) {
        robot.clickOn("#nameField").write("Linus");
        assertThat(robot.lookup("#addBtn").queryAs(Button.class).isDisabled()).isFalse();
        robot.clickOn("#addBtn");

        assertThat(robot.lookup("#contactTable").queryTableView().getItems()).hasSize(1);
        assertThat(robot.lookup("#statusLabel").queryAs(Label.class).getText()).isEqualTo("1 contacts");
        assertThat(robot.lookup("#nameField").queryTextInputControl().getText()).isEmpty();
    }

    @Test
    void categoryItemsAreFixed(FxRobot robot) {
        var box = robot.lookup("#categoryBox").queryComboBox();
        assertThat(box.getItems()).containsExactly("Work", "Family", "Friends");
        assertThat(box.getValue()).isNull();
    }
}
