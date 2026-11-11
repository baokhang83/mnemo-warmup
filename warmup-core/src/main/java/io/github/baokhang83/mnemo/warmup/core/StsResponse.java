package io.github.baokhang83.mnemo.warmup.core;

import java.time.Instant;
import java.util.Map;

/** Reads the temporary keys out of a JSON STS response ({@code Accept: application/json}). */
final class StsResponse {

    private StsResponse() {}

    static AwsCredentials credentials(String action, String json) {
        Object root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            throw new StoreException("STS " + action + " response is not valid JSON", e);
        }
        Object c = path(root, action + "Response", action + "Result", "Credentials");
        if (!(c instanceof Map<?, ?> creds)
                || !(creds.get("AccessKeyId") instanceof String id)
                || !(creds.get("SecretAccessKey") instanceof String secret)
                || !(creds.get("SessionToken") instanceof String session)) {
            throw new StoreException("STS " + action + " response has no credentials");
        }
        return new AwsCredentials(id, secret, session, expiration(creds.get("Expiration")));
    }

    private static Object path(Object node, String... keys) {
        for (String key : keys) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(key);
        }
        return node;
    }

    /** STS JSON gives epoch seconds (a number); tolerate an ISO-8601 string too. */
    private static Instant expiration(Object value) {
        if (value instanceof Number n) {
            return Instant.ofEpochMilli((long) (n.doubleValue() * 1000));
        }
        if (value instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
