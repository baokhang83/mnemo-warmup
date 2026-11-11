package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssumedRoleCredentialsTest {

    @Test
    void signsAssumeRoleWithTheBaseKeysAndReturnsTheNewOnes() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer sts = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sts.createContext("/", ex -> {
            seen.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seen.add(ex.getRequestHeaders().getFirst("Authorization"));
            seen.add(ex.getRequestHeaders().getFirst("X-Amz-Security-Token"));
            byte[] out = ("{\"AssumeRoleResponse\":{\"AssumeRoleResult\":{\"Credentials\":{\"AccessKeyId\":\"ASIAHOP2\","
                    + "\"SecretAccessKey\":\"s2\",\"SessionToken\":\"t2\",\"Expiration\":1.7e9}}}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        sts.start();
        try {
            var base = new AwsCredentials("ASIABASE", "base-secret", "base-token", null);
            var source = new AssumedRoleCredentials(() -> base, "arn:aws:iam::999999999999:role/push", "mnemo-warmup",
                    URI.create("http://127.0.0.1:" + sts.getAddress().getPort() + "/"), "eu-west-1",
                    new AwsHttp(Duration.ofSeconds(5), 1, Duration.ofMillis(1)),
                    Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC));
            AwsCredentials c = source.resolve();
            assertEquals("ASIAHOP2", c.accessKeyId());
            assertTrue(seen.get(0).contains("Action=AssumeRole&") && seen.get(0).contains("role%2Fpush"), seen.get(0));
            assertTrue(seen.get(1).contains("Credential=ASIABASE/20261111/eu-west-1/sts/aws4_request"), seen.get(1));
            assertEquals("base-token", seen.get(2));
        } finally {
            sts.stop(0);
        }
    }
}
