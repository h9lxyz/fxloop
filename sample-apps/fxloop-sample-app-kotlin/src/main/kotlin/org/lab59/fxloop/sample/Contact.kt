package org.lab59.fxloop.sample

import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty

class Contact(name: String?, category: String?) {
    private val name: StringProperty = SimpleStringProperty(name)
    private val category: StringProperty = SimpleStringProperty(category)

    fun nameProperty(): StringProperty = name
    fun categoryProperty(): StringProperty = category
    fun getName(): String? = name.get()
    fun getCategory(): String? = category.get()
}
