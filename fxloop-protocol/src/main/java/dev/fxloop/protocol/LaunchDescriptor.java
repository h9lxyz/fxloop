package dev.fxloop.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code launch.json} emitted by {@code fxloop:prepare} (Maven) and {@code fxloopPrepare} (Gradle).
 * Single source of truth for both build plugins; JSON schema published as
 * {@code dev/fxloop/protocol/launch.schema.json}.
 *
 * @param buildTool        {@code maven} or {@code gradle}
 * @param mainClass        fully qualified main class
 * @param mainModule       main module name when the app itself is modular, else {@code null}
 * @param classpath        absolute classpath entries (excluding {@code outputDir})
 * @param modulePath       absolute module-path entries (JavaFX jars when launched modular)
 * @param addModules       modules to pass to {@code --add-modules}
 * @param jvmArgs          extra JVM arguments configured in the build
 * @param appArgs          program arguments configured in the build
 * @param workingDir       absolute working directory for the child process
 * @param sourceRoots      absolute Java source roots
 * @param resourceRoots    absolute resource roots
 * @param outputDir        absolute compiled classes directory
 * @param release          Java release level for in-process compilation (e.g. "17")
 * @param annotationProcessors true when annotation processors were detected on the compile path
 * @param fcp              reserved for FCP overrides (port, timeouts)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LaunchDescriptor(
        int schemaVersion,
        String buildTool,
        String mainClass,
        String mainModule,
        List<String> classpath,
        List<String> modulePath,
        List<String> addModules,
        List<String> jvmArgs,
        List<String> appArgs,
        String workingDir,
        List<String> sourceRoots,
        List<String> resourceRoots,
        String outputDir,
        String release,
        boolean annotationProcessors,
        Map<String, Object> fcp) {

    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "launch.json";

    public LaunchDescriptor {
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        modulePath = modulePath == null ? List.of() : List.copyOf(modulePath);
        addModules = addModules == null ? List.of() : List.copyOf(addModules);
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        appArgs = appArgs == null ? List.of() : List.copyOf(appArgs);
        sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
        resourceRoots = resourceRoots == null ? List.of() : List.copyOf(resourceRoots);
        fcp = fcp == null ? Map.of() : Map.copyOf(fcp);
    }

    public void writeTo(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, Json.pretty(this));
    }

    public static LaunchDescriptor readFrom(Path file) throws IOException {
        return Json.MAPPER.readValue(Files.readString(file), LaunchDescriptor.class);
    }

    /** Fluent builder for the build plugins. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String buildTool;
        private String mainClass;
        private String mainModule;
        private List<String> classpath;
        private List<String> modulePath;
        private List<String> addModules;
        private List<String> jvmArgs;
        private List<String> appArgs;
        private String workingDir;
        private List<String> sourceRoots;
        private List<String> resourceRoots;
        private String outputDir;
        private String release;
        private boolean annotationProcessors;

        public Builder buildTool(String v) { buildTool = v; return this; }
        public Builder mainClass(String v) { mainClass = v; return this; }
        public Builder mainModule(String v) { mainModule = v; return this; }
        public Builder classpath(List<String> v) { classpath = v; return this; }
        public Builder modulePath(List<String> v) { modulePath = v; return this; }
        public Builder addModules(List<String> v) { addModules = v; return this; }
        public Builder jvmArgs(List<String> v) { jvmArgs = v; return this; }
        public Builder appArgs(List<String> v) { appArgs = v; return this; }
        public Builder workingDir(String v) { workingDir = v; return this; }
        public Builder sourceRoots(List<String> v) { sourceRoots = v; return this; }
        public Builder resourceRoots(List<String> v) { resourceRoots = v; return this; }
        public Builder outputDir(String v) { outputDir = v; return this; }
        public Builder release(String v) { release = v; return this; }
        public Builder annotationProcessors(boolean v) { annotationProcessors = v; return this; }

        public LaunchDescriptor build() {
            return new LaunchDescriptor(SCHEMA_VERSION, buildTool, mainClass, mainModule, classpath, modulePath,
                    addModules, jvmArgs, appArgs, workingDir, sourceRoots, resourceRoots, outputDir, release,
                    annotationProcessors, null);
        }
    }
}
