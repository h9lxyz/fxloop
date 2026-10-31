package dev.fxloop.sample

import javafx.beans.binding.Bindings
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField

class MainController {
    @FXML private lateinit var nameField: TextField
    @FXML private lateinit var categoryBox: ComboBox<String>
    @FXML private lateinit var addBtn: Button
    @FXML private lateinit var contactTable: TableView<Contact>
    @FXML private lateinit var nameCol: TableColumn<Contact, String>
    @FXML private lateinit var categoryCol: TableColumn<Contact, String>
    @FXML private lateinit var deleteBtn: Button
    @FXML private lateinit var boomBtn: Button
    @FXML private lateinit var helpBtn: Button
    @FXML private lateinit var hintLabel: Label
    @FXML private lateinit var statusLabel: Label

    private val contacts = FXCollections.observableArrayList<Contact>()

    @FXML
    fun initialize() {
        categoryBox.items.setAll("Work", "Family", "Friends")
        nameCol.setCellValueFactory { it.value.nameProperty() }
        categoryCol.setCellValueFactory { it.value.categoryProperty() }
        contactTable.items = contacts
        addBtn.disableProperty().bind(Bindings.createBooleanBinding({ nameField.text.isNullOrBlank() }, nameField.textProperty()))
        deleteBtn.disableProperty().bind(contactTable.selectionModel.selectedItemProperty().isNull)
        statusLabel.textProperty().bind(Bindings.size(contacts).asString("%d contacts"))
    }

    fun seed() {
        contacts.addAll(Contact("Ada Lovelace", "Work"), Contact("Alan Turing", "Work"), Contact("Grace Hopper", "Friends"))
    }

    @FXML
    fun addContact() {
        contacts.add(Contact(nameField.text, categoryBox.value))
        nameField.clear()
    }

    @FXML
    fun deleteContact() {
        contacts.remove(contactTable.selectionModel.selectedItem)
    }

    @FXML
    fun boom() {
        throw IllegalStateException("boom")
    }
}
