package io.github.baokhang83.mnemo.warmup.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An optional second hop: with the keys from {@code base} (the pod's own identity), call STS
 * {@code AssumeRole} to become a narrower or cross-account role. Unlike the web identity exchange this call
 * is SigV4-signed, because the base keys exist by now.
 */
final class AssumedRoleCredentials implements AwsCredentialsSource {

    private final AwsCredentialsSource base;
    private final String roleArn;
    private final String sessionName;
    private final URI stsEndpoint;
    private final String region;
    private final AwsHttp http;
    private final Clock clock;

    AssumedRoleCredentials(
            AwsCredentialsSource base, String roleArn, String sessionName, URI stsEndpoint, String region, AwsHttp http, Clock clock) {
        this.base = base;
        this.roleArn = roleArn;
        this.sessionName = sessionName;
        this.stsEndpoint = stsEndpoint;
        this.region = region;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public AwsCredentials resolve() {
        AwsCredentials own = base.resolve();
        byte[] form = ("Action=AssumeRole&Version=2011-06-15&RoleArn=" + enc(roleArn) + "&RoleSessionName=" + enc(sessionName))
                .getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        Map<String, String> signed = new LinkedHashMap<>(headers);
        signed.putAll(SigV4Signer.sign("POST", stsEndpoint, Map.of("Content-Type", headers.get("Content-Type")), form, own, region, "sts", clock.instant()));
        String body = http.post("STS AssumeRole for " + roleArn, stsEndpoint, signed, form);
        return StsResponse.credentials("AssumeRole", body);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
