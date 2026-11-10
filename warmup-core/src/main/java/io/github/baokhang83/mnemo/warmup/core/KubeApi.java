package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The few Kubernetes REST calls the trainer needs, on the JDK HTTP client so the core library stays free of
 * dependencies (the same reason as the OCI client, ADR 0001). Authenticates with the pod's service-account
 * token, which is re-read on every request because projected tokens rotate.
 */
final class KubeApi {

    static final Path SERVICE_ACCOUNT = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");

    record Response(int status, String body) {
        Map<String, Object> json() {
            try {
                Object parsed = Json.parse(body);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                return map;
            } catch (RuntimeException e) {
                throw new StoreException("Kubernetes API returned unreadable JSON (HTTP " + status + ")", e);
            }
        }
    }

    private final URI base;
    private final Supplier<String> token;
    private final HttpClient http;
    private final Duration timeout;

    private KubeApi(URI base, Supplier<String> token, SSLContext ssl, Duration timeout) {
        this.base = base;
        this.token = token;
        this.timeout = timeout;
        // HTTP/1.1 only: on a plain http:// URL the JDK client tries an h2c upgrade, which `kubectl proxy` answers
        // with a 500 (found against a real API server; invisible to a test double).
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10));
        if (ssl != null) {
            builder.sslContext(ssl);
        }
        this.http = builder.build();
    }

    /** Talks to the API server of the cluster this pod runs in. */
    static KubeApi inCluster(Duration timeout) {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv().getOrDefault("KUBERNETES_SERVICE_PORT", "443");
        if (host == null || host.isBlank()) {
            throw new StoreException("Not running in a Kubernetes pod (KUBERNETES_SERVICE_HOST is not set)");
        }
        Path tokenFile = SERVICE_ACCOUNT.resolve("token");
        Path caFile = SERVICE_ACCOUNT.resolve("ca.crt");
        if (!Files.isReadable(tokenFile) || !Files.isReadable(caFile)) {
            throw new StoreException("No service-account credentials under " + SERVICE_ACCOUNT
                    + " (automountServiceAccountToken must be true for the trainer)");
        }
        String authority = host.contains(":") ? "[" + host + "]" : host;
        return new KubeApi(URI.create("https://" + authority + ":" + port), () -> readToken(tokenFile), trust(caFile), timeout);
    }

    /** Talks to an API server at {@code base} without credentials: {@code kubectl proxy}, or a test double. */
    static KubeApi at(URI base, Duration timeout) {
        return new KubeApi(base, null, null, timeout);
    }

    Response request(String method, String path, Map<String, Object> body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("Accept", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token.get());
        }
        if (body == null) {
            b.method(method, BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, BodyPublishers.ofString(Json.writeCompact(body), StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> r = http.send(b.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new StoreException("Cannot reach the Kubernetes API at " + base.getAuthority() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("Interrupted while calling the Kubernetes API", e);
        }
    }

    private static String readToken(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new StoreException("Cannot read the service-account token: " + e.getMessage(), e);
        }
    }

    private static SSLContext trust(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("kube-ca-" + i++, cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new StoreException("Cannot load the cluster CA from " + caFile + ": " + e.getMessage(), e);
        }
    }
}
