package io.github.baokhang83.mnemo.warmup.core;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Chooses the ECR login for a registry from the pod's environment. It applies only to an ECR host (the region
 * comes from the host name) when EKS has injected a web identity ({@code AWS_ROLE_ARN} and
 * {@code AWS_WEB_IDENTITY_TOKEN_FILE}); otherwise it returns empty and the caller stays anonymous.
 *
 * <p>{@code MNEMO_WARMUP_AWS_ROLE_ARN} adds a second hop: assume that role with the web identity keys (the
 * pull-only fetch identity and the push-capable trainer identity are separate roles). {@code
 * AWS_ENDPOINT_URL_STS} and {@code AWS_ENDPOINT_URL_ECR} override the endpoints (VPC endpoints, tests).
 */
public final class AwsRegistryAuth {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private AwsRegistryAuth() {}

    public static Optional<RegistryAuth> fromEnvironment(String registryHost, Map<String, String> env) {
        return create(registryHost, env, Clock.systemUTC(), 3, Duration.ofMillis(200));
    }

    static Optional<RegistryAuth> create(String registryHost, Map<String, String> env, Clock clock, int attempts, Duration backoff) {
        Optional<String> region = AwsEndpoints.ecrRegion(registryHost);
        String roleArn = blankToNull(env.get("AWS_ROLE_ARN"));
        String tokenFile = blankToNull(env.get("AWS_WEB_IDENTITY_TOKEN_FILE"));
        if (region.isEmpty() || roleArn == null || tokenFile == null) {
            return Optional.empty();
        }
        String r = region.get();
        String session = Optional.ofNullable(blankToNull(env.get("AWS_ROLE_SESSION_NAME"))).orElse("mnemo-warmup");
        AwsHttp http = new AwsHttp(REQUEST_TIMEOUT, attempts, backoff);
        URI sts = endpoint(env.get("AWS_ENDPOINT_URL_STS"), AwsEndpoints.sts(r));
        URI ecr = endpoint(env.get("AWS_ENDPOINT_URL_ECR"), AwsEndpoints.ecrApi(r));

        AwsCredentialsSource source = new WebIdentityCredentials(Path.of(tokenFile), roleArn, session, sts, http);
        String hop = blankToNull(env.get("MNEMO_WARMUP_AWS_ROLE_ARN"));
        if (hop != null) {
            source = new AssumedRoleCredentials(source, hop, session, sts, r, http, clock);
        }
        return Optional.of(new EcrRegistryAuth(source, r, ecr, http, clock));
    }

    private static URI endpoint(String override, URI standard) {
        String value = blankToNull(override);
        if (value == null) {
            return standard;
        }
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
