package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An {@link ArchiveStore} on an OCI registry (ADR 0001), written against the OCI distribution API
 * with the JDK's own HTTP client so the core library stays free of dependencies.
 *
 * <p>An archive is one artifact: the manifest JSON is the config blob, the archive file is the single
 * layer, and the tag is {@code fp-<hex>} (a fingerprint's {@code sha256:} colon is not valid in a tag).
 * Downloads are verified against the digests the registry's manifest declares, and the downloaded
 * manifest must hash back to the requested fingerprint.
 */
public final class OciArchiveStore implements ArchiveStore {

    static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
    static final String ARTIFACT_TYPE = "application/vnd.mnemo.warmup.v1";
    static final String CONFIG_TYPE = "application/vnd.mnemo.warmup.manifest.v1+json";
    static final String LAYER_TYPE = "application/vnd.mnemo.warmup.archive.v1";
    private static final String TITLE_ANNOTATION = "org.opencontainers.image.title";
    private static final String FINGERPRINT_PREFIX = "sha256:";
    private static final String TAG_PREFIX = "fp-";
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final int MAX_REDIRECTS = 5;

    private final URI base;
    private final String repository;
    private final RegistryCredentials credentials;
    private final Duration timeout;
    private final HttpClient http;
    private volatile String authorization;

    private OciArchiveStore(URI base, String repository, RegistryCredentials credentials, Duration timeout) {
        this.base = base;
        this.repository = repository;
        this.credentials = credentials;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param reference   {@code oci://host[:port]/repository/path}
     * @param plainHttp   use http instead of https (local registries and tests)
     * @param credentials for the registry, or {@code null} for anonymous access
     * @param timeout     per request; archives are large, so make it generous
     */
    public static OciArchiveStore of(String reference, boolean plainHttp, RegistryCredentials credentials, Duration timeout) {
        ParsedReference ref = parse(reference);
        URI base = URI.create((plainHttp ? "http://" : "https://") + ref.host());
        return new OciArchiveStore(base, ref.repository(), credentials, timeout);
    }

    /** The {@code host[:port]} of an {@code oci://} reference, for looking up credentials. */
    public static String registryHost(String reference) {
        return parse(reference).host();
    }

    private record ParsedReference(String host, String repository) {}

    private static ParsedReference parse(String reference) {
        if (reference == null || !reference.startsWith("oci://")) {
            throw new IllegalArgumentException("Store must look like oci://host[:port]/repository, got: " + reference);
        }
        String rest = reference.substring("oci://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("Store must look like oci://host[:port]/repository, got: " + reference);
        }
        String repository = rest.substring(slash + 1);
        if (!repository.matches("[a-z0-9]+([._/-][a-z0-9]+)*")) {
            throw new IllegalArgumentException("Not a valid repository name (lower-case a-z, 0-9, . _ - /): " + repository);
        }
        return new ParsedReference(rest.substring(0, slash), repository);
    }

    // ---- tag encoding ----

    /** {@code sha256:<64 hex>} to {@code fp-<64 hex>}. */
    public static String tagFor(String fingerprint) {
        if (fingerprint == null
                || !fingerprint.startsWith(FINGERPRINT_PREFIX)
                || !HEX64.matcher(fingerprint.substring(FINGERPRINT_PREFIX.length())).matches()) {
            throw new IllegalArgumentException("Not a fingerprint (sha256:<64 hex>): " + fingerprint);
        }
        return TAG_PREFIX + fingerprint.substring(FINGERPRINT_PREFIX.length());
    }

    /** The inverse of {@link #tagFor(String)}. */
    public static String fingerprintOf(String tag) {
        if (tag == null || !tag.startsWith(TAG_PREFIX) || !HEX64.matcher(tag.substring(TAG_PREFIX.length())).matches()) {
            throw new IllegalArgumentException("Not a fingerprint tag (fp-<64 hex>): " + tag);
        }
        return FINGERPRINT_PREFIX + tag.substring(TAG_PREFIX.length());
    }

    // ---- ArchiveStore ----

    @Override
    public boolean exists(String fingerprint) {
        String tag = tagFor(fingerprint);
        HttpResponse<byte[]> r = call("HEAD", manifestUri(tag), Map.of("Accept", MANIFEST_TYPE), null, BodyHandlers.ofByteArray(), "pull");
        return switch (r.statusCode()) {
            case 200 -> true;
            case 404 -> false;
            default -> throw failure("check", r);
        };
    }

    @Override
    public void put(ArchiveManifest manifest, Path archiveFile) {
        String tag = tagFor(manifest.fingerprint());
        try {
            byte[] config = manifest.toJson().getBytes(StandardCharsets.UTF_8);
            String configDigest = "sha256:" + hex(config);
            String archiveHex = hex(archiveFile);
            String archiveDigest = "sha256:" + archiveHex;
            long archiveSize = Files.size(archiveFile);
            ArchiveManifest.ArchiveInfo declared = manifest.archive();
            if (declared == null || declared.sizeBytes() != archiveSize || !declared.sha256().equals(archiveHex)) {
                throw new IllegalArgumentException(
                        "Archive " + archiveFile + " does not match its manifest (size or SHA-256 differs); rebuild before pushing");
            }

            uploadBlob(configDigest, () -> BodyPublishers.ofByteArray(config));
            uploadBlob(archiveDigest, () -> {
                try {
                    return BodyPublishers.ofFile(archiveFile);
                } catch (IOException e) {
                    throw new StoreException("Cannot read archive " + archiveFile + ": " + e.getMessage(), e);
                }
            });

            Map<String, Object> configDescriptor = descriptor(CONFIG_TYPE, configDigest, config.length);
            Map<String, Object> layer = descriptor(LAYER_TYPE, archiveDigest, archiveSize);
            layer.put("annotations", new LinkedHashMap<>(Map.of(TITLE_ANNOTATION, archiveFile.getFileName().toString())));
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("schemaVersion", 2L);
            image.put("mediaType", MANIFEST_TYPE);
            image.put("artifactType", ARTIFACT_TYPE);
            image.put("config", configDescriptor);
            image.put("layers", new ArrayList<Object>(List.of(layer)));
            byte[] body = Json.writeCompact(image).getBytes(StandardCharsets.UTF_8);

            HttpResponse<byte[]> r = call(
                    "PUT",
                    manifestUri(tag),
                    Map.of("Content-Type", MANIFEST_TYPE),
                    () -> BodyPublishers.ofByteArray(body),
                    BodyHandlers.ofByteArray(),
                    "pull,push");
            if (r.statusCode() != 201 && r.statusCode() != 200) {
                throw failure("publish manifest", r);
            }
        } catch (IOException e) {
            throw new StoreException("Cannot read archive " + archiveFile + ": " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<StoredArchive> get(String fingerprint, Path targetDir) {
        String tag = tagFor(fingerprint);
        HttpResponse<byte[]> r = call("GET", manifestUri(tag), Map.of("Accept", MANIFEST_TYPE), null, BodyHandlers.ofByteArray(), "pull");
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        if (r.statusCode() != 200) {
            throw failure("fetch manifest", r);
        }
        Map<String, Object> image;
        Map<String, Object> config;
        Map<String, Object> layer;
        try {
            image = (Map<String, Object>) Json.parse(new String(r.body(), StandardCharsets.UTF_8));
            config = (Map<String, Object>) image.get("config");
            List<Object> layers = (List<Object>) image.get("layers");
            if (!CONFIG_TYPE.equals(config.get("mediaType")) || layers.size() != 1) {
                throw new StoreException("Tag " + tag + " in " + repository + " is not a mnemo-warmup archive");
            }
            layer = (Map<String, Object>) layers.get(0);
            if (!LAYER_TYPE.equals(layer.get("mediaType"))) {
                throw new StoreException("Tag " + tag + " in " + repository + " is not a mnemo-warmup archive");
            }
        } catch (ClassCastException | NullPointerException | IllegalArgumentException e) {
            throw new StoreException("Registry returned a malformed manifest for " + tag, e);
        }

        String configDigest = String.valueOf(config.get("digest"));
        HttpResponse<byte[]> configResponse = call("GET", blobUri(configDigest), Map.of(), null, BodyHandlers.ofByteArray(), "pull");
        if (configResponse.statusCode() != 200) {
            throw failure("fetch config", configResponse);
        }
        if (!configDigest.equals("sha256:" + hex(configResponse.body()))) {
            throw new StoreException("Digest mismatch for the manifest blob of " + tag);
        }
        ArchiveManifest manifest;
        try {
            manifest = ArchiveManifest.fromJson(new String(configResponse.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new StoreException("Stored manifest for " + tag + " is unreadable: " + e.getMessage(), e);
        }
        if (!fingerprint.equals(manifest.fingerprint())) {
            throw new StoreException("Stored manifest for " + tag + " does not hash to that fingerprint");
        }

        String layerDigest = String.valueOf(layer.get("digest"));
        long layerSize = ((Number) layer.get("size")).longValue();
        String fileName = Path.of(manifest.archive() == null ? "archive" : manifest.archive().file()).getFileName().toString();
        Path target = targetDir.resolve(fileName);
        download(blobUri(layerDigest), layerDigest, layerSize, target);
        return Optional.of(new StoredArchive(manifest, target));
    }

    // ---- blobs ----

    private void uploadBlob(String digest, Supplier<BodyPublisher> body) {
        HttpResponse<byte[]> head = call("HEAD", blobUri(digest), Map.of(), null, BodyHandlers.ofByteArray(), "pull,push");
        if (head.statusCode() == 200) {
            return;
        }
        URI startUri = uri("/v2/" + repository + "/blobs/uploads/");
        HttpResponse<byte[]> start = call(
                "POST", startUri, Map.of(), () -> BodyPublishers.noBody(), BodyHandlers.ofByteArray(), "pull,push");
        if (start.statusCode() != 202) {
            throw failure("start upload", start);
        }
        String location = start.headers().firstValue("Location").orElseThrow(
                () -> new StoreException("Registry did not return an upload Location"));
        URI target = startUri.resolve(location);
        String joined = target + (target.getRawQuery() == null ? "?" : "&") + "digest=" + URLEncoder.encode(digest, StandardCharsets.UTF_8);
        HttpResponse<byte[]> put = call("PUT", URI.create(joined), Map.of("Content-Type", "application/octet-stream"), body, BodyHandlers.ofByteArray(), "pull,push");
        if (put.statusCode() != 201) {
            throw failure("upload blob", put);
        }
    }

    private void download(URI source, String digest, long size, Path target) {
        Path tmp = null;
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            tmp = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".part");
            HttpResponse<InputStream> r = call("GET", source, Map.of(), null, BodyHandlers.ofInputStream(), "pull");
            if (r.statusCode() != 200) {
                closeQuietly(r.body());
                throw failure("download archive", r);
            }
            MessageDigest md = sha256();
            long written = copyWithIdleLimit(new DigestInputStream(r.body(), md), tmp, size);
            if (written != size || !digest.equals("sha256:" + HexFormat.of().formatHex(md.digest()))) {
                throw new StoreException("Downloaded archive failed verification (size or digest mismatch)");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
        } catch (IOException e) {
            throw new StoreException("Cannot download the archive: " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /**
     * Copies the body to {@code target}, closing the stream if no byte arrives for {@link #timeout}
     * (a server that sends headers and then stalls must not hold the init container forever), and
     * refusing more than {@code expectedSize} bytes.
     */
    private long copyWithIdleLimit(InputStream in, Path target, long expectedSize) throws IOException {
        long[] lastProgress = {System.nanoTime()};
        boolean[] stalled = {false};
        var watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oci-download-watchdog");
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(50, Math.min(1000, timeout.toMillis() / 4));
        watchdog.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - lastProgress[0] > timeout.toNanos()) {
                stalled[0] = true;
                try {
                    in.close();
                } catch (IOException ignored) {
                    // the read below fails and reports the stall
                }
            }
        }, period, period, java.util.concurrent.TimeUnit.MILLISECONDS);
        long total = 0;
        try (in; var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) >= 0; ) {
                lastProgress[0] = System.nanoTime();
                total += n;
                if (total > expectedSize) {
                    throw new StoreException("Registry sent more bytes than the manifest declares");
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            if (stalled[0]) {
                throw new StoreException("Download stalled: no data for " + timeout.toSeconds() + " s", e);
            }
            throw e;
        } finally {
            watchdog.shutdownNow();
        }
        return total;
    }

    private static Map<String, Object> descriptor(String mediaType, String digest, long size) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("mediaType", mediaType);
        d.put("digest", digest);
        d.put("size", size);
        return d;
    }

    // ---- HTTP with auth and redirects ----

    private URI uri(String path) {
        return base.resolve(path);
    }

    private URI manifestUri(String tag) {
        return uri("/v2/" + repository + "/manifests/" + tag);
    }

    private URI blobUri(String digest) {
        if (!digest.matches("sha256:[0-9a-f]{64}")) {
            throw new StoreException("Registry returned an unsupported digest: " + digest);
        }
        return uri("/v2/" + repository + "/blobs/" + digest);
    }

    /**
     * Sends one logical request: answers a 401 challenge once with a (cached) token or Basic header, and
     * follows redirects for GET/HEAD without leaking the registry's Authorization header to other hosts.
     */
    private <T> HttpResponse<T> call(
            String method,
            URI uri,
            Map<String, String> headers,
            Supplier<BodyPublisher> body,
            BodyHandler<T> handler,
            String actions) {
        URI current = uri;
        boolean authenticated = false;
        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            checkTransport(base, current);
            boolean sameHost = sameOrigin(base, current);
            HttpResponse<T> r = send(method, current, headers, body, handler, sameHost);
            int status = r.statusCode();
            if (status == 401 && sameHost && !authenticated) {
                String challenge = r.headers().firstValue("WWW-Authenticate").orElse("");
                closeQuietly(r.body());
                authorize(challenge, actions);
                if (authorization == null) {
                    throw new StoreException("Registry " + base.getAuthority() + " requires authentication and no credentials were provided");
                }
                authenticated = true;
                hops--;
                continue;
            }
            boolean redirect = status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
            if (redirect && (method.equals("GET") || method.equals("HEAD"))) {
                String location = r.headers().firstValue("Location").orElse(null);
                closeQuietly(r.body());
                if (location == null) {
                    throw new StoreException("Registry redirected without a Location");
                }
                current = current.resolve(location);
                continue;
            }
            return r;
        }
        throw new StoreException("Too many redirects from " + base.getAuthority());
    }

    /** Same scheme, host and port: the only place the registry's Authorization header may go. */
    static boolean sameOrigin(URI registry, URI other) {
        return registry.getScheme().equalsIgnoreCase(other.getScheme())
                && registry.getAuthority().equalsIgnoreCase(other.getAuthority());
    }

    /** An https registry may never send us to a plain-http URL (redirect, upload location or token realm). */
    static void checkTransport(URI registry, URI target) {
        if ("https".equalsIgnoreCase(registry.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) {
            throw new StoreException("Refusing to follow " + registry.getAuthority() + " to a non-https URL");
        }
    }

    private <T> HttpResponse<T> send(
            String method, URI uri, Map<String, String> headers, Supplier<BodyPublisher> body, BodyHandler<T> handler, boolean sendAuth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(b::header);
        String auth = authorization;
        if (sendAuth && auth != null) {
            b.header("Authorization", auth);
        }
        b.method(method, body == null ? BodyPublishers.noBody() : body.get());
        return await(http.sendAsync(b.build(), handler), uri);
    }

    /** Waits for the whole exchange (including a buffered body) for at most {@link #timeout}. */
    private <T> HttpResponse<T> await(java.util.concurrent.CompletableFuture<HttpResponse<T>> future, URI uri) {
        try {
            return future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new StoreException("Registry " + uri.getAuthority() + " did not answer within " + timeout.toSeconds() + " s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new StoreException("Cannot reach registry " + uri.getAuthority() + ": " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new StoreException("Interrupted while talking to registry " + uri.getAuthority(), e);
        }
    }

    /** Sets {@link #authorization} from a {@code WWW-Authenticate} challenge. */
    private void authorize(String challenge, String actions) {
        if (challenge.regionMatches(true, 0, "Bearer", 0, 6)) {
            Map<String, String> params = new LinkedHashMap<>();
            Matcher m = PARAM.matcher(challenge);
            while (m.find()) {
                params.put(m.group(1).toLowerCase(), m.group(2));
            }
            String realm = params.get("realm");
            if (realm == null) {
                throw new StoreException("Registry sent a Bearer challenge without a realm");
            }
            checkTransport(base, URI.create(realm));
            String scope = params.getOrDefault("scope", "repository:" + repository + ":" + actions);
            StringBuilder url = new StringBuilder(realm).append(realm.contains("?") ? '&' : '?');
            if (params.containsKey("service")) {
                url.append("service=").append(URLEncoder.encode(params.get("service"), StandardCharsets.UTF_8)).append('&');
            }
            url.append("scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout);
            if (credentials != null) {
                b.header("Authorization", credentials.basicHeader());
            }
            HttpResponse<byte[]> r = await(
                    http.sendAsync(b.GET().build(), BodyHandlers.ofByteArray()), URI.create(realm));
            if (r.statusCode() != 200) {
                throw new StoreException("Registry token service refused the request (HTTP " + r.statusCode() + ")"
                        + (credentials == null ? "; no credentials were provided" : ""));
            }
            Object parsed;
            try {
                parsed = Json.parse(new String(r.body(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new StoreException("Registry token response is not valid JSON", e);
            }
            Object token = parsed instanceof Map<?, ?> map ? (map.get("token") != null ? map.get("token") : map.get("access_token")) : null;
            if (!(token instanceof String t) || t.isBlank()) {
                throw new StoreException("Registry token response has no token");
            }
            authorization = "Bearer " + t;
        } else if (challenge.regionMatches(true, 0, "Basic", 0, 5) && credentials != null) {
            authorization = credentials.basicHeader();
        } else {
            authorization = null;
        }
    }

    private StoreException failure(String what, HttpResponse<?> r) {
        return new StoreException("Registry " + base.getAuthority() + " could not " + what + " (HTTP " + r.statusCode() + ")");
    }

    private static void closeQuietly(Object body) {
        if (body instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    // ---- hashing ----

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static String hex(Path file) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                md.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
