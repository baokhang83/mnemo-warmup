import io.github.baokhang83.mnemo.warmup.core.ArchiveManifest;
import io.github.baokhang83.mnemo.warmup.core.CacheStrategy;
import io.github.baokhang83.mnemo.warmup.core.JarExtractor;
import io.github.baokhang83.mnemo.warmup.core.JdkInfo;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Writes the fixture the Go planner is tested against, using the real Java classes, so that the Go side agrees with Java on the
 * fingerprint and on every comparison. Run once, from the repository root, after building the CLI jar:
 *
 *   java -cp warmup-cli/target/mnemo-warmup.jar launcher/testdata/compat/Gen.java launcher/testdata/compat/fixture
 *
 * The directory is copied by the Go tests, which put the modification times back and replace @ROOT@ with the copy's path.
 * The Java test CompatFixtureTest fails if Java stops accepting the committed manifest: regenerate then.
 */
public class Gen {
    static final Instant MTIME = Instant.parse("2026-01-02T03:04:05Z");
    static final String ROOT_PLACEHOLDER = "@ROOT@";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        deleteTree(root);
        Path app = Files.createDirectories(root.resolve("app"));
        Path lib = Files.createDirectories(app.resolve("lib"));
        Path cache = Files.createDirectories(root.resolve("cache"));
        Path home = Files.createDirectories(root.resolve("javahome/bin"));

        Files.writeString(home.resolve("java"), "#!/bin/sh\n");
        home.resolve("java").toFile().setExecutable(true);
        Files.writeString(root.resolve("javahome/release"),
                "JAVA_VERSION=\"21.0.13\"\nIMPLEMENTOR=\"Test Vendor\"\nJAVA_RUNTIME_VERSION=\"21.0.13+11-LTS\"\n");

        jar(lib.resolve("dep.jar"), null, "dep");
        jar(app.resolve("app.jar"), "lib/dep.jar", "app");
        Files.write(cache.resolve("app.jsa"), "a pretend archive, any bytes will do".getBytes(StandardCharsets.UTF_8));
        for (Path p : new Path[] {lib.resolve("dep.jar"), app.resolve("app.jar")}) {
            Files.setLastModifiedTime(p, FileTime.from(MTIME));
        }

        var jdk = new JdkInfo(root.resolve("javahome"), "21.0.13", 21, "Test Vendor", "21.0.13+11-LTS");
        var live = ArchiveManifest.describe(
                new JarExtractor.ExtractedApp(JarExtractor.Kind.EXECUTABLE_JAR, app.resolve("app.jar")),
                jdk, CacheStrategy.APP_CDS, List.of("-Xmx512m"), Map.of(), "sha256:abc");
        var manifest = live.withArchive(cache.resolve("app.jsa"));
        Files.writeString(cache.resolve(ArchiveManifest.FILE_NAME), manifest.toJson().replace(root.toString(), ROOT_PLACEHOLDER));
        Files.writeString(cache.resolve("mnemo-warmup.flags"), "-XX:SharedArchiveFile=" + ROOT_PLACEHOLDER + "/cache/app.jsa\n");
        System.out.println("fingerprint " + manifest.fingerprint());
    }

    static void jar(Path file, String classPath, String content) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) {
            mf.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
            mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        }
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out, mf)) {
            jar.putNextEntry(new JarEntry("data.txt"));
            jar.write(content.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(f);
            }
        }
    }
}
