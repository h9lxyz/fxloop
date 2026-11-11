package org.lab59.fxloop.showcase

import java.util.ResourceBundle
import javafx.geometry.Insets
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

class CustomersView(private val store: TicketStore, private val bundle: ResourceBundle) {

    val node: Parent = build()

    private fun build(): Parent {
        val title = Label(bundle["customers.title"]).apply {
            id = "customersTitle"
            style = "-fx-font-family: Arial; -fx-font-size: 22px; -fx-text-fill: #222222;"
        }

        val filter = TextField().apply {
            id = "customerFilter"
            promptText = bundle["customers.filter"]
        }
        val addBtn = Button(bundle["customers.add"]).apply {
            id = "addCustomerBtn"
            style = "-fx-background-color: #3b7cff; -fx-text-fill: white; -fx-background-radius: 0; -fx-font-family: Arial; -fx-padding: 10 22;"
            setOnAction { }
        }
        val importBtn = Button(bundle["customers.import"]).apply {
            id = "importCustomersBtn"
            style = "-fx-font-size: 15px;"
        }
        val toolbar = HBox(10.0, filter, addBtn, importBtn)

        val list = ListView(store.customers).apply {
            id = "customerList"
            prefHeight = 260.0
            setCellFactory {
                object : ListCell<Customer>() {
                    override fun updateItem(item: Customer?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else "${item.name} — ${item.company}"
                    }
                }
            }
        }

        val details = GridPane().apply {
            id = "customerDetails"
            hgap = 20.0
            vgap = 13.0
            padding = Insets(13.0)
            style = "-fx-background-color: #fafafa; -fx-border-color: #e3e3e3;"
            columnConstraints.addAll(
                javafx.scene.layout.ColumnConstraints(70.0),
                javafx.scene.layout.ColumnConstraints(140.0),
            )
        }
        val email = Label()
        val plan = Label()
        val company = Label()
        details.addRow(0, Label(bundle["customers.company"]), company)
        details.addRow(1, Label(bundle["customers.email"]), email)
        details.addRow(2, Label(bundle["customers.plan"]), plan)
        list.selectionModel.selectedItemProperty().addListener { _, _, c ->
            company.text = c?.company
            email.text = c?.email
            plan.text = c?.plan
        }

        return VBox(13.0, title, toolbar, list, details).apply {
            id = "customersView"
            padding = Insets(20.0)
        }
    }
}
