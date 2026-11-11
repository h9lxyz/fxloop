package org.lab59.fxloop.gauntlet;

import static org.assertj.core.api.Assertions.assertThat;

import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

@ExtendWith(ApplicationExtension.class)
class GauntletSmokeTest {

    @Start
    void start(Stage stage) throws Exception {
        new GauntletApp().start(stage);
    }

    @Test
    void idsExist(FxRobot robot) {
        assertThat(robot.lookup("#tabs").queryAs(TabPane.class)).isNotNull();
        assertThat(robot.lookup("#alertBtn").queryAs(Button.class)).isNotNull();
        TableView<?> table = robot.lookup("#bigTable").queryAs(TableView.class);
        assertThat(table.getItems()).hasSize(100_000);
    }

    @Test
    void alertShowsExtraWindow(FxRobot robot) {
        int before = Window.getWindows().size();
        robot.clickOn("#alertBtn");
        robot.interact(() -> { });
        assertThat(Window.getWindows().size()).isGreaterThan(before);
    }
}
