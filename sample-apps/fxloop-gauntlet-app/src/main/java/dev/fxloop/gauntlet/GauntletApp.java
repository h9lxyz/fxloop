package dev.fxloop.gauntlet;

import java.util.ArrayList;
import java.util.List;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.scene.web.WebView;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import javafx.scene.control.OverrunStyle;

/** Robustness/perf gauntlet target. Pure Java, no FXML. */
public class GauntletApp extends Application {

    private static final int BIG_ROWS = 100_000;
    private static final int BIG_NODES = 5000;

    private Stage primary;
    private FlowPane bigPane;

    @Override
    public void start(Stage stage) {
        this.primary = stage;
        TabPane tabs = new TabPane();
        tabs.setId("tabs");
        tabs.getTabs().addAll(
                tab("Dialogs", dialogs()),
                tab("Menus", menus()),
                tab("Stages", stages()),
                tab("Scroll", scroll()),
                tab("Visual", visual()),
                tab("Web", web()),
                tab("Big", big()),
                tab("RTL", rtl(tabs)));
        for (Tab t : tabs.getTabs()) {
            t.setClosable(false);
        }
        Scene scene = new Scene(tabs, 900, 700);
        stage.setTitle("FXLoop Gauntlet");
        stage.setScene(scene);
        stage.show();

        if (hasBigArg()) {
            buildBig();
        }
    }

    private boolean hasBigArg() {
        List<String> raw = new ArrayList<>();
        if (getParameters() != null) {
            raw.addAll(getParameters().getRaw());
        }
        String prop = System.getProperty("fxloop.gauntlet.args");
        if (prop != null) {
            for (String s : prop.trim().split("\\s+")) {
                raw.add(s);
            }
        }
        return raw.contains("--big");
    }

    private static Tab tab(String name, Node content) {
        Tab t = new Tab(name, content);
        t.setId("tab" + name);
        return t;
    }

    private static Button button(String id, String text) {
        Button b = new Button(text);
        b.setId(id);
        return b;
    }

    private static Label label(String id, String text) {
        Label l = new Label(text);
        l.setId(id);
        return l;
    }

    private static VBox page(Node... nodes) {
        VBox box = new VBox(10, nodes);
        box.setPadding(new Insets(12));
        return box;
    }

