package org.lab59.fxloop.showcase

import java.time.LocalDateTime
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList

enum class Priority { LOW, NORMAL, HIGH, URGENT }

enum class Status { OPEN, PENDING, SOLVED, CLOSED }

class Ticket(
    val id: Int,
    subject: String,
    customer: String,
    priority: Priority,
    status: Status,
    assignee: String,
    description: String,
    updated: LocalDateTime,
) {
    val subjectProperty = SimpleStringProperty(subject)
    val customerProperty = SimpleStringProperty(customer)
    val priorityProperty = SimpleObjectProperty(priority)
    val statusProperty = SimpleObjectProperty(status)
    val assigneeProperty = SimpleStringProperty(assignee)
    val descriptionProperty = SimpleStringProperty(description)
    val updatedProperty = SimpleObjectProperty(updated)
}

data class Customer(val name: String, val company: String, val email: String, val plan: String)

class TicketStore {
    val tickets: ObservableList<Ticket> = FXCollections.observableArrayList()
    val customers: ObservableList<Customer> = FXCollections.observableArrayList()
    val currentUser = "Alex Morgan"

    fun nextId(): Int = (tickets.maxOfOrNull { it.id } ?: 1000) + 1

    fun seed() {
        customers.setAll(
            Customer("Priya Raman", "Northwind Logistics", "priya@northwind.example", "Enterprise"),
            Customer("Jonas Becker", "Becker & Söhne Maschinenbau", "j.becker@becker-soehne.example", "Business"),
            Customer("Mei Tanaka", "Sakura Analytics", "mei@sakura.example", "Starter"),
            Customer("Carlos Mendes", "Atlântico Shipping", "carlos@atlantico.example", "Enterprise"),
            Customer("Fatima Haddad", "Cedar Health Clinics", "f.haddad@cedarhealth.example", "Business"),
            Customer("Oliver Grant", "Grant Family Bakery", "oliver@grantbakery.example", "Starter"),
        )
        val now = LocalDateTime.of(2026, 10, 30, 16, 45)
        tickets.setAll(
            Ticket(1042, "Cannot export invoices to PDF since the last update", "Northwind Logistics", Priority.HIGH, Status.OPEN, "Alex Morgan",
                "Since version 4.2 the PDF export button spins forever. Happens for all invoices created after Oct 1.", now.minusHours(2)),
            Ticket(1043, "Password reset email never arrives", "Sakura Analytics", Priority.URGENT, Status.OPEN, "",
                "Three users report no reset email. Checked spam folders. SPF record looks fine on their side.", now.minusHours(5)),
            Ticket(1044, "Feature request: dark mode", "Grant Family Bakery", Priority.LOW, Status.PENDING, "Sam Lee",
                "Would love a dark theme for the evening shift.", now.minusDays(1)),
            Ticket(1045, "Umlauts broken in CSV import (ä, ö, ü show as ?)", "Becker & Söhne Maschinenbau", Priority.NORMAL, Status.OPEN, "Alex Morgan",
                "CSV exported from Excel (Windows-1252). Import shows replacement characters.", now.minusDays(1).minusHours(3)),
            Ticket(1046, "Shipment tracking widget shows wrong time zone", "Atlântico Shipping", Priority.NORMAL, Status.SOLVED, "Sam Lee",
                "Times are shown in UTC instead of the warehouse local time.", now.minusDays(2)),
            Ticket(1047, "Invoice #88213 charged twice", "Northwind Logistics", Priority.URGENT, Status.PENDING, "Alex Morgan",
                "Customer was charged twice for the October invoice. Refund requested.", now.minusDays(2).minusHours(4)),
            Ticket(1048, "How do I add a second admin?", "Cedar Health Clinics", Priority.LOW, Status.SOLVED, "Robin Fox",
                "Answered with KB article 'Managing roles'.", now.minusDays(3)),
            Ticket(1049, "API rate limit too low for nightly sync", "Sakura Analytics", Priority.HIGH, Status.OPEN, "",
                "Nightly sync of 40k records hits 429 after ~5 minutes.", now.minusDays(3).minusHours(6)),
            Ticket(1050, "Mobile app crashes when opening attachments larger than 10 MB on Android 14", "Cedar Health Clinics", Priority.HIGH, Status.OPEN, "Robin Fox",
                "Reproducible on Pixel 8 with a 12 MB PDF.", now.minusDays(4)),
            Ticket(1051, "Request to change billing address", "Grant Family Bakery", Priority.LOW, Status.CLOSED, "Sam Lee",
                "Updated.", now.minusDays(6)),
            Ticket(1052, "SSO login loops back to the sign-in page", "Atlântico Shipping", Priority.URGENT, Status.OPEN, "Alex Morgan",
                "Azure AD SSO. Loop started after their IdP certificate rotation.", now.minusDays(6).minusHours(2)),
            Ticket(1053, "Report totals differ from dashboard", "Becker & Söhne Maschinenbau", Priority.NORMAL, Status.PENDING, "",
                "Monthly report shows 1,204 orders, dashboard shows 1,198.", now.minusDays(8)),
            Ticket(1054, "Add VAT ID to invoices", "Becker & Söhne Maschinenbau", Priority.NORMAL, Status.CLOSED, "Robin Fox",
                "Done in 4.1.", now.minusDays(12)),
            Ticket(1055, "Slow search in customer list", "Northwind Logistics", Priority.NORMAL, Status.OPEN, "Sam Lee",
                "Searching 'north' takes ~4 s.", now.minusDays(13)),
        )
    }
}
