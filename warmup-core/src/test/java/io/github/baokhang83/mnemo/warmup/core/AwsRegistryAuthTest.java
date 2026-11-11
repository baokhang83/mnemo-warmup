package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AwsRegistryAuthTest {

    private static final String ECR_HOST = "123456789012.dkr.ecr.eu-west-1.amazonaws.com";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private HttpServer aws;
    private final List<String> actions = new CopyOnWriteArrayList<>();

    /** One fake serving both STS (form posts) and ECR (JSON target header). */
    @BeforeEach
    void start() throws Exception {
        aws = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        aws.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String target = ex.getRequestHeaders().getFirst("X-Amz-Target");
            String out;
            if (target != null) {
                actions.add("ecr:" + ex.getRequestHeaders().getFirst("X-Amz-Security-Token"));
                String pw = Base64.getEncoder().encodeToString("AWS:registry-pw".getBytes(StandardCharsets.UTF_8));
                out = "{\"authorizationData\":[{\"authorizationToken\":\"" + pw + "\",\"expiresAt\":1795000000.0}]}";
            } else if (body.contains("AssumeRoleWithWebIdentity")) {
                actions.add("web-identity");
                out = sts("AssumeRoleWithWebIdentity", "ASIAWEB", "web-token");
            } else {
                actions.add("assume-role:" + body.contains("role%2Fpush"));
                out = sts("AssumeRole", "ASIAHOP", "hop-token");
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        aws.start();
    }

    @AfterEach
    void stop() {
        aws.stop(0);
    }

    private static String sts(String action, String key, String token) {
        return "{\"" + action + "Response\":{\"" + action + "Result\":{\"Credentials\":{\"AccessKeyId\":\"" + key
                + "\",\"SecretAccessKey\":\"s\",\"SessionToken\":\"" + token + "\",\"Expiration\":1795000000}}}}";
    }

    private Map<String, String> env(String... extra) throws Exception {
        Path token = Files.writeString(tmp.resolve("token"), "jwt");
        var env = new java.util.HashMap<>(Map.of(
                "AWS_ROLE_ARN", "arn:aws:iam::123456789012:role/pull",
                "AWS_WEB_IDENTITY_TOKEN_FILE", token.toString(),
                "AWS_ENDPOINT_URL_STS", "http://127.0.0.1:" + aws.getAddress().getPort(),
                "AWS_ENDPOINT_URL_ECR", "http://127.0.0.1:" + aws.getAddress().getPort()));
        for (int i = 0; i < extra.length; i += 2) {
            env.put(extra[i], extra[i + 1]);
        }
        return env;
    }

    @Test
    void webIdentityFeedsEcrWhenEksInjectedIt() throws Exception {
        var auth = AwsRegistryAuth.create(ECR_HOST, env(), CLOCK, 1, Duration.ofMillis(1)).orElseThrow();
        RegistryCredentials c = auth.credentials().orElseThrow();
        assertEquals("AWS", c.username());
        assertEquals("registry-pw", c.password());
        assertEquals(List.of("web-identity", "ecr:web-token"), actions);
    }

    @Test
    void anOptionalSecondRoleIsAssumedWithTheWebIdentityKeys() throws Exception {
        var auth = AwsRegistryAuth.create(ECR_HOST,
                env("MNEMO_WARMUP_AWS_ROLE_ARN", "arn:aws:iam::123456789012:role/push"), CLOCK, 1, Duration.ofMillis(1)).orElseThrow();
        auth.credentials();
        assertEquals(List.of("web-identity", "assume-role:true", "ecr:hop-token"), actions);
    }

    @Test
    void staysOutOfTheWayWhenThereIsNothingToUse() throws Exception {
        assertTrue(AwsRegistryAuth.create("ghcr.io", env(), CLOCK, 1, Duration.ofMillis(1)).isEmpty(), "not an ECR host");
        assertTrue(AwsRegistryAuth.create(ECR_HOST, Map.of(), CLOCK, 1, Duration.ofMillis(1)).isEmpty(), "no web identity in the pod");
        assertTrue(AwsRegistryAuth.create(ECR_HOST, Map.of("AWS_ROLE_ARN", "x"), CLOCK, 1, Duration.ofMillis(1)).isEmpty());
    }

    @Test
    void aMissingTokenFileSurfacesAsAStoreExceptionOnUseNotAtConstruction() throws Exception {
        var auth = AwsRegistryAuth.create(ECR_HOST,
                env("AWS_WEB_IDENTITY_TOKEN_FILE", tmp.resolve("gone").toString()), CLOCK, 1, Duration.ofMillis(1)).orElseThrow();
        assertThrows(StoreException.class, auth::credentials);
    }
}
