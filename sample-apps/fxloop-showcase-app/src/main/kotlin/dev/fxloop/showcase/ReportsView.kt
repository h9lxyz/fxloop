package dev.fxloop.showcase

import java.util.ResourceBundle
import java.util.logging.Logger
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.Parent
import javafx.scene.chart.PieChart
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlin.concurrent.thread

class ReportsView(
    private val store: TicketStore,
    private val bundle: ResourceBundle,
    private val statusLabel: Label,
) {
    private val log = Logger.getLogger("dev.fxloop.showcase.ReportsView")

    val node: Parent = build()

    private fun build(): Parent {
        val title = Label(bundle["reports.title"]).apply {
            id = "reportsTitle"
            styleClass += "section-title"
        }

        val chart = PieChart(
            FXCollections.observableArrayList(
                Status.values().map { s ->
                    PieChart.Data(bundle["status.${s.name.lowercase()}"], store.tickets.count { it.statusProperty.get() == s }.toDouble())
                },
            ),
        ).apply {
            id = "statusChart"
            this.title = bundle["reports.byStatus"]
            prefWidth = 300.0
            prefHeight = 300.0
            maxWidth = 300.0
            maxHeight = 300.0
        }

        val satisfaction = ProgressBar(0.87).apply {
            id = "satisfactionBar"
            prefWidth = 200.0
        }
        val satisfactionValue = Label("87%").apply {
            id = "satisfactionValue"
            styleClass += "kpi-value"
        }
        val satisfactionRow = StackPane(satisfaction, satisfactionValue).apply { id = "satisfactionRow"; maxWidth = 200.0 }

        val responseTime = Label(bundle["reports.firstResponse"]).apply {
            id = "firstResponseLabel"
            styleClass += "kpi-caption"
        }

        val generateBtn = Button(bundle["reports.generate"]).apply {
            id = "generateReportBtn"
            styleClass += "primary-button"
        }
        generateBtn.setOnAction {
            statusLabel.text = bundle["reports.generating"]
            thread(name = "report-generator") {
                Thread.sleep(800)
                val open = store.tickets.count { it.statusProperty.get() == Status.OPEN }
                statusLabel.text = String.format(bundle["reports.done"], open)
                log.info("Report generated")
                Alert(Alert.AlertType.INFORMATION, String.format(bundle["reports.done"], open)).show()
            }
        }

        val csvBtn = Button(bundle["reports.exportCsv"]).apply {
            id = "exportCsvBtn"
            style = "-fx-background-color: #2e9d5b; -fx-text-fill: white; -fx-background-radius: 14; -fx-font-weight: bold;"
        }

        return VBox(
            8.0, title, chart,
            Label(bundle["reports.satisfaction"]).apply { styleClass += "kpi-caption" },
            satisfactionRow, responseTime, HBox(8.0, generateBtn, csvBtn),
        ).apply {
            id = "reportsView"
            padding = Insets(16.0)
        }
    }
}
