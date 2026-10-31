package dev.fxloop.maven;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Derives JavaFX module names from {@code org.openjfx} artifact ids. */
public final class JavaFxModules {

    public static final String GROUP_ID = "org.openjfx";

    private JavaFxModules() {}

    /** {@code javafx-controls} to {@code javafx.controls}; {@code null} when not a JavaFX artifact id. */
    public static String moduleName(String artifactId) {
        if (artifactId == null || !artifactId.startsWith("javafx-") || artifactId.length() <= "javafx-".length()) {
            return null;
        }
        return artifactId.replace('-', '.');
    }

    /** Distinct module names, in first-seen order; classifier duplicates (same artifactId) collapse. */
    public static List<String> moduleNames(List<String> artifactIds) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : artifactIds) {
            String m = moduleName(id);
            if (m != null) {
                out.add(m);
            }
        }
        return new ArrayList<>(out);
    }
}
