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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebIdentityCredentialsTest {

    private static final String OK = "{\"AssumeRoleWithWebIdentityResponse\":{\"AssumeRoleWithWebIdentityResult\":{\"Credentials\":"
            + "{\"AccessKeyId\":\"ASIAEXAMPLE\",\"SecretAccessKey\":\"sekret-key\",\"SessionToken\":\"sess-tok\",\"Expiration\":1.7e9}}}}";

    @TempDir
    Path tmp;

    private HttpServer sts;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int[] script = {200};

    @BeforeEach
    void start() throws IOException {
        sts = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sts.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int n = calls.getAndIncrement();
            int status = script[Math.min(n, script.length - 1)];
            byte[] out = (status == 200 ? OK : "{\"Error\":{\"Code\":\"AccessDenied\",\"Message\":\"not authorized to assume\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        sts.start();
    }

    @AfterEach
    void stop() {
        sts.stop(0);
    }

    private WebIdentityCredentials source(Path tokenFile) {
        URI endpoint = URI.create("http://127.0.0.1:" + sts.getAddress().getPort() + "/");
        return new WebIdentityCredentials(tokenFile, "arn:aws:iam::123456789012:role/pull", "mnemo-warmup", endpoint,
                new AwsHttp(Duration.ofSeconds(5), 3, Duration.ofMillis(10)));
    }

    private Path token(String value) throws IOException {
        return Files.writeString(tmp.resolve("token"), value);
    }

    @Test
    void exchangesTheServiceAccountTokenForTemporaryKeys() throws Exception {
        AwsCredentials c = source(token("jwt.value.here\n")).resolve();
        assertEquals("ASIAEXAMPLE", c.accessKeyId());
        assertEquals("sess-tok", c.sessionToken());
        assertEquals(1_700_000_000L, c.expiration().getEpochSecond());
        String form = bodies.get(0);
        assertTrue(form.contains("Action=AssumeRoleWithWebIdentity"), form);
        assertTrue(form.contains("WebIdentityToken=jwt.value.here"), form);
        assertTrue(form.contains("RoleArn=arn%3Aaws%3Aiam%3A%3A123456789012%3Arole%2Fpull"), form);
    }

    @Test
    void rereadsTheTokenFileBecauseTheKubeletRotatesIt() throws Exception {
        Path file = token("first");
        var source = source(file);
        source.resolve();
        Files.writeString(file, "second");
        source.resolve();
        assertTrue(bodies.get(1).contains("WebIdentityToken=second"), bodies.get(1));
    }

    @Test
    void retriesTransientServerErrorsButNotDeniedPermissions() throws Exception {
        script = new int[] {503, 500, 200};
        assertEquals("ASIAEXAMPLE", source(token("t")).resolve().accessKeyId());
        assertEquals(3, calls.get());

        calls.set(0);
        script = new int[] {403};
        var ex = assertThrows(StoreException.class, () -> source(token("t")).resolve());
        assertEquals(1, calls.get(), "a denied request is an answer, not a transient failure");
        assertTrue(ex.getMessage().contains("403") && ex.getMessage().contains("not authorized"), ex.getMessage());
    }

    @Test
    void givesUpAfterTheConfiguredAttempts() throws Exception {
        script = new int[] {500};
        assertThrows(StoreException.class, () -> source(token("t")).resolve());
        assertEquals(3, calls.get());
    }

    @Test
    void missingOrEmptyTokenFilesAreStoreExceptionsThatNameTheStep() throws Exception {
        var missing = assertThrows(StoreException.class, () -> source(tmp.resolve("nope")).resolve());
        assertTrue(missing.getMessage().contains("token file"), missing.getMessage());
        assertThrows(StoreException.class, () -> source(token("  \n")).resolve());
        assertEquals(0, calls.get());
    }

    @Test
    void errorMessagesNeverContainTheTokenOrKeys() throws Exception {
        script = new int[] {403};
        var ex = assertThrows(StoreException.class, () -> source(token("super.secret.jwt")).resolve());
        assertFalse(ex.getMessage().contains("super.secret.jwt"), ex.getMessage());
        List<String> shown = new ArrayList<>(List.of(String.valueOf(ex.getMessage())));
        shown.forEach(s -> assertFalse(s.contains("sekret-key")));
    }

    @Test
    void anUnreachableEndpointIsAStoreException() throws Exception {
        var source = new WebIdentityCredentials(token("t"), "arn:aws:iam::1:role/r", "s", URI.create("http://127.0.0.1:1/"),
                new AwsHttp(Duration.ofSeconds(2), 2, Duration.ofMillis(10)));
        var ex = assertThrows(StoreException.class, source::resolve);
        assertTrue(ex.getMessage().contains("cannot reach"), ex.getMessage());
    }
}
