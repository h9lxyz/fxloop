package dev.fxloop.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainClassResolverTest {

    private static final Map<String, String> STUB = Map.of(
            "javafx/application/Application.java",
            "package javafx.application; public abstract class Application {"
                    + " public static void launch(Class<? extends Application> c, String... a) {} }");

    @TempDir
    Path tmp;

    private Path compile(Map<String, String> sources) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        List<String> files = new ArrayList<>();
        Map<String, String> all = new java.util.HashMap<>(STUB);
        all.putAll(sources);
        for (var e : all.entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-proc:none"));
        args.addAll(files);
        assertThat(jc.run(null, null, null, args.toArray(String[]::new))).isZero();
        // drop the stub from the "project" output so only user classes are scanned
        Files.delete(out.resolve("javafx/application/Application.class"));
        return out;
    }

    private static MainClassResolver.Inputs scan(Path out) {
        return new MainClassResolver.Inputs(null, null, null, null, out);
    }

    private static final String APP = "package p; public class App extends javafx.application.Application {}";
    private static final String LAUNCHER = "package p; public class Launcher { public static void main(String[] a)"
            + " { javafx.application.Application.launch(App.class, a); } }";

    @Test
    void explicitWins() throws Exception {
        var r = MainClassResolver.resolve(new MainClassResolver.Inputs("a.B", "c.D", "e.F", "g.H", tmp));
        assertThat(r.mainClass()).isEqualTo("a.B");
        assertThat(r.mainModule()).isNull();
    }

    @Test
    void javafxPluginSplitsModuleAndClass() throws Exception {
        var r = MainClassResolver.resolve(new MainClassResolver.Inputs(" ", "my.mod/p.Main", "e.F", "g.H", tmp));
        assertThat(r.mainModule()).isEqualTo("my.mod");
        assertThat(r.mainClass()).isEqualTo("p.Main");
    }

    @Test
    void execPluginBeatsManifest() throws Exception {
        var r = MainClassResolver.resolve(new MainClassResolver.Inputs(null, null, "e.F", "g.H", tmp));
        assertThat(r.mainClass()).isEqualTo("e.F");
    }

    @Test
    void manifestUsedBeforeScan() throws Exception {
        var r = MainClassResolver.resolve(new MainClassResolver.Inputs(null, null, null, "g.H", tmp));
        assertThat(r.mainClass()).isEqualTo("g.H");
    }

    @Test
    void scanPrefersLauncherPattern() throws Exception {
        Path out = compile(Map.of("p/App.java", APP, "p/Launcher.java", LAUNCHER));
        assertThat(MainClassResolver.resolve(scan(out)).mainClass()).isEqualTo("p.Launcher");
    }

    @Test
    void scanFallsBackToApplicationSubclass() throws Exception {
        Path out = compile(Map.of("p/App.java", APP, "p/Util.java", "package p; public class Util {}"));
        assertThat(MainClassResolver.resolve(scan(out)).mainClass()).isEqualTo("p.App");
    }

    @Test
    void scanFindsPlainMain() throws Exception {
        Path out = compile(Map.of("p/Tool.java", "package p; public class Tool { public static void main(String[] a) {} }"));
        assertThat(MainClassResolver.resolve(scan(out)).mainClass()).isEqualTo("p.Tool");
    }

    @Test
    void scanAmbiguousListsCandidates() throws Exception {
        Path out = compile(Map.of(
                "p/App.java", APP,
                "p/App2.java", "package p; public class App2 extends javafx.application.Application {}"));
        assertThatThrownBy(() -> MainClassResolver.resolve(scan(out)))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("p.App (extends Application)")
                .hasMessageContaining("p.App2 (extends Application)")
                .hasMessageContaining("fxloop.mainClass");
    }

    @Test
    void scanNothingFound() throws Exception {
        Path out = compile(Map.of("p/Util.java", "package p; public class Util {}"));
        assertThatThrownBy(() -> MainClassResolver.resolve(scan(out)))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("No main class");
    }
}
