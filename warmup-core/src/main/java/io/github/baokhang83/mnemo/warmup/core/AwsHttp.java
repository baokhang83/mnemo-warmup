package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * One POST to an AWS API with a few retries for transient trouble: connection errors, 429 and 5xx. A 4xx is
 * an answer (denied, expired, malformed) and is never retried. Error text carries the status and AWS's own
 * error message, never a token or key.
 */
final class AwsHttp {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Duration timeout;
    private final int attempts;
    private final Duration backoff;

    AwsHttp(Duration timeout, int attempts, Duration backoff) {
        this.timeout = timeout;
        this.attempts = attempts;
        this.backoff = backoff;
    }

    /** Returns the body of a 200 response. {@code step} names the call in error messages. */
    String post(String step, URI uri, Map<String, String> headers, byte[] body) {
        StoreException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleep(backoff.multipliedBy(1L << (attempt - 2)));
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(b::header);
            try {
                HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                String text = new String(r.body(), StandardCharsets.UTF_8);
                if (r.statusCode() == 200) {
                    return text;
                }
                StoreException failure = new StoreException(step + " failed (HTTP " + r.statusCode() + ")" + detail(text));
                if (r.statusCode() != 429 && r.statusCode() < 500) {
                    throw failure;
                }
                last = failure;
            } catch (IOException e) {
                last = new StoreException(step + ": cannot reach " + uri.getAuthority() + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreException(step + ": interrupted", e);
            }
        }
        throw last;
    }

    /** AWS's own error message from a JSON or XML error body, when there is one. */
    private static String detail(String body) {
        String lower = body.toLowerCase();
        int i = lower.indexOf("\"message\"");
        if (i < 0) {
            i = lower.indexOf("<message>");
        }
        if (i < 0) {
            return "";
        }
        String snippet = body.substring(i, Math.min(body.length(), i + 200)).replaceAll("[\\r\\n]+", " ");
        return ": " + snippet;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
