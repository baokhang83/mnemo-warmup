package io.github.baokhang83.mnemo.warmup.core;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, dependency-free OCI registry HTTP transport with challenge auth and safe redirects. */
final class OciRegistryHttp {
    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final int MAX_REDIRECTS = 5;

    private final URI base;
    private final String repository;
    private final RegistryAuth auth;
    private final Duration timeout;
    private final HttpClient http;
    private volatile String authorization;

    OciRegistryHttp(URI base, String repository, RegistryAuth auth, Duration timeout) {
        this.base = base;
        this.repository = repository;
        this.auth = auth;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    <T> HttpResponse<T> call(
            String method,
            URI uri,
            Map<String, String> headers,
            Supplier<BodyPublisher> body,
            BodyHandler<T> handler,
            String actions) {
        URI current = uri;
        boolean authenticated = false;
        boolean refreshed = false;
        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            checkTransport(base, current);
            boolean sameHost = sameOrigin(base, current);
            HttpResponse<T> response = send(method, current, headers, body, handler, sameHost);
            int status = response.statusCode();
            if (status == 401 && sameHost && authenticated && !refreshed) {
                String previous = authorization;
                auth.invalidate();
                authorize(response.headers().firstValue("WWW-Authenticate").orElse(""), actions);
                refreshed = true;
                if (authorization != null && !authorization.equals(previous)) {
                    closeQuietly(response.body());
                    hops--;
                    continue;
                }
                return response;
            }
            if (status == 401 && sameHost && !authenticated) {
                String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
                closeQuietly(response.body());
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
                String location = response.headers().firstValue("Location").orElse(null);
                closeQuietly(response.body());
                if (location == null) throw new StoreException("Registry redirected without a Location");
                current = current.resolve(location);
                continue;
            }
            return response;
        }
        throw new StoreException("Too many redirects from " + base.getAuthority());
    }

    static boolean sameOrigin(URI registry, URI other) {
        return registry.getScheme().equalsIgnoreCase(other.getScheme())
                && registry.getAuthority().equalsIgnoreCase(other.getAuthority());
    }

    static void checkTransport(URI registry, URI target) {
        if ("https".equalsIgnoreCase(registry.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) {
            throw new StoreException("Refusing to follow " + registry.getAuthority() + " to a non-https URL");
        }
    }

    private <T> HttpResponse<T> send(
            String method, URI uri, Map<String, String> headers, Supplier<BodyPublisher> body, BodyHandler<T> handler, boolean sendAuth) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(request::header);
        String value = authorization;
        if (sendAuth && value != null) request.header("Authorization", value);
        request.method(method, body == null ? BodyPublishers.noBody() : body.get());
        return await(http.sendAsync(request.build(), handler), uri);
    }

    private <T> HttpResponse<T> await(CompletableFuture<HttpResponse<T>> future, URI uri) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StoreException("Registry " + uri.getAuthority() + " did not answer within " + timeout.toSeconds() + " s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new StoreException("Cannot reach registry " + uri.getAuthority() + ": " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new StoreException("Interrupted while talking to registry " + uri.getAuthority(), e);
        }
    }

    private void authorize(String challenge, String actions) {
        RegistryCredentials credentials = auth.credentials().orElse(null);
        if (challenge.regionMatches(true, 0, "Bearer", 0, 6)) {
            Map<String, String> params = new LinkedHashMap<>();
            Matcher matcher = PARAM.matcher(challenge);
            while (matcher.find()) params.put(matcher.group(1).toLowerCase(), matcher.group(2));
            String realm = params.get("realm");
            if (realm == null) throw new StoreException("Registry sent a Bearer challenge without a realm");
            checkTransport(base, URI.create(realm));
            String scope = params.getOrDefault("scope", "repository:" + repository + ":" + actions);
            StringBuilder target = new StringBuilder(realm).append(realm.contains("?") ? '&' : '?');
            if (params.containsKey("service")) {
                target.append("service=").append(URLEncoder.encode(params.get("service"), StandardCharsets.UTF_8)).append('&');
            }
            target.append("scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
            URI tokenUri = URI.create(target.toString());
            HttpRequest.Builder request = HttpRequest.newBuilder(tokenUri).timeout(timeout);
            if (credentials != null) request.header("Authorization", credentials.basicHeader());
            HttpResponse<byte[]> response = await(http.sendAsync(request.GET().build(), BodyHandlers.ofByteArray()), tokenUri);
            if (response.statusCode() != 200) {
                auth.invalidate();
                throw new StoreException("Registry token service refused the request (HTTP " + response.statusCode() + ")"
                        + (credentials == null ? "; no credentials were provided" : ""));
            }
            Object parsed;
            try {
                parsed = Json.parse(new String(response.body(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new StoreException("Registry token response is not valid JSON", e);
            }
            Object token = parsed instanceof Map<?, ?> map ? (map.get("token") != null ? map.get("token") : map.get("access_token")) : null;
            if (!(token instanceof String value) || value.isBlank()) throw new StoreException("Registry token response has no token");
            authorization = "Bearer " + value;
        } else if (challenge.regionMatches(true, 0, "Basic", 0, 5) && credentials != null) {
            authorization = credentials.basicHeader();
        } else {
            authorization = null;
        }
    }

    private static void closeQuietly(Object body) {
        if (body instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { /* nothing to close */ }
        }
    }
}
