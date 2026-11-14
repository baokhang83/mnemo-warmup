package io.github.baokhang83.mnemo.warmup.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads the small runtime config blob from an OCI image without downloading its layers. */
public final class OciImageConfigReader {
    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    private static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";
    private static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
    private static final String DOCKER_INDEX = "application/vnd.docker.distribution.manifest.list.v2+json";
    private static final String OCI_CONFIG = "application/vnd.oci.image.config.v1+json";
    private static final String DOCKER_CONFIG = "application/vnd.docker.container.image.v1+json";
    private static final Pattern REPOSITORY = Pattern.compile("[a-z0-9]+([._/-][a-z0-9]+)*");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PLATFORMS = 32;

    private OciImageConfigReader() {}

    /** Registry metadata lookup. The call reads manifests and config blobs, never image layers. */
    public static List<ImageConfig> read(String image, RegistryAuth auth, Duration timeout) {
        return read(image, auth, timeout, false);
    }

    /** Registry authority for auth lookup; no credentials are included in an image reference. */
    public static String registryHost(String image) { return Reference.parse(image).host(); }

    static List<ImageConfig> read(String image, RegistryAuth auth, Duration timeout, boolean plainHttp) {
        Reference reference = Reference.parse(image);
        URI base = URI.create((plainHttp ? "http://" : "https://") + reference.host());
        OciRegistryHttp registry = new OciRegistryHttp(base, reference.repository(), auth, timeout);
        byte[] manifest = get(registry, base, reference, reference.selector(), manifestAccept(), false);
        Object parsed = parseJson(manifest, "image manifest");
        if (!(parsed instanceof Map<?, ?> object)) throw new StoreException("Registry returned a malformed image manifest");

        List<String> manifests = manifestDigests(object);
        if (manifests.isEmpty()) return List.of(readConfig(registry, base, reference, object));
        if (manifests.size() > MAX_PLATFORMS) throw new StoreException("Image index has too many platform manifests to inspect");
        List<ImageConfig> configs = new ArrayList<>();
        for (String digest : manifests) {
            Object child = parseJson(get(registry, base, reference, digest, manifestAccept(), false), "platform manifest");
            if (!(child instanceof Map<?, ?> childManifest)) throw new StoreException("Registry returned a malformed platform manifest");
            configs.add(readConfig(registry, base, reference, childManifest));
        }
        if (configs.isEmpty()) throw new StoreException("Image index has no platform manifests to inspect");
        return List.copyOf(configs);
    }

    private static String manifestAccept() {
        return String.join(", ", OCI_MANIFEST, DOCKER_MANIFEST, OCI_INDEX, DOCKER_INDEX);
    }

