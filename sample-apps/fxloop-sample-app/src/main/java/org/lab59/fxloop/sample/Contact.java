package org.lab59.fxloop.sample;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class Contact {
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty category = new SimpleStringProperty();

    public Contact(String name, String category) {
        this.name.set(name);
        this.category.set(category);
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty categoryProperty() { return category; }
    public String getName() { return name.get(); }
    public String getCategory() { return category.get(); }
}
