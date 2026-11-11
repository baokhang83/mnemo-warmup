package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * IRSA: EKS projects a signed service-account token into the pod ({@code AWS_WEB_IDENTITY_TOKEN_FILE}) and
 * names the IAM role to use ({@code AWS_ROLE_ARN}). STS {@code AssumeRoleWithWebIdentity} exchanges the
 * token for temporary keys. The call is deliberately not SigV4-signed: the token is the proof of identity.
 * The token file is re-read every time because the kubelet rotates it.
 */
final class WebIdentityCredentials implements AwsCredentialsSource {

    private final Path tokenFile;
    private final String roleArn;
    private final String sessionName;
    private final URI stsEndpoint;
    private final AwsHttp http;

    WebIdentityCredentials(Path tokenFile, String roleArn, String sessionName, URI stsEndpoint, AwsHttp http) {
        this.tokenFile = tokenFile;
        this.roleArn = roleArn;
        this.sessionName = sessionName;
        this.stsEndpoint = stsEndpoint;
        this.http = http;
    }

    @Override
    public AwsCredentials resolve() {
        String token;
        try {
            token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new StoreException("Cannot read the web identity token file " + tokenFile + ": " + e.getMessage(), e);
        }
        if (token.isEmpty()) {
            throw new StoreException("The web identity token file " + tokenFile + " is empty");
        }
        String form = "Action=AssumeRoleWithWebIdentity&Version=2011-06-15"
                + "&RoleArn=" + enc(roleArn)
                + "&RoleSessionName=" + enc(sessionName)
                + "&WebIdentityToken=" + enc(token);
        String body = http.post(
                "STS AssumeRoleWithWebIdentity for " + roleArn,
                stsEndpoint,
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
                form.getBytes(StandardCharsets.UTF_8));
        return StsResponse.credentials("AssumeRoleWithWebIdentity", body);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
