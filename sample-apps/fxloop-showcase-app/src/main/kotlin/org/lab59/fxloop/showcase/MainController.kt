package org.lab59.fxloop.showcase

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ResourceBundle
import java.util.logging.Logger
import javafx.collections.transformation.FilteredList
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.FileChooser

class MainController(private val store: TicketStore) {

    private val log = Logger.getLogger("org.lab59.fxloop.showcase.MainController")
    private val updatedFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm")

    @FXML lateinit var resources: ResourceBundle
    @FXML lateinit var root: BorderPane
    @FXML lateinit var searchField: TextField
    @FXML lateinit var contentHost: StackPane
    @FXML lateinit var ticketsView: AnchorPane
    @FXML lateinit var ticketTable: TableView<Ticket>
    @FXML lateinit var idCol: TableColumn<Ticket, Int>
    @FXML lateinit var subjectCol: TableColumn<Ticket, String>
    @FXML lateinit var customerCol: TableColumn<Ticket, String>
    @FXML lateinit var priorityCol: TableColumn<Ticket, Priority>
    @FXML lateinit var statusCol: TableColumn<Ticket, Status>
    @FXML lateinit var updatedCol: TableColumn<Ticket, LocalDateTime>
    @FXML lateinit var subjectField: TextField
    @FXML lateinit var customerField: TextField
    @FXML lateinit var statusCombo: ComboBox<Status>
    @FXML lateinit var priorityBox: ComboBox<Priority>
    @FXML lateinit var assigneeField: TextField
    @FXML lateinit var descriptionArea: TextArea
    @FXML lateinit var assignBtn: Button
    @FXML lateinit var statusLabel: Label

    private lateinit var filtered: FilteredList<Ticket>

    @FXML
    fun initialize() {
        filtered = FilteredList(store.tickets)
        ticketTable.items = filtered

        idCol.setCellValueFactory { javafx.beans.property.SimpleObjectProperty(it.value.id) }
        subjectCol.setCellValueFactory { it.value.subjectProperty }
        customerCol.setCellValueFactory { it.value.customerProperty }
        priorityCol.setCellValueFactory { it.value.priorityProperty }
        statusCol.setCellValueFactory { it.value.statusProperty }
        updatedCol.setCellValueFactory { it.value.updatedProperty }

        priorityCol.setCellFactory {
            object : TableCell<Ticket, Priority>() {
                override fun updateItem(item: Priority?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = null
                    graphic = if (empty || item == null) null else Circle(5.0, priorityColor(item))
                }
            }
        }
        statusCol.setCellFactory {
            object : TableCell<Ticket, Status>() {
                override fun updateItem(item: Status?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = null
                    graphic = if (empty || item == null) null else statusChip(item)
                }
            }
        }
        updatedCol.setCellFactory {
            object : TableCell<Ticket, LocalDateTime>() {
                override fun updateItem(item: LocalDateTime?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else updatedFormat.format(item)
                }
            }
        }

        statusCombo.items.setAll(*Status.values())

        ticketTable.selectionModel.selectedItemProperty().addListener { _, _, ticket -> showTicket(ticket) }
        searchField.textProperty().addListener { _, _, query ->
            filtered.setPredicate { query.isNullOrEmpty() || it.subjectProperty.get().contains(query) }
        }
        assignBtn.disableProperty().bind(searchField.textProperty().isEmpty)

        statusLabel.text = String.format(resources["footer.count"], store.tickets.size)
    }

    private fun priorityColor(p: Priority): Color = when (p) {
        Priority.LOW -> Color.web("#9ccc9c")
        Priority.NORMAL -> Color.web("#9cb8e0")
        Priority.HIGH -> Color.web("#f2b36b")
        Priority.URGENT -> Color.web("#ee6b6b")
    }

    private fun statusChip(s: Status): Label = Label(resources["status.${s.name.lowercase()}"]).apply {
        styleClass += "status-chip"
        style = when (s) {
            Status.OPEN -> "-fx-background-color: #e6f0ff; -fx-text-fill: #8fb3ff;"
            Status.PENDING -> "-fx-background-color: #fff6e0; -fx-text-fill: #f0c36d;"
            Status.SOLVED -> "-fx-background-color: #e8f7ee; -fx-text-fill: #9fd8b4;"
            Status.CLOSED -> "-fx-background-color: #f0f0f0; -fx-text-fill: #c8c8c8;"
        }
    }

    private fun showTicket(t: Ticket?) {
        if (t == null) {
            subjectField.clear(); customerField.clear(); assigneeField.clear(); descriptionArea.clear()
            statusCombo.value = null
            return
        }
        subjectField.text = t.subjectProperty.get()
        customerField.text = t.customerProperty.get()
        statusCombo.value = t.statusProperty.get()
        assigneeField.text = t.assigneeProperty.get()
        descriptionArea.text = t.descriptionProperty.get()
    }

    @FXML
    fun onSave() {
        val t = ticketTable.selectionModel.selectedItem ?: return
        t.subjectProperty.set(subjectField.text)
        t.statusProperty.set(statusCombo.value)
        t.assigneeProperty.set(assigneeField.text)
        t.descriptionProperty.set(descriptionArea.text)
        t.updatedProperty.set(LocalDateTime.now())
        log.info("Saved ticket ${t.id}")
    }

    @FXML
    fun onDelete() {
        val index = ticketTable.selectionModel.selectedIndex
        val removed = store.tickets.removeAt(maxOf(index, 0))
        statusLabel.text = String.format(resources["footer.count"], store.tickets.size)
        log.info("Deleted ticket ${removed.id}")
    }

    @FXML
    fun onAssignToMe() {
        val t = ticketTable.selectionModel.selectedItem!!
        t.assigneeProperty.set(store.currentUser)
        assigneeField.text = store.currentUser
    }

    @FXML
    fun onClearForm() {
        subjectField.clear()
        customerField.clear()
        priorityBox.value = null
        assigneeField.clear()
        descriptionArea.clear()
    }

    @FXML
    fun onAttach() {
        val file = FileChooser().showOpenDialog(root.scene.window) ?: return
        statusLabel.text = String.format(resources["footer.attached"], file.name)
    }

    @FXML
    fun onExport() {
        statusLabel.text = resources["footer.exported"]
    }

    @FXML
    fun onRefresh() {
        val started = System.currentTimeMillis()
        Thread.sleep(2500)
        store.tickets.forEach { it.updatedProperty.set(it.updatedProperty.get()) }
        statusLabel.text = resources["footer.refreshed"]
        log.info("Refresh finished in ${System.currentTimeMillis() - started} ms")
    }

    @FXML
    fun onNewTicket() {
        NewTicketDialog(store, resources).show()
    }

    @FXML
    fun onOpenSettings() {
        SettingsWindow.open(resources)
    }

    @FXML
    fun onNav(event: ActionEvent) {
        val target = (event.source as ToggleButton).userData as String
        val view: Node = when (target) {
            "customers" -> CustomersView(store, resources).node
            "reports" -> ReportsView(store, resources, statusLabel).node
            "knowledge" -> KnowledgeBaseView(resources).node
            else -> ticketsView
        }
        contentHost.children.setAll(view)
    }
}