    // 1. Dialogs
    private Node dialogs() {
        Label result = label("dialogResult", "none");
        Button alertBtn = button("alertBtn", "Show Alert");
        alertBtn.setOnAction(e -> {
            Alert a = new Alert(AlertType.INFORMATION);
            a.setTitle("Gauntlet Alert");
            a.setContentText("Hello from alert");
            a.show();
        });
        Button dialogBtn = button("dialogBtn", "Show Dialog");
        dialogBtn.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Gauntlet Dialog");
            d.resultProperty().addListener((o, ov, nv) -> {
                if (nv != null) {
                    result.setText(nv);
                }
            });
            d.show();
        });
        return page(alertBtn, dialogBtn, result);
    }

    // 2. Menus
    private Node menus() {
        Label menuResult = label("menuResult", "none");
        MenuBar bar = new MenuBar();
        bar.setId("menuBar");
        MenuItem mNew = new MenuItem("New");
        mNew.setId("menuNew");
        mNew.setOnAction(e -> menuResult.setText("New"));
        MenuItem mExit = new MenuItem("Exit");
        mExit.setId("menuExit");
        mExit.setOnAction(e -> menuResult.setText("Exit"));
        Menu file = new Menu("File", null, mNew, mExit);
        file.setId("menuFile");
        bar.getMenus().add(file);

        Label ctx = label("ctxTarget", "Right-click me");
        ctx.setPadding(new Insets(10));
        MenuItem copy = new MenuItem("Copy");
        copy.setId("ctxCopy");
        copy.setOnAction(e -> menuResult.setText("Copy"));
        MenuItem paste = new MenuItem("Paste");
        paste.setId("ctxPaste");
        paste.setOnAction(e -> menuResult.setText("Paste"));
        ContextMenu cm = new ContextMenu(copy, paste);
        cm.setId("ctxMenu");
        ctx.setContextMenu(cm);

        Button tip = button("tooltipBtn", "Hover me");
        tip.setTooltip(new Tooltip("Gauntlet tooltip"));
        VBox box = new VBox(10, bar, page(menuResult, ctx, tip));
        return box;
    }

    // 3. Stages
    private Node stages() {
        Button open = button("openStageBtn", "Open Stage");
        open.setOnAction(e -> {
            Button second = button("secondStageBtn", "Second");
            second.setOnAction(ev -> second.setText("Clicked"));
            Stage s = new Stage();
            s.setTitle("Gauntlet Second Stage");
            s.setScene(new Scene(new VBox(10, second), 300, 200));
            s.show();
        });
        Button popupBtn = button("popupBtn", "Show Popup");
        Popup popup = new Popup();
        popup.setAutoHide(true);
        Label pl = label("popupLabel", "Popup content");
        pl.setStyle("-fx-background-color: lightyellow; -fx-padding: 8; -fx-border-color: black;");
        popup.getContent().add(pl);
        popupBtn.setOnAction(e -> {
            var b = popupBtn.localToScreen(popupBtn.getBoundsInLocal());
            popup.show(popupBtn, b.getMinX(), b.getMaxY() + 4);
        });
        Button fs = button("fullscreenBtn", "Toggle Fullscreen");
        fs.setOnAction(e -> primary.setFullScreen(!primary.isFullScreen()));
        return page(open, popupBtn, fs);
    }

    // 4. Scroll
    private Node scroll() {
        Label deepResult = label("deepResult", "not clicked");
        VBox content = new VBox(4);
        for (int i = 0; i < 60; i++) {
            content.getChildren().add(new Label("Scroll item " + i));
        }
        Button deep = button("deepBtn", "Deep");
        deep.setOnAction(e -> deepResult.setText("deep clicked"));
        content.getChildren().add(deep);
        ScrollPane sp = new ScrollPane(content);
        sp.setId("scroller");
        sp.setPrefHeight(200);
        sp.setMinHeight(200);

        TabPane inner = new TabPane();
        inner.setId("innerTabs");
        Tab a = new Tab("A", new Label("Inner A"));
        a.setId("innerTabA");
        Button innerBtn = button("innerBtn", "Inner");
        Tab b = new Tab("B", innerBtn);
        b.setId("innerTabB");
        a.setClosable(false);
        b.setClosable(false);
        inner.getTabs().addAll(a, b);
        inner.setPrefHeight(150);
        return page(sp, deepResult, inner);
    }

    // 5. Visual
    private Node visual() {
        // Contrast: text #b0b0b0 on #ffffff. Relative luminance of #b0b0b0 (176/255=0.690 -> lin 0.434)
        // => ratio = (1.05)/(0.434+0.05) = 2.17. Too high; use #d0d0d0 (208/255=0.816 -> lin 0.631):
        // ratio = 1.05/(0.631+0.05) = 1.54. WCAG contrast ~1.54:1 (target 1.4-1.6).
        Label low = label("lowContrastLabel", "Barely visible");
        low.setTextFill(Color.web("#d0d0d0"));
        low.setBackground(new Background(new BackgroundFill(Color.WHITE, CornerRadii.EMPTY, Insets.EMPTY)));

        Button state = button("stateBtn", "Stateful");
        state.getStylesheets().add(getClass().getResource("gauntlet.css").toExternalForm());

        Label trunc = label("truncLabel",
                "This is a very long label text that will certainly be truncated with an ellipsis");
        trunc.setMaxWidth(80);
        trunc.setMinWidth(80);
        trunc.setTextOverrun(OverrunStyle.ELLIPSIS);

        Region zero = new Region();
        zero.setId("zeroRegion");
        zero.setMinSize(0, 0);
        zero.setPrefSize(0, 0);
        zero.setMaxSize(0, 0);

        Button off = button("offsceneBtn", "Offscene");
        off.setManaged(false);
        off.setLayoutX(5000);
        off.setLayoutY(10);

        return page(low, state, trunc, zero, off);
    }

    // 6. Web
    private Node web() {
        WebView wv = new WebView();
        wv.setId("web");
        wv.getEngine().loadContent("<html><body><h1 id='title'>Gauntlet Web</h1></body></html>");
        return wv;
    }

    // 7. Big
    private Node big() {
        TableView<String> table = new TableView<>();
        table.setId("bigTable");
        TableColumn<String, String> col = new TableColumn<>("Value");
        col.setId("bigCol");
        col.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue()));
        table.getColumns().add(col);
        List<String> rows = new ArrayList<>(BIG_ROWS);
        for (int i = 0; i < BIG_ROWS; i++) {
            rows.add("row-" + i);
        }
        ObservableList<String> items = FXCollections.observableArrayList(rows);
        table.setItems(items);

        Button build = button("buildBigBtn", "Build 5000 nodes");
        build.setOnAction(e -> buildBig());
        bigPane = new FlowPane(2, 2);
        bigPane.setId("bigPane");
        ScrollPane sp = new ScrollPane(bigPane);
        sp.setId("bigScroll");
        sp.setFitToWidth(true);
        sp.setPrefHeight(250);
        table.setPrefHeight(300);
        return page(table, build, sp);
    }

    private void buildBig() {
        List<Node> nodes = new ArrayList<>(BIG_NODES);
        for (int i = 0; i < BIG_NODES; i++) {
            nodes.add(new Label("n" + i));
        }
        bigPane.getChildren().setAll(nodes);
    }

    // 8. RTL
    private Node rtl(TabPane tabs) {
        Label rtlLabel = label("rtlLabel", NodeOrientation.LEFT_TO_RIGHT.name());
        ToggleButton toggle = new ToggleButton("Toggle RTL");
        toggle.setId("rtlToggle");
        toggle.setOnAction(e -> {
            NodeOrientation o = toggle.isSelected()
                    ? NodeOrientation.RIGHT_TO_LEFT
                    : NodeOrientation.LEFT_TO_RIGHT;
            tabs.getScene().getRoot().setNodeOrientation(o);
            rtlLabel.setText(o.name());
        });
        return page(toggle, rtlLabel);
    }
}
