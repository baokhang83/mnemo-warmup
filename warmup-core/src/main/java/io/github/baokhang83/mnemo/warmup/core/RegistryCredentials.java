package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * A username and password for one registry, read from a Docker-config style {@code config.json}
 * (the format of a mounted {@code kubernetes.io/dockerconfigjson} Secret, ADR 0001). Credential
 * helpers and identity tokens are not supported; a file without a usable entry means anonymous.
 */
public record RegistryCredentials(String username, String password) {

    /** The entry for {@code registryHost} (with optional port), or empty if there is none. */
    public static Optional<RegistryCredentials> fromDockerConfig(Path configFile, String registryHost) {
        String text;
        try {
            text = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StoreException("Cannot read registry credentials " + configFile + ": " + e.getMessage(), e);
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException e) {
            throw new StoreException("Registry credentials " + configFile + " are not valid JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> top) || !(top.get("auths") instanceof Map<?, ?> auths)) {
            return Optional.empty();
        }
        for (Map.Entry<?, ?> entry : auths.entrySet()) {
            if (hostOf(String.valueOf(entry.getKey())).equalsIgnoreCase(registryHost)
                    && entry.getValue() instanceof Map<?, ?> value) {
                return decode(value);
            }
        }
        return Optional.empty();
    }

    private static Optional<RegistryCredentials> decode(Map<?, ?> value) {
        if (value.get("auth") instanceof String auth && !auth.isBlank()) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(auth.trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new StoreException("Registry credentials: the auth value is not valid base64");
            }
            int colon = decoded.indexOf(':');
            if (colon > 0) {
                return Optional.of(new RegistryCredentials(decoded.substring(0, colon), decoded.substring(colon + 1)));
            }
        }
        if (value.get("username") instanceof String user && value.get("password") instanceof String pass) {
            return Optional.of(new RegistryCredentials(user, pass));
        }
        return Optional.empty();
    }

    /** {@code https://index.example.com/v1/} and {@code index.example.com} both give {@code index.example.com}. */
    private static String hostOf(String key) {
        String candidate = key.contains("://") ? key : "//" + key;
        String authority = URI.create(candidate.replace(" ", "")).getAuthority();
        return authority == null ? key : authority;
    }

    /** The {@code Authorization: Basic ...} header value. */
    String basicHeader() {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "RegistryCredentials[username=" + username + ", password=***]";
    }
}
