package io.github.baokhang83.mnemo.warmup.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Just enough of the Lease API (create, get, update with resourceVersion, delete) to test the lock. */
final class FakeKubeApi implements AutoCloseable {

    final Map<String, Map<String, Object>> leases = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong(100);
    volatile int forceStatus; // when set, every request answers with it
    private final HttpServer server;

    FakeKubeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    KubeApi api() {
        return KubeApi.at(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), java.time.Duration.ofSeconds(10));
    }

    @SuppressWarnings("unchecked")
    private synchronized void handle(HttpExchange ex) throws IOException {
        try {
            if (forceStatus != 0) {
                reply(ex, forceStatus, "{}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String[] parts = path.split("/");
            // /apis/coordination.k8s.io/v1/namespaces/<ns>/leases[/<name>]
            String name = parts.length > 7 ? parts[7] : null;
            String method = ex.getRequestMethod();
            byte[] raw = ex.getRequestBody().readAllBytes();
            Map<String, Object> body = raw.length == 0 ? null : (Map<String, Object>) Json.parse(new String(raw, StandardCharsets.UTF_8));
            if (name == null && method.equals("POST")) {
                String leaseName = (String) ((Map<String, Object>) body.get("metadata")).get("name");
                if (leases.containsKey(leaseName)) {
                    reply(ex, 409, "{}");
                } else {
                    reply(ex, 201, Json.writeCompact(store(leaseName, body)));
                }
            } else if (name != null && method.equals("GET")) {
                Map<String, Object> lease = leases.get(name);
                reply(ex, lease == null ? 404 : 200, lease == null ? "{}" : Json.writeCompact(lease));
            } else if (name != null && method.equals("PUT")) {
                Map<String, Object> stored = leases.get(name);
                String sent = (String) ((Map<String, Object>) body.get("metadata")).get("resourceVersion");
                if (stored == null) {
                    reply(ex, 404, "{}");
                } else if (!((Map<String, Object>) stored.get("metadata")).get("resourceVersion").equals(sent)) {
                    reply(ex, 409, "{}");
                } else {
                    reply(ex, 200, Json.writeCompact(store(name, body)));
                }
            } else if (name != null && method.equals("DELETE")) {
                Map<String, Object> stored = leases.get(name);
                String expected = body == null ? null : (String) ((Map<String, Object>) body.get("preconditions")).get("resourceVersion");
                if (stored == null) {
                    reply(ex, 404, "{}");
                } else if (expected != null && !expected.equals(((Map<String, Object>) stored.get("metadata")).get("resourceVersion"))) {
                    reply(ex, 409, "{}");
                } else {
                    leases.remove(name);
                    reply(ex, 200, "{}");
                }
            } else {
                reply(ex, 405, "{}");
            }
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> store(String name, Map<String, Object> body) {
        Map<String, Object> copy = (Map<String, Object>) Json.parse(Json.writeCompact(body));
        ((Map<String, Object>) copy.get("metadata")).put("resourceVersion", String.valueOf(versions.incrementAndGet()));
        leases.put(name, copy);
        return copy;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> spec(String name) {
        return (Map<String, Object>) leases.get(name).get("spec");
    }

    /** Rewrites a stored lease as another holder, renewed at {@code renewTime}. */
    @SuppressWarnings("unchecked")
    void setHolder(String name, String holder, String renewTime, long durationSeconds) {
        Map<String, Object> lease = leases.get(name);
        Map<String, Object> spec = (Map<String, Object>) lease.get("spec");
        spec.put("holderIdentity", holder);
        spec.put("renewTime", renewTime);
        spec.put("leaseDurationSeconds", durationSeconds);
        ((Map<String, Object>) lease.get("metadata")).put("resourceVersion", String.valueOf(versions.incrementAndGet()));
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static Map<String, Object> newMap() {
        return new LinkedHashMap<>();
    }
}
