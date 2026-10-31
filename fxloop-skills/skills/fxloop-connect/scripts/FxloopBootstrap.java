import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launches the FXLoop MCP server for any MCP client (PRD 06 §3.1). Runs as a single-file source program
 * ({@code java FxloopBootstrap.java}), so a JDK is the only requirement on every OS. The {@code fxloop-connect} skill
 * copies it to {@code ~/.fxloop/} and points the client's MCP config there, so the config never depends on where the
 * skills are installed.
 *
 * <p>Server jar resolution: {@code FXLOOP_SERVER_JAR} → {@code ~/.fxloop/fxloop-server-<v>-all.jar} (release cache)
 * → the local Maven repository → {@code jbang} on PATH → the project's own wrapper ({@code mvnw dependency:copy}, or
 * {@code gradlew} on a throwaway resolve build) into {@code ~/.fxloop/}. Then {@code java -jar <jar> <args>} with
 * stdio inherited. stdout carries MCP frames only: everything this program and the build tools print goes to stderr.
 *
 * <p>The server runs in the workspace: {@code FXLOOP_WORKSPACE}, else {@code CLAUDE_PROJECT_DIR}, else the current
 * directory. It reads its optional {@code fxloop.properties} from there.
 *
 * <p>{@code --install} only makes sure a server jar is available (resolving it into {@code ~/.fxloop/} if needed),
 * reports it on stderr and exits 0, or 2 when none can be found. The connect skill runs it before writing any config.
 */
