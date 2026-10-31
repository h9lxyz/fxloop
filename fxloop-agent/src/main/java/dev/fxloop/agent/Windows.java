package dev.fxloop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fxloop.protocol.Fcp;
import dev.fxloop.protocol.FcpException;
import dev.fxloop.protocol.Json;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.DialogPane;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Window enumeration and addressing. Must be called on the FX thread. */
final class Windows {
    private Windows() {}

    static List<Window> showing() {
        List<Window> out = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null) {
                out.add(w);
            }
        }
        return out;
    }

    static String type(Window w) {
        if (w instanceof PopupWindow) {
            return "POPUP";
        }
        if (w instanceof Stage s) {
            boolean dialog = s.getModality() != javafx.stage.Modality.NONE
                    || s.getScene() != null && s.getScene().getRoot() instanceof DialogPane;
            return dialog ? "DIALOG" : "STAGE";
        }
        return "WINDOW";
    }

    /** Path segment for a window: {@code Stage[i]} among stages, {@code Popup[i]} among popups. */
    static String segment(Window w) {
        List<Window> all = showing();
        boolean popup = w instanceof PopupWindow;
        int i = 0;
        for (Window other : all) {
            if (other == w) {
                break;
            }
            if ((other instanceof PopupWindow) == popup) {
                i++;
            }
        }
        return (popup ? "Popup[" : "Stage[") + i + "]";
    }

    static Window bySegment(String kind, int index) {
        int i = 0;
        for (Window w : showing()) {
            boolean popup = w instanceof PopupWindow;
            boolean match = switch (kind) {
                case "Stage" -> !popup;
                case "Popup" -> popup;
                default -> true;
            };
            if (match) {
                if (i == index) {
                    return w;
                }
                i++;
            }
        }
        throw new FcpException(Fcp.Error.WINDOW_NOT_FOUND,
                "no showing window " + kind + "[" + index + "]; call fx_get_app_status to list windows");
    }

    /** Index into {@link #showing()}; {@code null}/negative selects the focused window (else the first stage). */
    static Window byIndex(Integer index) {
        List<Window> all = showing();
        if (all.isEmpty()) {
            throw new FcpException(Fcp.Error.WINDOW_NOT_FOUND, "the application has no showing window yet");
        }
        if (index == null || index < 0) {
            return focused(all);
        }
        if (index >= all.size()) {
            throw new FcpException(Fcp.Error.WINDOW_NOT_FOUND,
                    "windowIndex " + index + " out of range (" + all.size() + " windows)");
        }
        return all.get(index);
    }

    static Window focused(List<Window> all) {
        for (Window w : all) {
            if (w.isFocused() && !(w instanceof PopupWindow)) {
                return w;
            }
        }
        for (Window w : all) {
            if (!(w instanceof PopupWindow)) {
                return w;
            }
        }
        return all.get(0);
    }

    static int indexOf(Window w) {
        return showing().indexOf(w);
    }

    static ArrayNode describeAll() {
        ArrayNode arr = Json.array();
        List<Window> all = showing();
        for (int i = 0; i < all.size(); i++) {
            arr.add(describe(all.get(i), i));
        }
        return arr;
    }

    static ObjectNode describe(Window w, int index) {
        ObjectNode o = Json.object();
        o.put("index", index);
        o.put("segment", segment(w));
        if (w instanceof Stage s) {
            o.put("title", s.getTitle());
        }
        o.put("type", type(w));
        o.put("focused", w.isFocused());
        o.put("x", Math.round(w.getX()));
        o.put("y", Math.round(w.getY()));
        o.put("w", Math.round(w.getWidth()));
        o.put("h", Math.round(w.getHeight()));
        o.put("showing", w.isShowing());
        return o;
    }
}
