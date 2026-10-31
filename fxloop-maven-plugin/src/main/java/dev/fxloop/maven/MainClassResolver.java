package dev.fxloop.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.plugin.MojoFailureException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Resolves the application main class from plain inputs: explicit parameter, javafx-maven-plugin,
 * exec-maven-plugin, jar-plugin manifest, then an ASM scan of compiled classes.
 */
public final class MainClassResolver {

    private static final String APPLICATION = "javafx/application/Application";

    /** Plain inputs; any string may be {@code null} or blank. */
    public record Inputs(
            String explicit, String javafxPlugin, String execPlugin, String jarManifest, Path outputDir) {}

    /** Result; {@code mainModule} is non-null only when a {@code module/class} form was given. */
    public record Result(String mainModule, String mainClass, String source) {}

    private MainClassResolver() {}

    public static Result resolve(Inputs in) throws MojoFailureException {
        String[] steps = {in.explicit(), in.javafxPlugin(), in.execPlugin(), in.jarManifest()};
        String[] names = {"fxloop.mainClass", "javafx-maven-plugin", "exec-maven-plugin", "maven-jar-plugin manifest"};
        for (int i = 0; i < steps.length; i++) {
            String v = steps[i];
            if (v != null && !v.isBlank()) {
                return parse(v.trim(), names[i]);
            }
        }
        return scan(in.outputDir());
    }

    static Result parse(String value, String source) {
        int slash = value.indexOf('/');
        if (slash > 0) {
            return new Result(value.substring(0, slash), value.substring(slash + 1), source);
        }
        return new Result(null, value, source);
    }

    private static final class ClassInfo {
        String name; // dotted
        String superName; // internal
        boolean hasMain;
        boolean referencesApplication;
        final Set<String> referenced = new HashSet<>();
    }

    private static Result scan(Path outputDir) throws MojoFailureException {
        Map<String, ClassInfo> classes = new HashMap<>();
        if (outputDir != null && Files.isDirectory(outputDir)) {
            try (Stream<Path> s = Files.walk(outputDir)) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".class"))::iterator) {
                    if (p.getFileName().toString().equals("module-info.class")) {
                        continue;
                    }
                    try (InputStream is = Files.newInputStream(p)) {
                        ClassInfo ci = read(new ClassReader(is));
                        classes.put(ci.name, ci);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        List<String> apps = new ArrayList<>();
        List<String> mains = new ArrayList<>();
        List<String> launchers = new ArrayList<>();
        Set<String> appNames = new HashSet<>();
        for (ClassInfo ci : classes.values()) {
            if (isApplication(ci, classes, 0)) {
                appNames.add(ci.name);
                apps.add(ci.name);
            }
        }
        for (ClassInfo ci : classes.values()) {
            if (!ci.hasMain) {
                continue;
            }
            mains.add(ci.name);
            if (!appNames.contains(ci.name)) {
                boolean refs = ci.referencesApplication;
                for (String a : appNames) {
                    if (ci.referenced.contains(a)) {
                        refs = true;
                    }
                }
                if (refs) {
                    launchers.add(ci.name);
                }
            }
        }
        apps.sort(null);
        mains.sort(null);
        launchers.sort(null);

        if (launchers.size() == 1) {
            return new Result(null, launchers.get(0), "class scan (launcher)");
        }
        if (launchers.isEmpty() && apps.size() == 1) {
            return new Result(null, apps.get(0), "class scan (Application subclass)");
        }
        if (launchers.isEmpty() && apps.isEmpty() && mains.size() == 1) {
            return new Result(null, mains.get(0), "class scan (main method)");
        }
        List<String> all = new ArrayList<>();
        for (String a : apps) {
            all.add(a + " (extends Application)");
        }
        for (String m : mains) {
            all.add(m + " (declares main)" + (launchers.contains(m) ? " [launcher]" : ""));
        }
        String msg = all.isEmpty()
                ? "No main class found in " + outputDir + ". Compile first, or set -Dfxloop.mainClass=<fqcn>."
                : "Cannot determine the main class unambiguously. Candidates:\n  - " + String.join("\n  - ", all)
                        + "\nSet -Dfxloop.mainClass=<fqcn> (or <mainClass> in the fxloop plugin configuration).";
        throw new MojoFailureException(msg);
    }

    private static boolean isApplication(ClassInfo ci, Map<String, ClassInfo> all, int depth) {
        if (ci.superName == null || depth > 32) {
            return false;
        }
        if (ci.superName.equals(APPLICATION)) {
            return true;
        }
        ClassInfo parent = all.get(ci.superName.replace('/', '.'));
        return parent != null && isApplication(parent, all, depth + 1);
    }

    private static ClassInfo read(ClassReader cr) {
        ClassInfo ci = new ClassInfo();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String sig, String superName, String[] itf) {
                ci.name = name.replace('/', '.');
                ci.superName = superName;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (name.equals("main")
                        && desc.equals("([Ljava/lang/String;)V")
                        && (access & Opcodes.ACC_PUBLIC) != 0
                        && (access & Opcodes.ACC_STATIC) != 0) {
                    ci.hasMain = true;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        note(owner);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type t && t.getSort() == Type.OBJECT) {
                            note(t.getInternalName());
                        }
                    }

                    @Override
                    public void visitTypeInsn(int op, String type) {
                        note(type);
                    }

                    private void note(String internal) {
                        if (internal.equals(APPLICATION)) {
                            ci.referencesApplication = true;
                        }
                        ci.referenced.add(internal.replace('/', '.'));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return ci;
    }
}
