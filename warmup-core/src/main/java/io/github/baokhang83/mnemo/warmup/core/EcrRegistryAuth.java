package io.github.baokhang83.mnemo.warmup.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry login for Amazon ECR from the pod's AWS identity. {@code GetAuthorizationToken} (SigV4-signed with
 * the temporary keys) returns a base64 {@code AWS:<password>} valid for about 12 hours; it is cached and
 * re-requested shortly before it expires, or at once after {@link #invalidate()}.
 */
final class EcrRegistryAuth implements RegistryAuth {

    /** Refresh this long before expiry so a request never starts with a token about to die. */
    static final Duration SKEW = Duration.ofMinutes(5);
    /** Used when ECR gives no usable expiry. */
    private static final Duration FALLBACK_LIFETIME = Duration.ofHours(1);
    private static final String TARGET = "AmazonEC2ContainerRegistry_V20150921.GetAuthorizationToken";

    private final AwsCredentialsSource source;
    private final String region;
    private final URI ecrEndpoint;
    private final AwsHttp http;
    private final Clock clock;
    private RegistryCredentials cached;
    private Instant refreshAt = Instant.MIN;

    EcrRegistryAuth(AwsCredentialsSource source, String region, URI ecrEndpoint, AwsHttp http, Clock clock) {
        this.source = source;
        this.region = region;
        this.ecrEndpoint = ecrEndpoint;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public synchronized Optional<RegistryCredentials> credentials() {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(refreshAt)) {
            cached = fetch(now);
        }
        return Optional.of(cached);
    }

    @Override
    public synchronized void invalidate() {
        cached = null;
        refreshAt = Instant.MIN;
    }

    private RegistryCredentials fetch(Instant now) {
        AwsCredentials keys = source.resolve();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-amz-json-1.1");
        headers.put("X-Amz-Target", TARGET);
        Map<String, String> request = new LinkedHashMap<>(headers);
        request.putAll(SigV4Signer.sign("POST", ecrEndpoint, headers, body, keys, region, "ecr", now));
        String json = http.post("ECR GetAuthorizationToken", ecrEndpoint, request, body);

        Object root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            throw new StoreException("ECR GetAuthorizationToken response is not valid JSON", e);
        }
        if (!(root instanceof Map<?, ?> top)
                || !(top.get("authorizationData") instanceof List<?> data)
                || data.isEmpty()
                || !(data.get(0) instanceof Map<?, ?> first)
                || !(first.get("authorizationToken") instanceof String token)) {
            throw new StoreException("ECR GetAuthorizationToken response has no authorization token");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new StoreException("ECR authorization token is not valid base64");
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            throw new StoreException("ECR authorization token has an unexpected format");
        }
        Instant expires = first.get("expiresAt") instanceof Number n
                ? Instant.ofEpochMilli((long) (n.doubleValue() * 1000))
                : now.plus(FALLBACK_LIFETIME);
        refreshAt = expires.minus(SKEW);
        return new RegistryCredentials(decoded.substring(0, colon), decoded.substring(colon + 1));
    }
}
