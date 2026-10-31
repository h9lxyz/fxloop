package dev.fxloop.maven;

import dev.fxloop.protocol.LaunchDescriptor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/** Writes {@code target/fxloop/launch.json} describing how to launch the project's JavaFX application. */
@Mojo(name = "prepare", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class PrepareMojo extends AbstractMojo {

    private static final Pattern MODULE_NAME =
            Pattern.compile("(?:^|\\s)module\\s+([\\w.]+)\\s*\\{", Pattern.MULTILINE);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Target file for the launch descriptor. */
    @Parameter(property = "fxloop.outputFile", defaultValue = "${project.build.directory}/fxloop/launch.json")
    private File outputFile;

    /** Main class (optionally {@code module/fqcn}); overrides all auto-detection. */
    @Parameter(property = "fxloop.mainClass")
    private String mainClass;

    /** {@code auto}, {@code classpath} or {@code modulepath}. */
    @Parameter(property = "fxloop.launchMode", defaultValue = "auto")
    private String launchMode;

    /** Extra JVM arguments. */
    @Parameter(property = "fxloop.jvmArgs")
    private List<String> jvmArgs;

    /** Extra program arguments. */
    @Parameter(property = "fxloop.appArgs")
    private List<String> appArgs;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            doExecute();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write launch descriptor: " + e.getMessage(), e);
        }
    }

    private void doExecute() throws IOException, MojoFailureException, MojoExecutionException {
        Path outputDir = Path.of(project.getBuild().getOutputDirectory()).toAbsolutePath();
        Plugin javafxPlugin = PluginConfigs.find(project, "org.openjfx", "javafx-maven-plugin");
        Plugin execPlugin = PluginConfigs.find(project, "org.codehaus.mojo", "exec-maven-plugin");
        Plugin jarPlugin = PluginConfigs.find(project, "org.apache.maven.plugins", "maven-jar-plugin");
        Plugin compilerPlugin = PluginConfigs.find(project, "org.apache.maven.plugins", "maven-compiler-plugin");

        MainClassResolver.Result main = MainClassResolver.resolve(new MainClassResolver.Inputs(
                mainClass,
                PluginConfigs.text(javafxPlugin, "mainClass"),
                PluginConfigs.text(execPlugin, "mainClass"),
                PluginConfigs.text(jarPlugin, "archive", "manifest", "mainClass"),
                outputDir));

        List<String> sourceRoots = existing(project.getCompileSourceRoots());
        List<String> resourceRoots = new ArrayList<>();
        for (Resource r : project.getResources()) {
            resourceRoots.addAll(existing(List.of(r.getDirectory())));
        }

        String moduleInfoSource = findModuleInfo(project.getCompileSourceRoots());
        String mode = resolveMode(moduleInfoSource != null);

        List<String> runtime = new ArrayList<>();
        for (String e : getRuntimeElements()) {
            Path p = Path.of(e).toAbsolutePath();
            if (!p.equals(outputDir)) {
                runtime.add(p.toString());
            }
        }

        List<String> classpath = new ArrayList<>();
        List<String> modulePath = new ArrayList<>();
        List<String> addModules = new ArrayList<>();
        String mainModule = main.mainModule();

        if (mode.equals("modulepath")) {
            if (moduleInfoSource != null) {
                if (mainModule == null) {
                    mainModule = parseModuleName(Files.readString(Path.of(moduleInfoSource)));
                }
                modulePath.add(outputDir.toString());
                modulePath.addAll(runtime);
            } else {
                mainModule = null;
                List<String> fxArtifactIds = new ArrayList<>();
                List<String> fxPaths = new ArrayList<>();
                for (Artifact a : project.getArtifacts()) {
                    if (JavaFxModules.GROUP_ID.equals(a.getGroupId()) && a.getFile() != null) {
                        fxArtifactIds.add(a.getArtifactId());
                        fxPaths.add(a.getFile().toPath().toAbsolutePath().toString());
                    }
                }
                for (String p : runtime) {
                    (fxPaths.contains(p) ? modulePath : classpath).add(p);
                }
                addModules.addAll(JavaFxModules.moduleNames(fxArtifactIds));
            }
        } else {
            mainModule = null;
            classpath.addAll(runtime);
        }

        List<String> jvm = new ArrayList<>(nullToEmpty(jvmArgs));
        jvm.addAll(PluginConfigs.childTexts(javafxPlugin, "options"));
        List<String> app = new ArrayList<>(nullToEmpty(appArgs));
        String cmdline = PluginConfigs.text(javafxPlugin, "commandlineArgs");
        if (cmdline != null) {
            for (String t : cmdline.split("\\s+")) {
                if (!t.isEmpty()) {
                    app.add(t);
                }
            }
        }

        LaunchDescriptor d = LaunchDescriptor.builder()
                .buildTool("maven")
                .mainClass(main.mainClass())
                .mainModule(mainModule)
                .classpath(classpath)
                .modulePath(modulePath)
                .addModules(addModules)
                .jvmArgs(jvm)
                .appArgs(app)
                .workingDir(project.getBasedir().toPath().toAbsolutePath().toString())
                .sourceRoots(sourceRoots)
                .resourceRoots(resourceRoots)
                .outputDir(outputDir.toString())
                .release(resolveRelease(compilerPlugin))
                .annotationProcessors(detectProcessors(compilerPlugin))
                .build();
        d.writeTo(outputFile.toPath());

        getLog().info("FXLoop: mainClass=" + main.mainClass() + " (" + main.source() + "), mode=" + mode
                + ", classpath=" + classpath.size() + ", modulePath=" + modulePath.size()
                + ", sourceRoots=" + sourceRoots.size() + ", resourceRoots=" + resourceRoots.size()
                + " -> " + outputFile);
    }

    private List<String> getRuntimeElements() throws MojoExecutionException {
        try {
            return project.getRuntimeClasspathElements();
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot resolve runtime classpath", e);
        }
    }

    private String resolveMode(boolean modular) throws MojoFailureException {
        String m = launchMode == null ? "auto" : launchMode.trim().toLowerCase();
        return switch (m) {
            case "auto" -> modular ? "modulepath" : "classpath";
            case "classpath", "modulepath" -> m;
            default -> throw new MojoFailureException(
                    "Invalid fxloop.launchMode '" + launchMode + "' (expected auto|classpath|modulepath)");
        };
    }

    static String parseModuleName(String moduleInfoSource) {
        String stripped = moduleInfoSource
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ");
        Matcher m = MODULE_NAME.matcher(stripped);
        return m.find() ? m.group(1) : null;
    }

    private static String findModuleInfo(List<String> roots) {
        for (String r : roots) {
            Path p = Path.of(r, "module-info.java");
            if (Files.isRegularFile(p)) {
                return p.toString();
            }
        }
        return null;
    }

    private static List<String> existing(List<String> dirs) {
        List<String> out = new ArrayList<>();
        for (String d : dirs) {
            if (d != null && Files.isDirectory(Path.of(d))) {
                out.add(Path.of(d).toAbsolutePath().toString());
            }
        }
        return out;
    }

    private static List<String> nullToEmpty(List<String> l) {
        return l == null ? List.of() : l;
    }

    private String resolveRelease(Plugin compilerPlugin) {
        String r = PluginConfigs.text(compilerPlugin, "release");
        if (isValue(r)) {
            return r;
        }
        for (String key : new String[] {"maven.compiler.release", "maven.compiler.target"}) {
            String v = project.getProperties().getProperty(key);
            if (isValue(v)) {
                return v.trim();
            }
        }
        return String.valueOf(Runtime.version().feature());
    }

    private static boolean isValue(String v) {
        return v != null && !v.isBlank() && !v.contains("${");
    }

    private boolean detectProcessors(Plugin compilerPlugin) {
        if (PluginConfigs.has(compilerPlugin, "annotationProcessorPaths")) {
            return true;
        }
        List<String> elements;
        try {
            elements = project.getCompileClasspathElements();
        } catch (Exception e) {
            return false;
        }
        String svc = "META-INF/services/javax.annotation.processing.Processor";
        for (String el : elements) {
            File f = new File(el);
            if (f.isFile()) {
                try (ZipFile z = new ZipFile(f)) {
                    if (z.getEntry(svc) != null) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // not a readable jar: skip
                }
            } else if (f.isDirectory() && new File(f, svc).isFile()) {
                return true;
            }
        }
        return false;
    }
}