public final class FxloopBootstrap {
    static final String VERSION = env("FXLOOP_SERVER_VERSION", "1.0.0-SNAPSHOT");
    static final String GAV = "dev.fxloop:fxloop-server:" + VERSION;
    static final String JAR_NAME = "fxloop-server-" + VERSION + "-all.jar";
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    public static void main(String[] args) throws Exception {
        Path project = Path.of(env("FXLOOP_WORKSPACE", env("CLAUDE_PROJECT_DIR", System.getProperty("user.dir"))))
            .toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home"), ".fxloop");
        if (args.length > 0 && args[0].equals("--install")) {
            System.exit(install(project, home));
        }
        List<String> cmd = new ArrayList<>();
        Path jar = resolveJar(project, home);
        if (jar != null) {
            cmd.add(javaExe());
            cmd.add("-jar");
            cmd.add(jar.toString());
        } else if (onPath("jbang")) {
            log("using jbang for " + GAV + ":all");
            cmd.addAll(List.of(WINDOWS ? "jbang.cmd" : "jbang", "--quiet", GAV + ":all"));
        } else {
            jar = fetchWithWrapper(project, home);
            if (jar == null) {
                log("cannot find the FXLoop server " + GAV + ":all. Fix one of: set FXLOOP_SERVER_JAR to the -all.jar; "
                    + "install jbang; add a Maven/Gradle wrapper to the project; or build FXLoop locally (./mvnw install).");
                System.exit(2);
            }
            cmd.addAll(List.of(javaExe(), "-jar", jar.toString()));
        }
        cmd.addAll(List.of(args));
        Process server = new ProcessBuilder(cmd).directory(project.toFile()).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server.isAlive()) server.destroy();
        }));
        System.exit(server.waitFor());
    }

    /** {@code --install}: 0 when a server jar (or jbang) is available, 2 otherwise. */
    static int install(Path project, Path home) throws IOException, InterruptedException {
        Path jar = resolveJar(project, home);
        if (jar == null && !onPath("jbang")) jar = fetchWithWrapper(project, home);
        if (jar != null) {
            log("server " + VERSION + ": " + jar);
            return 0;
        }
        if (onPath("jbang")) {
            log("server " + VERSION + ": jbang " + GAV + ":all (resolved on first start)");
            return 0;
        }
        log("cannot find the FXLoop server " + GAV + ":all. Fix one of: set FXLOOP_SERVER_JAR to the -all.jar; "
            + "install jbang; add a Maven/Gradle wrapper to the project; or build FXLoop locally (./mvnw install).");
        return 2;
    }

    /** A jar that exists locally already, or null. */
    static Path resolveJar(Path project, Path home) {
        String explicit = System.getenv("FXLOOP_SERVER_JAR");
        if (explicit != null && !explicit.isBlank()) {
            Path p = project.resolve(explicit.trim());
            if (Files.isRegularFile(p)) return p;
            log("FXLOOP_SERVER_JAR=" + explicit + " does not exist; trying the other sources");
        }
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository", "dev", "fxloop", "fxloop-server", VERSION, JAR_NAME);
        Path cached = home.resolve(JAR_NAME);
        // a SNAPSHOT changes under the same name: prefer the local repository over the cache
        if (VERSION.endsWith("-SNAPSHOT") && Files.isRegularFile(m2)) return m2;
        if (Files.isRegularFile(cached)) return cached;
        if (Files.isRegularFile(m2)) return m2;
        return null;
    }

    /** Resolves the jar through the project's Maven or Gradle wrapper into ~/.fxloop; null when there is none. */
    static Path fetchWithWrapper(Path project, Path home) throws IOException, InterruptedException {
        Files.createDirectories(home);
        Path mvnw = wrapper(project, WINDOWS ? "mvnw.cmd" : "mvnw");
        Path gradlew = wrapper(project, WINDOWS ? "gradlew.bat" : "gradlew");
        Path target = home.resolve(JAR_NAME);
        if (mvnw != null) {
            log("resolving " + GAV + ":all with " + mvnw);
            run(List.of(mvnw.toString(), "-q", "-B", "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy",
                "-Dartifact=" + GAV + ":jar:all", "-DoutputDirectory=" + home, "-Dmdep.useBaseVersion=true"), home);
        } else if (gradlew != null) {
            log("resolving " + GAV + ":all with " + gradlew);
            Path build = home.resolve("resolve");
            Files.createDirectories(build);
            Files.writeString(build.resolve("settings.gradle.kts"), "rootProject.name = \"fxloop-resolve\"\n");
            Files.writeString(build.resolve("build.gradle.kts"), String.join("\n",
                "repositories { mavenLocal(); mavenCentral() }",
                "val fxloop by configurations.creating { isTransitive = false }",
                "dependencies { fxloop(\"" + GAV + ":all\") }",
                "tasks.register<Copy>(\"fxloopServer\") { from(fxloop); into(\"" + home.toString().replace("\\", "/") + "\") }",
                ""));
            run(List.of(gradlew.toString(), "-q", "-p", build.toString(), "fxloopServer"), build);
        } else {
            return null;
        }
        return Files.isRegularFile(target) ? target : null;
    }

    /** The wrapper in the project dir or a parent (multi-module builds keep it at the root). */
    static Path wrapper(Path project, String name) {
        for (Path d = project; d != null; d = d.getParent()) {
            Path w = d.resolve(name);
            if (Files.isRegularFile(w)) return w;
        }
        return null;
    }

    /** Runs a build command with no stdin and its output copied to stderr (never stdout). */
    static void run(List<String> cmd, Path dir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        pump(p.getInputStream(), System.err);
        int exit = p.waitFor();
        if (exit != 0) log(String.join(" ", cmd) + " exited with " + exit);
    }

    static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        out.flush();
    }

    static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            for (String ext : WINDOWS ? new String[] {".cmd", ".bat", ".exe"} : new String[] {""}) {
                if (Files.isExecutable(Path.of(dir, exe + ext))) return true;
            }
        }
        return false;
    }

    /** The java running this program: the same JDK starts the server. */
    static String javaExe() {
        return ProcessHandle.current().info().command().orElse(WINDOWS ? "java.exe" : "java");
    }

    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    static void log(String msg) {
        System.err.println("[fxloop-bootstrap] " + msg);
        System.err.flush();
    }

    private FxloopBootstrap() {}

    static {
        // keep stray prints off stdout (MCP frames only)
        System.setOut(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
    }
}
