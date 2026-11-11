package org.lab59.fxloop.showcase

import javafx.scene.control.TableView
import javafx.stage.Stage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start

@ExtendWith(ApplicationExtension::class)
class TicketDeskSmokeTest {

    @Start
    fun start(stage: Stage) {
        TicketDeskApp().start(stage)
    }

    @Test
    fun `main window shows seeded tickets`(robot: FxRobot) {
        val table = robot.lookup("#ticketTable").queryAs(TableView::class.java)
        assertThat(table.items).hasSize(14)
    }
}