    private static List<String> manifestDigests(Map<?, ?> manifest) {
        Object descriptors = manifest.get("manifests");
        if (!(descriptors instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> descriptor)) throw new StoreException("Image index contains a malformed descriptor");
            // Indexes can include non-image artifacts such as attestations. They have no platform config.
            if (!(descriptor.get("platform") instanceof Map<?, ?> platform)
                    || !(platform.get("os") instanceof String)
                    || !(platform.get("architecture") instanceof String)) continue;
            String digest = String.valueOf(descriptor.get("digest"));
            if (!DIGEST.matcher(digest).matches()) throw new StoreException("Image index contains an unsupported manifest digest");
            result.add(digest);
        }
        return result;
    }

    private static ImageConfig readConfig(OciRegistryHttp registry, URI base, Reference reference, Map<?, ?> manifest) {
        Object descriptorValue = manifest.get("config");
        if (!(descriptorValue instanceof Map<?, ?> descriptor)) throw new StoreException("Image manifest has no config descriptor");
        Object mediaType = descriptor.get("mediaType");
        if (!OCI_CONFIG.equals(mediaType) && !DOCKER_CONFIG.equals(mediaType)) {
            throw new StoreException("Image manifest has an unsupported config media type");
        }
        String digest = String.valueOf(descriptor.get("digest"));
        if (!DIGEST.matcher(digest).matches()) throw new StoreException("Image config has an unsupported digest");
        byte[] config = get(registry, base, reference, digest, null, true);
        if (!digest.equals("sha256:" + sha256(config))) throw new StoreException("Image config digest does not match its descriptor");
        Object parsed = parseJson(config, "image config");
        if (!(parsed instanceof Map<?, ?> image) || !(image.get("config") instanceof Map<?, ?> runtime)) {
            throw new StoreException("Image config has no runtime settings");
        }
        return new ImageConfig(strings(runtime.get("Entrypoint")), strings(runtime.get("Cmd")), environment(runtime.get("Env")), labels(runtime.get("Labels")));
    }

    private static List<String> strings(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new StoreException("Image config contains a non-array command field");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) throw new StoreException("Image config contains a non-string command value");
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> environment(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof List<?> list)) throw new StoreException("Image config contains a non-array Env field");
        Map<String, String> result = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof String entry)) throw new StoreException("Image config contains a non-string Env value");
            int equals = entry.indexOf('=');
            if (equals > 0) result.put(entry.substring(0, equals), entry.substring(equals + 1));
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> labels(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw new StoreException("Image config contains a non-object Labels field");
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key instanceof String name && item instanceof String text) result.put(name, text);
        });
        return Map.copyOf(result);
    }

    private static byte[] get(OciRegistryHttp registry, URI base, Reference reference, String selector, String accept, boolean blob) {
        URI uri = base.resolve("/v2/" + reference.repository() + "/" + (blob ? "blobs/" : "manifests/") + selector);
        Map<String, String> headers = accept == null ? Map.of() : Map.of("Accept", accept);
        HttpResponse<InputStream> response = registry.call("GET", uri, headers, null, BodyHandlers.ofInputStream(), "pull");
        if (response.statusCode() != 200) {
            close(response.body());
            throw new StoreException("Registry " + reference.host() + " could not read image metadata (HTTP " + response.statusCode() + ")");
        }
        return readBounded(response.body());
    }

    private static byte[] readBounded(InputStream input) {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1;) {
                if (out.size() + read > MAX_JSON_BYTES) throw new StoreException("Image metadata exceeds the 4 MiB safety limit");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new StoreException("Cannot read image metadata: " + e.getMessage(), e);
        }
    }

    private static Object parseJson(byte[] bytes, String what) {
        try {
            return Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new StoreException("Registry returned invalid " + what + " JSON", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void close(InputStream input) {
        try { input.close(); } catch (IOException ignored) { /* no content to retain */ }
    }

    private record Reference(String host, String repository, String selector) {
        static Reference parse(String image) {
            if (image == null || image.isBlank() || image.contains("://") || image.chars().anyMatch(Character::isWhitespace)) {
                throw new StoreException("Image must be a registry reference without a URL scheme");
            }
            String name = image;
            String selector = "latest";
            int at = name.lastIndexOf('@');
            if (at >= 0) {
                selector = name.substring(at + 1);
                name = name.substring(0, at);
                if (!DIGEST.matcher(selector).matches()) throw new StoreException("Image digest must use sha256:<64 lowercase hex>");
            } else {
                int slash = name.lastIndexOf('/');
                int colon = name.lastIndexOf(':');
                if (colon > slash) {
                    selector = name.substring(colon + 1);
                    name = name.substring(0, colon);
                    if (!TAG.matcher(selector).matches()) throw new StoreException("Image tag is invalid");
                }
            }
            int slash = name.indexOf('/');
            String first = slash < 0 ? name : name.substring(0, slash);
            boolean includesHost = first.contains(".") || first.contains(":") || first.equals("localhost");
            String host = includesHost ? first : "registry-1.docker.io";
            String repository = includesHost ? (slash < 0 ? "" : name.substring(slash + 1)) : name;
            if (!includesHost && !repository.contains("/")) repository = "library/" + repository;
            if (!host.matches("[A-Za-z0-9.-]+(:[0-9]{1,5})?") || !REPOSITORY.matcher(repository).matches()) {
                throw new StoreException("Image reference must contain a valid registry and repository");
            }
            return new Reference(host, repository, selector);
        }
    }

    /** Runtime defaults from one platform-specific OCI image config. Never print these values. */
    public record ImageConfig(List<String> entrypoint, List<String> cmd, Map<String, String> env, Map<String, String> labels) {
        public ImageConfig {
            entrypoint = List.copyOf(entrypoint);
            cmd = List.copyOf(cmd);
            env = Map.copyOf(env);
            labels = Map.copyOf(labels);
        }

        @Override public String toString() { return "ImageConfig[entrypoint=<redacted>, cmd=<redacted>, env=<redacted>, labels=<redacted>]"; }
    }
}
