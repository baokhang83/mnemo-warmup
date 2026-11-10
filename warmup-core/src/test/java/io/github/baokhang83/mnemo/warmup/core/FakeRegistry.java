package io.github.baokhang83.mnemo.warmup.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A just-enough OCI registry for tests: blobs, manifests, optional bearer auth, redirects and corruption. */
final class FakeRegistry implements AutoCloseable {

    static final String USER = "robot";
    static final String PASSWORD = "s3cret";
    static final String TOKEN = "tok-123";

    final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    final Map<String, byte[]> manifests = new ConcurrentHashMap<>();
    final AtomicInteger tokenRequests = new AtomicInteger();
    volatile boolean requireAuth;
    volatile boolean redirectBlobs;
    volatile boolean stallBlobs;
    /** absolute-path (default), relative ("u1") or query ("?_state=x"). */
    volatile String locationStyle = "absolute-path";
    private final HttpServer server;

    FakeRegistry() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String host() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    String reference(String repository) {
        return "oci://" + host() + "/" + repository;
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if (path.equals("/token")) {
                tokenRequests.incrementAndGet();
                String expected = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    reply(ex, 401, new byte[0]);
                } else {
                    reply(ex, 200, ("{\"token\":\"" + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (path.startsWith("/redirected/")) {
                if (ex.getRequestHeaders().getFirst("Authorization") != null) {
                    // the real storage backend would reject a registry token; keep the check explicit
                    reply(ex, 400, new byte[0]);
                    return;
                }
                serveBlob(ex, method, "sha256:" + path.substring("/redirected/sha256:".length()), false);
                return;
            }
            if (requireAuth && !("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().add(
                        "WWW-Authenticate",
                        "Bearer realm=\"http://" + host() + "/token\",service=\"fake\",scope=\"repository:test/archives:pull,push\"");
                reply(ex, 401, new byte[0]);
                return;
            }
            if (path.endsWith("/blobs/uploads/") && method.equals("POST")) {
                String location = switch (locationStyle) {
                    case "relative" -> "u1";
                    case "query" -> "?_state=x";
                    default -> "/v2/test/archives/blobs/uploads/u1";
                };
                ex.getResponseHeaders().add("Location", location);
                reply(ex, 202, new byte[0]);
            } else if (path.contains("/blobs/uploads/") && method.equals("PUT")) {
                String digest = query(ex, "digest");
                byte[] body = ex.getRequestBody().readAllBytes();
                if (!digest.equals("sha256:" + HexFormat.of().formatHex(sha(body)))) {
                    reply(ex, 400, new byte[0]);
                } else {
                    blobs.put(digest, body);
                    reply(ex, 201, new byte[0]);
                }
            } else if (path.contains("/blobs/")) {
                serveBlob(ex, method, path.substring(path.indexOf("/blobs/") + "/blobs/".length()), redirectBlobs);
            } else if (path.contains("/manifests/")) {
                String tag = path.substring(path.indexOf("/manifests/") + "/manifests/".length());
                switch (method) {
                    case "PUT" -> {
                        manifests.put(tag, ex.getRequestBody().readAllBytes());
                        reply(ex, 201, new byte[0]);
                    }
                    case "GET", "HEAD" -> {
                        byte[] m = manifests.get(tag);
                        reply(ex, m == null ? 404 : 200, m == null ? new byte[0] : m);
                    }
                    default -> reply(ex, 405, new byte[0]);
                }
            } else {
                reply(ex, 404, new byte[0]);
            }
        } finally {
            ex.close();
        }
    }

    private void serveBlob(HttpExchange ex, String method, String digest, boolean redirect) throws IOException {
        byte[] blob = blobs.get(digest);
        if (blob == null) {
            reply(ex, 404, new byte[0]);
        } else if (redirect && method.equals("GET")) {
            ex.getResponseHeaders().add("Location", "/redirected/" + digest);
            reply(ex, 307, new byte[0]);
        } else if (stallBlobs && method.equals("GET") && blob.length > 1000) {
            ex.sendResponseHeaders(200, blob.length);
            ex.getResponseBody().flush();
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            reply(ex, 200, blob);
        }
    }

    private static String query(HttpExchange ex, String key) {
        for (String pair : ex.getRequestURI().getRawQuery().split("&")) {
            if (pair.startsWith(key + "=")) {
                return java.net.URLDecoder.decode(pair.substring(key.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void reply(HttpExchange ex, int status, byte[] body) throws IOException {
        boolean head = ex.getRequestMethod().equals("HEAD");
        if (head) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
        }
        ex.sendResponseHeaders(status, head ? -1 : (body.length == 0 ? -1 : body.length));
        if (!head && body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static byte[] sha(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
