package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Describes what an archive was trained against, and derives a {@linkplain #fingerprint()
 * fingerprint} from it. An archive is only valid for the same JDK build, the same classpath
 * (content and order) and the same heap/GC options, so a later step recomputes the manifest of the
 * environment it is in and compares.
 *
 * <p>The fingerprint covers: format version, cache type, JDK vendor and full build, optional
 * image digest, the relevant JVM options (see {@link JvmFlags}), and each classpath entry's name,
 * size and SHA-256 in classpath order. The archive itself and file modification times are
 * recorded but are <em>not</em> part of the fingerprint: the archive is the thing being
 * identified, and modification times differ between an extraction and an image layer holding
 * the same bytes. (The JVM does also check JAR modification times when it maps an archive; how
 * that interacts with images is still open, see docs/PLAN.md.)
 */
public record ArchiveManifest(
        int schemaVersion,
        CacheStrategy cacheType,
        Jdk jdk,
        String imageDigest,
        List<String> jvmFlags,
        List<ClasspathEntry> classpath,
        ArchiveInfo archive) {

    public static final String FILE_NAME = "mnemo-warmup.manifest.json";
    public static final int SCHEMA_VERSION = 1;

    public record Jdk(String vendor, String version, String runtimeVersion, int featureVersion) {
        static Jdk of(JdkInfo info) {
            return new Jdk(info.vendor(), info.version(), info.runtimeVersion(), info.featureVersion());
        }
    }

    /**
     * @param name         path relative to the directory of the launched JAR, with '/' separators
     * @param lastModified informational only
     */
    public record ClasspathEntry(String name, long sizeBytes, String sha256, Instant lastModified) {}

    public record ArchiveInfo(String file, long sizeBytes, String sha256) {}

    public ArchiveManifest {
        Objects.requireNonNull(cacheType, "cacheType");
        Objects.requireNonNull(jdk, "jdk");
        jvmFlags = List.copyOf(jvmFlags);
        classpath = List.copyOf(classpath);
    }

    // ---- building ----

    /**
     * Describes the environment of {@code app}: the JAR that will be launched and everything its
     * manifest {@code Class-Path} lists. There is no archive yet; see {@link #withArchive(Path)}.
     *
     * @param jvmArgs     JVM options of the run (only the relevant ones are kept)
     * @param environment the process environment, for {@code JAVA_TOOL_OPTIONS} and friends
     * @param imageDigest the container image digest, or {@code null}
     * @throws IllegalStateException if a listed classpath file is missing
     */
    public static ArchiveManifest describe(
            JarExtractor.ExtractedApp app,
            JdkInfo jdk,
            CacheStrategy cacheType,
            List<String> jvmArgs,
            Map<String, String> environment,
            String imageDigest) {
        return new ArchiveManifest(
                SCHEMA_VERSION,
                cacheType,
                Jdk.of(jdk),
                imageDigest,
                JvmFlags.relevant(jvmArgs, environment),
                classpathOf(app.runnableJar()),
                null);
    }

    /** A copy that also records the written archive's name, size and SHA-256. */
    public ArchiveManifest withArchive(Path archiveFile) {
        try {
            ArchiveInfo info = new ArchiveInfo(
                    archiveFile.getFileName().toString(), Files.size(archiveFile), sha256(archiveFile));
            return new ArchiveManifest(schemaVersion, cacheType, jdk, imageDigest, jvmFlags, classpath, info);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read archive " + archiveFile + ": " + e.getMessage(), e);
        }
    }

    // ---- fingerprint and comparison ----

    /** {@code sha256:<hex>} over the canonical form of everything that decides archive validity. */
    public String fingerprint() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schema", (long) schemaVersion);
        canonical.put("cacheType", cacheType.name());
        canonical.put("jdkVendor", jdk.vendor());
        canonical.put("jdkRuntimeVersion", jdk.runtimeVersion());
        canonical.put("jdkFeatureVersion", (long) jdk.featureVersion());
        canonical.put("imageDigest", imageDigest);
        canonical.put("jvmFlags", new ArrayList<Object>(jvmFlags));
        List<Object> entries = new ArrayList<>();
        for (ClasspathEntry e : classpath) {
            entries.add(List.of(e.name(), e.sizeBytes(), e.sha256()));
        }
        canonical.put("classpath", entries);
        return "sha256:" + sha256(Json.writeCompact(canonical).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Explains how {@code live}, the manifest of the environment about to run the application,
     * differs from this one, which the archive was trained against. Empty means the archive may
     * be used.
     */
    public List<String> mismatches(ArchiveManifest live) {
        List<String> problems = new ArrayList<>();
        if (schemaVersion != live.schemaVersion) {
            problems.add("manifest format " + schemaVersion + " vs " + live.schemaVersion);
        }
        if (cacheType != live.cacheType) {
            problems.add("cache type " + cacheType + " vs " + live.cacheType);
        }
        if (!Objects.equals(jdk.vendor(), live.jdk.vendor())) {
            problems.add("JDK vendor '" + jdk.vendor() + "' vs '" + live.jdk.vendor() + "'");
        }
        if (!Objects.equals(jdk.runtimeVersion(), live.jdk.runtimeVersion())) {
            problems.add("JDK build " + jdk.runtimeVersion() + " vs " + live.jdk.runtimeVersion());
        }
        if (!Objects.equals(imageDigest, live.imageDigest)) {
            problems.add("image digest " + imageDigest + " vs " + live.imageDigest);
        }
        if (!jvmFlags.equals(live.jvmFlags)) {
            problems.add("JVM options " + jvmFlags + " vs " + live.jvmFlags);
        }
        compareClasspath(live, problems);
        return problems;
    }

    /**
     * The JVM also checks each classpath JAR's modification time when it maps an archive, which
     * {@link #mismatches} deliberately ignores (the fingerprint must survive re-created layers with
     * identical bytes). Compared to whole seconds, as the JVM does. Empty means the times agree.
     */
    public List<String> modificationTimeMismatches(ArchiveManifest live) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < Math.min(classpath.size(), live.classpath.size()); i++) {
            ClasspathEntry a = classpath.get(i);
            ClasspathEntry b = live.classpath.get(i);
            if (a.name().equals(b.name())
                    && a.lastModified() != null
                    && b.lastModified() != null
                    && a.lastModified().getEpochSecond() != b.lastModified().getEpochSecond()) {
                problems.add("'" + a.name() + "' was modified at " + b.lastModified() + ", the archive was trained at " + a.lastModified());
            }
        }
        return problems;
    }

    private void compareClasspath(ArchiveManifest live, List<String> problems) {
        if (classpath.size() != live.classpath.size()) {
            problems.add("classpath has " + classpath.size() + " entries vs " + live.classpath.size());
        }
        for (int i = 0; i < Math.min(classpath.size(), live.classpath.size()); i++) {
            ClasspathEntry a = classpath.get(i);
            ClasspathEntry b = live.classpath.get(i);
            if (!a.name().equals(b.name())) {
                problems.add("classpath entry " + i + " is '" + a.name() + "' vs '" + b.name() + "'");
            } else if (a.sizeBytes() != b.sizeBytes() || !a.sha256().equals(b.sha256())) {
                problems.add("classpath entry '" + a.name() + "' has different content");
            }
        }
    }

    // ---- JSON ----

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", (long) schemaVersion);
        root.put("fingerprint", fingerprint());
        root.put("cacheType", cacheType.name());
        Map<String, Object> jdkJson = new LinkedHashMap<>();
        jdkJson.put("vendor", jdk.vendor());
        jdkJson.put("version", jdk.version());
        jdkJson.put("runtimeVersion", jdk.runtimeVersion());
        jdkJson.put("featureVersion", (long) jdk.featureVersion());
        root.put("jdk", jdkJson);
        root.put("imageDigest", imageDigest);
        root.put("jvmFlags", new ArrayList<Object>(jvmFlags));
        List<Object> cp = new ArrayList<>();
        for (ClasspathEntry e : classpath) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", e.name());
            entry.put("sizeBytes", e.sizeBytes());
            entry.put("sha256", e.sha256());
            entry.put("lastModified", e.lastModified() == null ? null : e.lastModified().toString());
            cp.add(entry);
        }
        root.put("classpath", cp);
        if (archive != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("file", archive.file());
            a.put("sizeBytes", archive.sizeBytes());
            a.put("sha256", archive.sha256());
            root.put("archive", a);
        }
        return Json.writePretty(root);
    }

    /**
     * @throws IllegalArgumentException if the text is not a valid manifest, or its recorded
     *                                  fingerprint does not match its contents
     */
    @SuppressWarnings("unchecked")
    public static ArchiveManifest fromJson(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Manifest must be a JSON object");
        }
        Map<String, Object> root = (Map<String, Object>) parsed;
        int schema = Math.toIntExact(number(root, "schemaVersion"));
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported manifest schemaVersion " + schema);
        }
        CacheStrategy cacheType;
        try {
            cacheType = CacheStrategy.valueOf(string(root, "cacheType"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown cacheType in manifest: " + root.get("cacheType"));
        }
        Map<String, Object> jdkJson = object(root, "jdk");
        Jdk jdk = new Jdk(
                string(jdkJson, "vendor"),
                string(jdkJson, "version"),
                string(jdkJson, "runtimeVersion"),
                Math.toIntExact(number(jdkJson, "featureVersion")));
        List<String> flags = new ArrayList<>();
        for (Object f : array(root, "jvmFlags")) {
            flags.add(asString(f, "jvmFlags"));
        }
        List<ClasspathEntry> cp = new ArrayList<>();
        for (Object o : array(root, "classpath")) {
            Map<String, Object> e = asObject(o, "classpath");
            Object modified = e.get("lastModified");
            cp.add(new ClasspathEntry(
                    string(e, "name"),
                    number(e, "sizeBytes"),
                    string(e, "sha256"),
                    modified == null ? null : Instant.parse(asString(modified, "lastModified"))));
        }
        ArchiveInfo archive = null;
        if (root.get("archive") != null) {
            Map<String, Object> a = object(root, "archive");
            archive = new ArchiveInfo(string(a, "file"), number(a, "sizeBytes"), string(a, "sha256"));
        }
        Object digest = root.get("imageDigest");
        ArchiveManifest manifest = new ArchiveManifest(
                schema, cacheType, jdk, digest == null ? null : asString(digest, "imageDigest"), flags, cp, archive);
        String recorded = string(root, "fingerprint");
        if (!manifest.fingerprint().equals(recorded)) {
            throw new IllegalArgumentException(
                    "Manifest fingerprint " + recorded + " does not match its contents (" + manifest.fingerprint()
                            + "); the file was edited or is corrupt");
        }
        return manifest;
    }

    public void write(Path file) {
        try {
            Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write manifest " + file + ": " + e.getMessage(), e);
        }
    }

    public static ArchiveManifest read(Path file) {
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read manifest " + file + ": " + e.getMessage(), e);
        }
    }

    // ---- classpath ----

    private static List<ClasspathEntry> classpathOf(Path launchedJar) {
        Path dir = launchedJar.toAbsolutePath().getParent();
        List<Path> files = new ArrayList<>();
        files.add(launchedJar.toAbsolutePath());
        for (String token : classPathAttribute(launchedJar)) {
            Path resolved;
            try {
                resolved = dir.resolve(new URI(token).getPath()).normalize();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Bad Class-Path entry '" + token + "' in " + launchedJar, e);
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalStateException(
                        "Class-Path entry '" + token + "' of " + launchedJar + " does not exist: " + resolved);
            }
            files.add(resolved);
        }
        List<ClasspathEntry> entries = new ArrayList<>();
        for (Path file : files) {
            try {
                entries.add(new ClasspathEntry(
                        dir.relativize(file).toString().replace('\\', '/'),
                        Files.size(file),
                        sha256(file),
                        Files.getLastModifiedTime(file).toInstant()));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
            }
        }
        return entries;
    }

    private static List<String> classPathAttribute(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.getManifest() == null) {
                return List.of();
            }
            String value = file.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            return value == null || value.isBlank() ? List.of() : List.of(value.trim().split("\\s+"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read manifest of " + jar + ": " + e.getMessage(), e);
        }
    }

    // ---- hashing and JSON access ----

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                md.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("Manifest field '" + what + "' must be an object");
    }

    private static String asString(Object o, String what) {
        if (o instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Manifest field '" + what + "' must be a string");
    }

    private static Object required(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Manifest is missing '" + key + "'");
        }
        return v;
    }

    private static String string(Map<String, Object> m, String key) {
        return asString(required(m, key), key);
    }

    private static long number(Map<String, Object> m, String key) {
        if (required(m, key) instanceof Long n) {
            return n;
        }
        throw new IllegalArgumentException("Manifest field '" + key + "' must be an integer");
    }

    private static Map<String, Object> object(Map<String, Object> m, String key) {
        return asObject(required(m, key), key);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Map<String, Object> m, String key) {
        if (required(m, key) instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException("Manifest field '" + key + "' must be an array");
    }
}
