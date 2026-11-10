package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link TrainingLock} on a Kubernetes {@code Lease} named after the fingerprint (ADR 0002).
 *
 * <p>Creating a Lease is atomic, so two trainers cannot both create it. A Lease nobody renews expires, so a
 * crashed trainer does not block training forever: the next trainer takes it over with an update that
 * carries the resourceVersion it read, so only one of several takers wins. The holder renews in the
 * background while it trains and deletes the Lease when done.
 */
public final class KubernetesLeaseLock implements TrainingLock {

    private static final DateTimeFormatter MICRO_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final KubeApi api;
    private final String namespace;
    private final String holder;
    private final Duration leaseDuration;
    private final Clock clock;

    KubernetesLeaseLock(KubeApi api, String namespace, String holder, Duration leaseDuration, Clock clock) {
        this.api = api;
        this.namespace = namespace;
        this.holder = holder;
        this.leaseDuration = leaseDuration;
        this.clock = clock;
    }

    /** The Lease API of the cluster this pod runs in, in this pod's namespace, held as this pod. */
    public static KubernetesLeaseLock inCluster(Duration leaseDuration) {
        KubeApi api = KubeApi.inCluster(Duration.ofSeconds(30)); // first: explains "not in a pod" before anything else
        String namespace = System.getenv("POD_NAMESPACE");
        try {
            if (namespace == null || namespace.isBlank()) {
                namespace = Files.readString(KubeApi.SERVICE_ACCOUNT.resolve("namespace"), StandardCharsets.UTF_8).strip();
            }
        } catch (IOException e) {
            throw new StoreException("Cannot determine the pod's namespace (set POD_NAMESPACE): " + e.getMessage(), e);
        }
        String pod = System.getenv().getOrDefault("HOSTNAME", "trainer");
        return new KubernetesLeaseLock(
                api, namespace, pod + "-" + UUID.randomUUID().toString().substring(0, 8), leaseDuration, Clock.systemUTC());
    }

    static String leaseName(String key) {
        String hex = key.startsWith("sha256:") ? key.substring("sha256:".length()) : key;
        if (!hex.matches("[0-9a-f]{1,128}")) {
            throw new IllegalArgumentException("Not a fingerprint: " + key);
        }
        return "mnemo-warmup-" + hex;
    }

    @Override
    public Optional<Handle> tryAcquire(String key) {
        String name = leaseName(key);
        for (int attempt = 0; attempt < 3; attempt++) {
            KubeApi.Response created = api.request("POST", collection(), lease(name, null, null));
            if (created.status() == 201 || created.status() == 200) {
                return Optional.of(new LeaseHandle(name));
            }
            if (created.status() != 409) {
                throw failure("create the lease", created);
            }
            KubeApi.Response current = api.request("GET", item(name), null);
            if (current.status() == 404) {
                continue; // released between our create and our read: try again
            }
            if (current.status() != 200) {
                throw failure("read the lease", current);
            }
            Map<String, Object> existing = current.json();
            Map<String, Object> spec = map(existing.get("spec"));
            String currentHolder = string(spec.get("holderIdentity"));
            if (!holder.equals(currentHolder) && !expired(spec)) {
                return Optional.empty();
            }
            String resourceVersion = string(map(existing.get("metadata")).get("resourceVersion"));
            KubeApi.Response taken = api.request("PUT", item(name), lease(name, resourceVersion, string(spec.get("acquireTime"))));
            if (taken.status() == 200) {
                return Optional.of(new LeaseHandle(name));
            }
            if (taken.status() == 409) {
                return Optional.empty(); // someone else took it over first
            }
            throw failure("take over the lease", taken);
        }
        return Optional.empty();
    }

    private boolean expired(Map<String, Object> spec) {
        String renewed = string(spec.get("renewTime"));
        if (renewed == null) {
            renewed = string(spec.get("acquireTime"));
        }
        if (renewed == null) {
            return true;
        }
        long seconds = spec.get("leaseDurationSeconds") instanceof Number n ? n.longValue() : leaseDuration.toSeconds();
        return clock.instant().isAfter(Instant.parse(renewed).plusSeconds(seconds));
    }

    private Map<String, Object> lease(String name, String resourceVersion, String acquireTime) {
        String now = MICRO_TIME.format(clock.instant());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);
        if (resourceVersion != null) {
            metadata.put("resourceVersion", resourceVersion);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("holderIdentity", holder);
        spec.put("leaseDurationSeconds", leaseDuration.toSeconds());
        spec.put("acquireTime", resourceVersion == null || acquireTime == null ? now : acquireTime);
        spec.put("renewTime", now);
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("apiVersion", "coordination.k8s.io/v1");
        lease.put("kind", "Lease");
        lease.put("metadata", metadata);
        lease.put("spec", spec);
        return lease;
    }

    private String collection() {
        return "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases";
    }

    private String item(String name) {
        return collection() + "/" + name;
    }

    private StoreException failure(String what, KubeApi.Response r) {
        String hint = r.status() == 403 ? " (the trainer's ServiceAccount needs get/create/update/delete on leases in namespace " + namespace + ")" : "";
        return new StoreException("Kubernetes refused to " + what + " (HTTP " + r.status() + serverMessage(r) + ")" + hint);
    }

    /** The API server's own explanation (its Status message), if the body has one. */
    private static String serverMessage(KubeApi.Response r) {
        try {
            Object message = r.json().get("message");
            return message instanceof String m && !m.isBlank() ? ": " + (m.length() > 300 ? m.substring(0, 300) + "..." : m) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String string(Object o) {
        return o instanceof String s ? s : null;
    }

    /** Renews in the background every third of the lease duration; deletes the lease on close. */
    private final class LeaseHandle implements Handle {
        private final String name;
        private final AtomicBoolean held = new AtomicBoolean(true);
        private final ScheduledExecutorService renewer;

        LeaseHandle(String name) {
            this.name = name;
            this.renewer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lease-renewer");
                t.setDaemon(true);
                return t;
            });
            long period = Math.max(1, leaseDuration.toSeconds() / 3);
            renewer.scheduleAtFixedRate(this::renew, period, period, TimeUnit.SECONDS);
        }

        private void renew() {
            try {
                KubeApi.Response current = api.request("GET", item(name), null);
                if (current.status() != 200) {
                    held.set(false);
                    return;
                }
                Map<String, Object> existing = current.json();
                Map<String, Object> spec = map(existing.get("spec"));
                if (!holder.equals(string(spec.get("holderIdentity")))) {
                    held.set(false);
                    return;
                }
                String rv = string(map(existing.get("metadata")).get("resourceVersion"));
                if (api.request("PUT", item(name), lease(name, rv, string(spec.get("acquireTime")))).status() != 200) {
                    held.set(false);
                }
            } catch (RuntimeException e) {
                // a transient API error: keep going, the lease only expires after its full duration
            }
        }

        @Override
        public boolean held() {
            return held.get();
        }

        @Override
        public void close() {
            renewer.shutdownNow();
            try {
                KubeApi.Response current = api.request("GET", item(name), null);
                if (current.status() == 200 && holder.equals(string(map(map(current.json()).get("spec")).get("holderIdentity")))) {
                    Map<String, Object> options = new LinkedHashMap<>();
                    options.put("apiVersion", "v1");
                    options.put("kind", "DeleteOptions");
                    options.put("preconditions", Map.of("resourceVersion", string(map(current.json().get("metadata")).get("resourceVersion"))));
                    api.request("DELETE", item(name), options);
                }
            } catch (RuntimeException e) {
                // best effort: an undeleted lease simply expires
            }
            held.set(false);
        }
    }
}
