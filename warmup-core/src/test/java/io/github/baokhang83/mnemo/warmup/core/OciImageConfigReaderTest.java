package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OciImageConfigReaderTest {
    private HttpServer server;
    private final Map<String, byte[]> responses = new HashMap<>();
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void startRegistry() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            byte[] body = responses.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopRegistry() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsOnlyTheVerifiedRuntimeConfigBlob() throws Exception {
        byte[] config = config("java", "-XX:AOTCache=/app/app.aot", "JAVA_TOOL_OPTIONS=-Dmode=prod");
        String digest = digest(config);
        responses.put(manifest("latest"), bytes(manifestBody(digest, config.length)));
        responses.put(blob(digest), config);

        var result = OciImageConfigReader.read(image(), RegistryAuth.anonymous(), Duration.ofSeconds(2), true);

        assertEquals(1, result.size());
        assertEquals(List.of("java"), result.get(0).entrypoint());
        assertEquals(List.of("-XX:AOTCache=/app/app.aot"), result.get(0).cmd());
        assertEquals("-Dmode=prod", result.get(0).env().get("JAVA_TOOL_OPTIONS"));
        assertFalse(result.get(0).toString().contains("/app/app.aot"));
        assertTrue(requests.contains(manifest("latest")));
        assertTrue(requests.contains(blob(digest)));
        assertEquals(2, requests.size(), "the inspector must not fetch image layers");
    }

    @Test
    void readsEachPlatformConfigFromAnImageIndex() throws Exception {
        byte[] first = config("java", "-XX:SharedArchiveFile=/app/app.jsa", "JAVA_TOOL_OPTIONS=-Darch=one");
        byte[] second = config("java", "-XX:AOTCache=/app/app.aot", "JAVA_TOOL_OPTIONS=-Darch=two");
        String firstConfig = digest(first);
        String secondConfig = digest(second);
        String firstManifest = digest(bytes(manifestBody(firstConfig, first.length)));
        String secondManifest = digest(bytes(manifestBody(secondConfig, second.length)));
        String index = "{\"schemaVersion\":2,\"manifests\":["
                + descriptor(firstManifest, "amd64") + "," + descriptor(secondManifest, "arm64") + "]}";
        responses.put(manifest("latest"), bytes(index));
        responses.put(manifest(firstManifest), bytes(manifestBody(firstConfig, first.length)));
        responses.put(manifest(secondManifest), bytes(manifestBody(secondConfig, second.length)));
        responses.put(blob(firstConfig), first);
        responses.put(blob(secondConfig), second);

        var result = OciImageConfigReader.read(image(), RegistryAuth.anonymous(), Duration.ofSeconds(2), true);

        assertEquals(2, result.size());
        assertEquals("-Darch=one", result.get(0).env().get("JAVA_TOOL_OPTIONS"));
        assertEquals("-Darch=two", result.get(1).env().get("JAVA_TOOL_OPTIONS"));
        assertEquals(5, requests.size());
        assertFalse(requests.stream().anyMatch(path -> path.contains("/layers/")));
    }

    @Test
    void rejectsConfigBytesThatDoNotMatchTheManifestDigest() throws Exception {
        byte[] config = config("java", "-XX:AOTCache=/app/cache", "JAVA_TOOL_OPTIONS=-Dmode=prod");
        String wrongDigest = "sha256:" + "0".repeat(64);
        responses.put(manifest("latest"), bytes(manifestBody(wrongDigest, config.length)));
        responses.put(blob(wrongDigest), config);

        assertThrows(StoreException.class,
                () -> OciImageConfigReader.read(image(), RegistryAuth.anonymous(), Duration.ofSeconds(2), true));
    }

    private String image() { return "127.0.0.1:" + server.getAddress().getPort() + "/example/app:latest"; }
    private String manifest(String selector) { return "/v2/example/app/manifests/" + selector; }
    private String blob(String digest) { return "/v2/example/app/blobs/" + digest; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static byte[] config(String entrypoint, String cmd, String env) {
        return bytes("{\"architecture\":\"amd64\",\"os\":\"linux\",\"config\":{" 
                + "\"Entrypoint\":[\"" + entrypoint + "\"],\"Cmd\":[\"" + cmd + "\"],"
                + "\"Env\":[\"" + env + "\"],\"Labels\":{\"test.label\":\"value\"}}}");
    }

    private static String manifestBody(String configDigest, int size) {
        return "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
                + "\"digest\":\"" + configDigest + "\",\"size\":" + size + "},\"layers\":[]}";
    }

    private static String descriptor(String manifestDigest, String architecture) {
        return "{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"digest\":\""
                + manifestDigest + "\",\"size\":1,\"platform\":{\"os\":\"linux\",\"architecture\":\""
                + architecture + "\"}}";
    }

    private static String digest(byte[] value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
