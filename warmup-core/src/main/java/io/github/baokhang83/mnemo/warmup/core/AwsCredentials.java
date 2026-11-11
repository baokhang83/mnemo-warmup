package io.github.baokhang83.mnemo.warmup.core;

import java.time.Instant;

/** Temporary AWS keys. {@code sessionToken} is null for long-lived keys; {@code expiration} is null if unknown. */
record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken, Instant expiration) {

    @Override
    public String toString() {
        return "AwsCredentials[accessKeyId=" + accessKeyId + ", secrets=***]";
    }
}
