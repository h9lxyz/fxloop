package dev.fxloop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Persists stage geometry per stage index to {@code state.json} and restores it on next start (PRD 02 §5). */
final class GeometryStore {
    private final Path file;
    private ObjectNode state;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(400));

    GeometryStore(Path file) {
        this.file = file;
    }

    /** FX thread. */
    void install() {
        state = load();
        debounce.setOnFinished(e -> save());
        for (Window w : Window.getWindows()) {
            attach(w);
        }
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) {
                c.getAddedSubList().forEach(this::attach);
            }
        });
    }

    private void attach(Window w) {
        if (!(w instanceof Stage stage) || stage.getProperties().containsKey("fxloop.geometry")) {
            return;
        }
        stage.getProperties().put("fxloop.geometry", Boolean.TRUE);
        restore(stage);
        stage.xProperty().addListener(o -> debounce.playFromStart());
        stage.yProperty().addListener(o -> debounce.playFromStart());
        stage.widthProperty().addListener(o -> debounce.playFromStart());
        stage.heightProperty().addListener(o -> debounce.playFromStart());
        stage.maximizedProperty().addListener(o -> debounce.playFromStart());
    }

    private int stageIndex(Stage stage) {
        int i = 0;
        for (Window w : Window.getWindows()) {
            if (w == stage) {
                return i;
            }
            if (w instanceof Stage) {
                i++;
            }
        }
        return i;
    }

    private void restore(Stage stage) {
        JsonNode g = state.path("stages").path(String.valueOf(stageIndex(stage)));
        if (g.isMissingNode()) {
            return;
        }
        double x = g.path("x").asDouble();
        double y = g.path("y").asDouble();
        List<Screen> screens = Screen.getScreensForRectangle(new Rectangle2D(x, y, 40, 40));
        if (screens.isEmpty()) {
            return; // monitor layout changed: keep default placement
        }
        stage.setX(x);
        stage.setY(y);
        if (g.path("w").asDouble() > 0) {
            stage.setWidth(g.path("w").asDouble());
            stage.setHeight(g.path("h").asDouble());
        }
        stage.setMaximized(g.path("maximized").asBoolean(false));
    }

    private void save() {
        ObjectNode stages = state.putObject("stages");
        int i = 0;
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s.isShowing()) {
                ObjectNode g = stages.putObject(String.valueOf(i++));
                g.put("x", s.getX()).put("y", s.getY()).put("w", s.getWidth()).put("h", s.getHeight());
                g.put("maximized", s.isMaximized());
                List<Screen> screens = Screen.getScreensForRectangle(s.getX(), s.getY(), 1, 1);
                g.put("screenId", screens.isEmpty() ? 0 : Screen.getScreens().indexOf(screens.get(0)));
            }
        }
        ObjectNode snapshot = state.deepCopy();
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, Json.pretty(snapshot));
            } catch (IOException e) {
                AgentLog.warn("could not save " + file, e);
            }
        }, "fxloop-geometry-save");
        t.setDaemon(true);
        t.start();
    }

    private ObjectNode load() {
        try {
            if (Files.exists(file)) {
                return (ObjectNode) Json.MAPPER.readTree(Files.readString(file));
            }
        } catch (IOException | ClassCastException e) {
            AgentLog.warn("ignoring unreadable " + file, e);
        }
        return Json.object();
    }
}
