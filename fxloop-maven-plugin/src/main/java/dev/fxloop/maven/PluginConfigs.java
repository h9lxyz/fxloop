package dev.fxloop.maven;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Reads values out of other plugins' configuration (plugin level first, then each execution). */
final class PluginConfigs {

    private PluginConfigs() {}

    static Plugin find(MavenProject project, String groupId, String artifactId) {
        String key = groupId + ":" + artifactId;
        Plugin p = project.getBuild() == null ? null : lookup(project.getBuild().getPlugins(), key);
        if (p == null && project.getBuild() != null && project.getBuild().getPluginManagement() != null) {
            p = lookup(project.getBuild().getPluginManagement().getPlugins(), key);
        }
        return p;
    }

    private static Plugin lookup(List<Plugin> plugins, String key) {
        for (Plugin p : plugins) {
            if (p.getKey().equals(key)) {
                return p;
            }
        }
        return null;
    }

    static List<Xpp3Dom> configurations(Plugin plugin) {
        List<Xpp3Dom> out = new ArrayList<>();
        if (plugin == null) {
            return out;
        }
        if (plugin.getConfiguration() instanceof Xpp3Dom d) {
            out.add(d);
        }
        for (PluginExecution e : plugin.getExecutions()) {
            if (e.getConfiguration() instanceof Xpp3Dom d) {
                out.add(d);
            }
        }
        return out;
    }

    /** First non-blank text at {@code path} (child element names) across plugin and execution configs. */
    static String text(Plugin plugin, String... path) {
        for (Xpp3Dom cfg : configurations(plugin)) {
            Xpp3Dom n = cfg;
            for (String p : path) {
                n = n == null ? null : n.getChild(p);
            }
            if (n != null && n.getValue() != null && !n.getValue().isBlank()) {
                return n.getValue().trim();
            }
        }
        return null;
    }

    /** Text values of all children of the element at {@code path}. */
    static List<String> childTexts(Plugin plugin, String... path) {
        List<String> out = new ArrayList<>();
        for (Xpp3Dom cfg : configurations(plugin)) {
            Xpp3Dom n = cfg;
            for (String p : path) {
                n = n == null ? null : n.getChild(p);
            }
            if (n != null) {
                for (Xpp3Dom c : n.getChildren()) {
                    if (c.getValue() != null && !c.getValue().isBlank()) {
                        out.add(c.getValue().trim());
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        return out;
    }

    static boolean has(Plugin plugin, String... path) {
        for (Xpp3Dom cfg : configurations(plugin)) {
            Xpp3Dom n = cfg;
            for (String p : path) {
                n = n == null ? null : n.getChild(p);
            }
            if (n != null) {
                return true;
            }
        }
        return false;
    }
}
