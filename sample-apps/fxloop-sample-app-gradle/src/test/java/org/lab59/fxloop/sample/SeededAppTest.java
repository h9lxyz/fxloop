package org.lab59.fxloop.sample;

import static org.assertj.core.api.Assertions.assertThat;

import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

@ExtendWith(ApplicationExtension.class)
class SeededAppTest {

    @BeforeAll
    static void seedFlag() {
        System.setProperty("fxloop.sample.args", "--seed --broken-css");
    }

    @AfterAll
    static void clearFlag() {
        System.clearProperty("fxloop.sample.args");
    }

    @Start
    void start(Stage stage) throws Exception {
        new ContactApp().start(stage);
    }

    @Test
    void seedGivesThreeContacts(FxRobot robot) {
        assertThat(robot.lookup("#statusLabel").queryAs(Label.class).getText()).isEqualTo("3 contacts");
        assertThat(robot.lookup("#contactTable").queryTableView().getItems()).hasSize(3);
        assertThat(robot.lookup("#hintLabel").queryAs(Label.class).getTextFill().toString()).isEqualTo("0xffffffff");
    }
}
