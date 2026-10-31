package dev.fxloop.sample;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

public class MainController {
    @FXML private TextField nameField;
    @FXML private ComboBox<String> categoryBox;
    @FXML private Button addBtn;
    @FXML private TableView<Contact> contactTable;
    @FXML private TableColumn<Contact, String> nameCol;
    @FXML private TableColumn<Contact, String> categoryCol;
    @FXML private Button deleteBtn;
    @FXML private Button boomBtn;
    @FXML private Button helpBtn;
    @FXML private Label hintLabel;
    @FXML private Label statusLabel;

    private final ObservableList<Contact> contacts = FXCollections.observableArrayList();

    @FXML
    void initialize() {
        categoryBox.getItems().setAll("Work", "Family", "Friends");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        categoryCol.setCellValueFactory(c -> c.getValue().categoryProperty());
        contactTable.setItems(contacts);
        addBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> nameField.getText() == null || nameField.getText().isBlank(), nameField.textProperty()));
        deleteBtn.disableProperty().bind(contactTable.getSelectionModel().selectedItemProperty().isNull());
        statusLabel.textProperty().bind(Bindings.size(contacts).asString("%d contacts"));
        if (Boolean.getBoolean("fxloop.sample.seed")) {
            seed(); // data loaded by the controller itself, as in apps that fill their views in initialize() (live-dev E2)
        }
    }

    void seed() {
        contacts.addAll(new Contact("Ada Lovelace", "Work"), new Contact("Alan Turing", "Work"),
                new Contact("Grace Hopper", "Friends"));
    }

    @FXML
    void addContact() {
        contacts.add(new Contact(nameField.getText(), categoryBox.getValue()));
        nameField.clear();
    }

    @FXML
    void deleteContact() {
        contacts.remove(contactTable.getSelectionModel().getSelectedItem());
    }

    @FXML
    void boom() {
        throw new IllegalStateException("boom");
    }
}
