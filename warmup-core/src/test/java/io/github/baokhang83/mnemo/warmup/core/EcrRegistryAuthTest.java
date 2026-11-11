package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EcrRegistryAuthTest {

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");
    private static final AwsCredentials KEYS = new AwsCredentials("ASIAEXAMPLE", "sekret-key", "sess-tok", null);

    private HttpServer ecr;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> targets = new CopyOnWriteArrayList<>();
    private volatile int[] script = {200};
    private volatile Instant expiry = T0.plus(Duration.ofHours(12));
    private volatile String password = "pw-1";
    private final MutableClock clock = new MutableClock(T0);
    private final AtomicInteger resolves = new AtomicInteger();

    static final class MutableClock extends Clock {
        volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void start() throws IOException {
        ecr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ecr.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization") + " | " + ex.getRequestHeaders().getFirst("X-Amz-Security-Token"));
            targets.add(ex.getRequestHeaders().getFirst("X-Amz-Target"));
            int n = calls.getAndIncrement();
            int status = script[Math.min(n, script.length - 1)];
            String token = Base64.getEncoder().encodeToString(("AWS:" + password).getBytes(StandardCharsets.UTF_8));
            String body = status == 200
                    ? "{\"authorizationData\":[{\"authorizationToken\":\"" + token + "\",\"expiresAt\":" + expiry.getEpochSecond() + ".0}]}"
                    : "{\"__type\":\"AccessDeniedException\",\"message\":\"not allowed to call ecr:GetAuthorizationToken\"}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        ecr.start();
    }

    @AfterEach
    void stop() {
        ecr.stop(0);
    }

    private EcrRegistryAuth auth() {
        URI endpoint = URI.create("http://127.0.0.1:" + ecr.getAddress().getPort() + "/");
        return new EcrRegistryAuth(() -> {
            resolves.incrementAndGet();
            return KEYS;
        }, "eu-west-1", endpoint, new AwsHttp(Duration.ofSeconds(5), 3, Duration.ofMillis(10)), clock);
    }

    @Test
    void exchangesAwsKeysForARegistryLogin() {
        RegistryCredentials c = auth().credentials().orElseThrow();
        assertEquals("AWS", c.username());
        assertEquals("pw-1", c.password());
        assertEquals("AmazonEC2ContainerRegistry_V20150921.GetAuthorizationToken", targets.get(0));
        String header = authHeaders.get(0);
        assertTrue(header.contains("Credential=ASIAEXAMPLE/20261111/eu-west-1/ecr/aws4_request"), header);
        assertTrue(header.contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token;x-amz-target"), header);
        assertTrue(header.endsWith("| sess-tok"), header);
    }

    @Test
    void reusesTheTokenUntilJustBeforeItExpires() {
        var auth = auth();
        auth.credentials();
        clock.now = T0.plus(Duration.ofHours(11));
        auth.credentials();
        assertEquals(1, calls.get());

        clock.now = T0.plus(Duration.ofHours(12)).minus(EcrRegistryAuth.SKEW).plusSeconds(1);
        password = "pw-2";
        assertEquals("pw-2", auth.credentials().orElseThrow().password());
        assertEquals(2, calls.get());
    }

    @Test
    void invalidateForcesANewTokenAndNewKeys() {
        var auth = auth();
        auth.credentials();
        auth.invalidate();
        auth.credentials();
        assertEquals(2, calls.get());
        assertEquals(2, resolves.get());
    }

    @Test
    void deniedPermissionsAreAStoreExceptionNamingTheMissingAction() {
        script = new int[] {400};
        var ex = assertThrows(StoreException.class, () -> auth().credentials());
        assertTrue(ex.getMessage().contains("GetAuthorizationToken") && ex.getMessage().contains("not allowed"), ex.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void transientFailuresAreRetried() {
        script = new int[] {500, 503, 200};
        assertEquals("pw-1", auth().credentials().orElseThrow().password());
        assertEquals(3, calls.get());
    }

    @Test
    void aFailedRefreshDoesNotLeaveAStaleTokenInUse() {
        var auth = auth();
        auth.credentials();
        clock.now = T0.plus(Duration.ofHours(13));
        script = new int[] {400};
        calls.set(0);
        assertThrows(StoreException.class, auth::credentials);
        assertThrows(StoreException.class, auth::credentials);
        assertEquals(2, calls.get(), "an expired token is never handed out; each call retries the refresh");
    }

    @Test
    void nothingSecretIsEverPrinted() {
        var auth = auth();
        RegistryCredentials c = auth.credentials().orElseThrow();
        assertFalse(c.toString().contains("pw-1"));
        script = new int[] {400};
        auth.invalidate();
        var ex = assertThrows(StoreException.class, auth::credentials);
        assertFalse(ex.getMessage().contains("sekret-key") || ex.getMessage().contains("sess-tok") || ex.getMessage().contains("pw-1"));
    }
}
