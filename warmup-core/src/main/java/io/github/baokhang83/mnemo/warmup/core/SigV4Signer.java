package io.github.baokhang83.mnemo.warmup.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for the two small calls this tool makes (STS and ECR). Hand-written over the JDK
 * so the core stays dependency-free (constitution section 4); checked against AWS's published test suite.
 * Handles the paths and queries of those calls (a single {@code /} path, simple query strings), not S3.
 */
final class SigV4Signer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private SigV4Signer() {}

    /**
     * The headers to add to the request: {@code x-amz-date}, {@code x-amz-security-token} when the
     * credentials have a session token, and {@code Authorization}. {@code headers} are the request's other
     * headers that should be signed (names compared case-insensitively); {@code host} is always signed.
     */
    static Map<String, String> sign(
            String method,
            URI uri,
            Map<String, String> headers,
            byte[] body,
            AwsCredentials credentials,
            String region,
            String service,
            Instant now) {
        String amzDate = STAMP.format(now);
        String day = amzDate.substring(0, 8);

        TreeMap<String, String> toSign = new TreeMap<>();
        headers.forEach((k, v) -> toSign.put(k.toLowerCase(Locale.ROOT), v.trim().replaceAll("\\s+", " ")));
        toSign.put("host", uri.getAuthority());
        toSign.put("x-amz-date", amzDate);
        if (credentials.sessionToken() != null) {
            toSign.put("x-amz-security-token", credentials.sessionToken());
        }

        StringBuilder canonicalHeaders = new StringBuilder();
        toSign.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v).append('\n'));
        String signedHeaders = String.join(";", toSign.keySet());

        String canonicalRequest = method + '\n'
                + canonicalPath(uri) + '\n'
                + canonicalQuery(uri) + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + hex(sha256(body));

        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + '\n' + amzDate + '\n' + scope + '\n' + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] key = hmac(("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = hex(hmac(key, stringToSign));

        TreeMap<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        out.put("x-amz-date", amzDate);
        if (credentials.sessionToken() != null) {
            out.put("x-amz-security-token", credentials.sessionToken());
        }
        out.put("Authorization", ALGORITHM + " Credential=" + credentials.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return out;
    }

    private static String canonicalPath(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static String canonicalQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            pairs.add(eq < 0 ? part + "=" : part);
        }
        pairs.sort(null);
        return String.join("&", pairs);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
