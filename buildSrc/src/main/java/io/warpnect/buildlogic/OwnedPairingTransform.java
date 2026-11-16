package io.warpnect.buildlogic;

import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Build-time only: retain the module graph, replace exactly the reviewed pairing class closure. */
public abstract class OwnedPairingTransform implements TransformAction<OwnedPairingTransform.Parameters> {
    public interface Parameters extends TransformParameters {
        @InputFile @PathSensitive(PathSensitivity.NONE) RegularFileProperty getSource();
        @Classpath ConfigurableFileCollection getCompilerClasspath();
    }

    @InputArtifact @PathSensitive(PathSensitivity.NONE)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    private static final String PREFIX = "io/github/muntashirakon/adb/PairingConnectionCtx";
    private static final String AAR_SHA = "62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB";

    @Override public void transform(TransformOutputs outputs) {
        File input = getInputArtifact().get().getAsFile();
        if (!input.getName().equals("libadb-android-bc-3.4.0.aar")) {
            outputs.file(input); // Other AARs retain the exact original bytes and artifact.
            return;
        }
        patch(input, getParameters().getSource().get().getAsFile(),
                getParameters().getCompilerClasspath().getFiles(),
                outputs.file("libadb-android-bc-3.4.0-owned-pairing.aar"));
    }

    /** Same implementation used by the transform and the explicit two-build reproduction gate. */
    public static void patch(File input, File source, Collection<File> compilerClasspath, File output) {
        try {
            if (!sha(Files.readAllBytes(input.toPath())).equals(AAR_SHA)) {
                throw new IOException("Unreviewed libadb AAR input");
            }
            Path stage = output.toPath().getParent().resolve("compile");
            Files.createDirectories(stage);
            SortedMap<String, byte[]> aar = unzip(Files.readAllBytes(input.toPath()));
            SortedMap<String, byte[]> original = unzip(Objects.requireNonNull(aar.get("classes.jar")));
            Path library = stage.resolve("original.jar");
            Files.write(library, aar.get("classes.jar"));
            List<String> classpath = new ArrayList<>();
            classpath.add(library.toString());
            int count = 0;
            for (File file : compilerClasspath) {
                if (file.getName().endsWith(".aar")) {
                    byte[] classes = unzip(Files.readAllBytes(file.toPath())).get("classes.jar");
                    if (classes != null) {
                        Path extracted = stage.resolve("dependency-" + count++ + ".jar");
                        Files.write(extracted, classes);
                        classpath.add(extracted.toString());
                    }
                } else classpath.add(file.getAbsolutePath());
            }
            Path compiled = stage.resolve("classes");
            Files.createDirectories(compiled);
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            int result = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                    "--release", "8", "-proc:none", "-implicit:none", "-g:none",
                    "-classpath", String.join(File.pathSeparator, classpath), "-d", compiled.toString(),
                    source.getAbsolutePath());
            if (diagnostics.size() != 0) System.err.print(diagnostics.toString("UTF-8"));
            if (result != 0) throw new IOException("Pairing patch compilation failed: " + diagnostics.toString("UTF-8"));
            SortedMap<String, byte[]> patched = new TreeMap<>(original);
            patched.keySet().removeIf(OwnedPairingTransform::owned);
            try (java.util.stream.Stream<Path> paths = Files.walk(compiled)) {
                for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    String name = compiled.relativize(file).toString().replace('\\', '/');
                    if (!owned(name)) throw new IOException("Patch compiled an unrelated class");
                    patched.put(name, Files.readAllBytes(file));
                }
            }
            if (!patched.containsKey(PREFIX + ".class")) throw new IOException("No patched context");
            for (String name : original.keySet()) {
                if (!owned(name) && !Arrays.equals(original.get(name), patched.get(name))) {
                    throw new IOException("Unrelated class changed");
                }
            }
            aar.put("classes.jar", zip(patched));
            Files.write(output.toPath(), zip(aar));
        } catch (IOException failure) {
            throw new IllegalStateException("Controlled libadb patch failed", failure);
        }
    }

    private static boolean owned(String name) {
        return name.equals(PREFIX + ".class") || name.startsWith(PREFIX + "$") && name.endsWith(".class");
    }

    private static SortedMap<String, byte[]> unzip(byte[] bytes) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && entries.put(entry.getName(), in.readAllBytes()) != null) {
                    throw new IOException("Duplicate archive entry");
                }
            }
        }
        return entries;
    }

    private static byte[] zip(SortedMap<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                out.putNextEntry(entry);
                out.write(item.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
}
