package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Optional integration test against a local Moto server; normal CI does not need Moto. */
class MotoEcrAuthTest {
    @Test
    void ecrTokenFromMotoAnswersARegistryBasicChallenge() throws Exception {
        String endpoint = System.getenv("MNEMO_MOTO_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "set MNEMO_MOTO_ENDPOINT to a local Moto server");
        var source = (AwsCredentialsSource) () -> new AwsCredentials("testing", "testing", null, null);
        var auth = new EcrRegistryAuth(source, "eu-west-1", URI.create(endpoint),
                new AwsHttp(Duration.ofSeconds(5), 1, Duration.ZERO), Clock.systemUTC());
        RegistryCredentials credentials = auth.credentials().orElseThrow();
        assertEquals("AWS", credentials.username());
        assertFalse(credentials.password().isBlank());

        AtomicBoolean authenticated = new AtomicBoolean();
        HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/v2/", exchange -> {
            if (credentials.basicHeader().equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                authenticated.set(true);
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"local-test\"");
                exchange.sendResponseHeaders(401, -1);
            }
            exchange.close();
        });
        registry.start();
        try {
            auth.invalidate();
            var store = OciArchiveStore.withAuth("oci://127.0.0.1:" + registry.getAddress().getPort() + "/test/archive",
                    true, auth, Duration.ofSeconds(5));
            assertFalse(store.exists("sha256:" + "0".repeat(64)));
            assertTrue(authenticated.get(), "registry must receive the Moto-issued credentials");
        } finally {
            registry.stop(0);
        }
    }
}
