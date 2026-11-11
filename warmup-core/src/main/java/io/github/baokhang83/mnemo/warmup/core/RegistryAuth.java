package io.github.baokhang83.mnemo.warmup.core;

import java.util.Optional;

/**
 * Where a registry login comes from. {@link OciArchiveStore} asks for credentials each time it needs a
 * registry token, rather than holding one fixed password, so a provider whose credentials expire (ECR)
 * can hand out a fresh one. Implementations must be thread-safe and must never log a secret.
 */
public interface RegistryAuth {

    /** The login to present, or empty for anonymous access. Throws {@link StoreException} if it cannot be obtained. */
    Optional<RegistryCredentials> credentials();

    /** The registry refused the credentials last handed out: drop any cached copy so the next call re-fetches. */
    default void invalidate() {}

    /** No login. */
    static RegistryAuth anonymous() {
        return Optional::empty;
    }

    /** A fixed login (a Docker-config entry), or anonymous if {@code credentials} is null. */
    static RegistryAuth fixed(RegistryCredentials credentials) {
        Optional<RegistryCredentials> value = Optional.ofNullable(credentials);
        return () -> value;
    }
}
