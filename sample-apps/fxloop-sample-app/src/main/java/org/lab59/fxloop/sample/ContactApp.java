package org.lab59.fxloop.sample;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ContactApp extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws IOException {
        List<String> args = new ArrayList<>(getParameters() == null ? List.of() : getParameters().getRaw());
        String extra = System.getProperty("fxloop.sample.args", "").trim();
        if (!extra.isEmpty()) {
            args.addAll(List.of(extra.split("\\s+")));
        }

        FXMLLoader loader = new FXMLLoader(ContactApp.class.getResource("main-view.fxml"));
        Parent root = loader.load();
        MainController controller = loader.getController();
        if (args.contains("--seed")) {
            controller.seed();
        }

        Scene scene = new Scene(root, 640, 480);
        scene.getStylesheets().add(ContactApp.class.getResource("app.css").toExternalForm());
        if (args.contains("--broken-css")) {
            scene.getStylesheets().add(ContactApp.class.getResource("broken.css").toExternalForm());
        }
        stage.setTitle("FXLoop Sample — Contacts");
        stage.setScene(scene);
        stage.show();
    }
}
