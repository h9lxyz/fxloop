package dev.fxloop.showcase

import java.time.LocalDateTime
import java.util.ResourceBundle
import java.util.logging.Logger
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.stage.Stage

class NewTicketDialog(private val store: TicketStore, private val bundle: ResourceBundle) {

    private val log = Logger.getLogger("dev.fxloop.showcase.NewTicketDialog")

    fun show() {
        val stage = Stage()
        stage.title = bundle["newTicket.title"]

        val subject = TextField().apply { id = "newSubjectField" }
        val customer = TextField().apply { id = "newCustomerField" }
        val priority = ComboBox<Priority>().apply {
            id = "newPriorityCombo"
            items.setAll(*Priority.values())
        }
        val description = TextArea().apply {
            id = "newDescriptionArea"
            prefRowCount = 6
            isWrapText = true
        }

        val grid = GridPane().apply {
            id = "newTicketForm"
            hgap = 4.0
            vgap = 4.0
            padding = Insets(8.0)
        }
        grid.add(Label(bundle["field.subject"]), 0, 0)
        grid.add(subject, 1, 0)
        grid.add(Label(bundle["field.customer"]), 0, 2)
        grid.add(customer, 1, 2)
        grid.add(Label(bundle["field.priority"]), 0, 1)
        grid.add(priority, 1, 1)
        grid.add(Label(bundle["field.description"]), 0, 3)
        grid.add(description, 1, 3)

        val create = Button(bundle["action.create"]).apply { id = "createTicketBtn" }
        val cancel = Button(bundle["action.cancel"]).apply { id = "cancelTicketBtn" }
        grid.add(HBox(4.0, cancel, create), 1, 4)

        cancel.setOnAction { stage.close() }
        create.setOnAction {
            if (subject.text.isNullOrBlank()) {
                Alert(Alert.AlertType.ERROR, bundle["validation.subjectRequired"]).showAndWait()
                return@setOnAction
            }
            if (customer.text.isNullOrBlank()) {
                Alert(Alert.AlertType.ERROR, bundle["validation.customerRequired"]).showAndWait()
                return@setOnAction
            }
            if (subject.text.length > 60) {
                Alert(Alert.AlertType.ERROR, bundle["validation.subjectTooLong"]).showAndWait()
                return@setOnAction
            }
            val ticket = Ticket(
                store.nextId(), subject.text, customer.text, priority.value ?: Priority.NORMAL, Status.OPEN,
                "", description.text ?: "", LocalDateTime.now(),
            )
            store.tickets.add(ticket)
            log.info("Created ticket ${ticket.id}")
            stage.close()
        }

        stage.scene = Scene(grid, 320.0, 240.0)
        stage.isResizable = false
        stage.show()
    }
}
