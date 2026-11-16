package io.github.baokhang83.mnemo.warmup.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Creates one isolated training pod, waits for completion, and deletes only the pod it created.
 * The trusted caller constructs the pod and grants its service account no publishing privileges.
 * Cluster admission and cloud identity configuration remain part of the security boundary.
 */
public final class IsolatedTrainingPodRunner {
    @FunctionalInterface
    interface Api {
        KubeApi.Response request(String method, String path, Map<String, Object> body);
    }

    public enum Status { SUCCEEDED, FAILED, TIMED_OUT, API_ERROR }
    public record Outcome(Status status, String detail, boolean cleanupRequestFailed) {}

    private final Api api;

    IsolatedTrainingPodRunner(Api api) { this.api = Objects.requireNonNull(api); }

    /** Uses the coordinator pod's projected service-account credentials. */
    public static IsolatedTrainingPodRunner inCluster() {
        KubeApi api = KubeApi.inCluster(Duration.ofSeconds(5));
        return new IsolatedTrainingPodRunner(api::request);
    }

    /**
     * Runs an explicitly named pod in one namespace. A successful create must return a UID so that
     * cleanup cannot delete a replacement pod with the same name. The caller owns volume cleanup.
     */
    public Outcome run(String namespace, Map<String, Object> pod, Duration timeout, Duration pollInterval) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (timeout.isZero() || timeout.isNegative() || pollInterval.isZero() || pollInterval.isNegative()
                || pollInterval.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("training timeout must be positive and poll interval must be between zero and five seconds");
        }
        String name = validate(namespace, pod);
        String collection = "/api/v1/namespaces/" + namespace + "/pods";
        String item = collection + "/" + name;
        String uid = null;
        boolean createAttempted = false;
        boolean podCreated = false;
        Outcome result;
        try {
            createAttempted = true;
            KubeApi.Response created = api.request("POST", collection, pod);
            if (created.status() != 201) {
                return new Outcome(Status.API_ERROR, "Kubernetes refused to create the training pod (HTTP " + created.status() + ")",
                        created.status() >= 500);
            }
            podCreated = true;
            uid = string(map(created.json().get("metadata")).get("uid"));
            if (uid == null || uid.isBlank()) {
                // Do not delete by name without an identity: it could already refer to a replacement.
                return new Outcome(Status.API_ERROR, "Created training pod has no UID; manual cleanup may be needed", true);
            }
            long started = System.nanoTime();
            long timeoutNanos = timeout.toNanos();
            result = new Outcome(Status.TIMED_OUT, "Training pod did not complete in time", false);
            while (System.nanoTime() - started < timeoutNanos) {
                KubeApi.Response current = api.request("GET", item, null);
                if (current.status() != 200) {
                    result = new Outcome(Status.API_ERROR, "Cannot read the training pod (HTTP " + current.status() + ")", false);
                    break;
                }
                Map<String, Object> value = current.json();
                if (!uid.equals(string(map(value.get("metadata")).get("uid")))) {
                    result = new Outcome(Status.API_ERROR, "Training pod identity changed", false);
                    break;
                }
                String phase = string(map(value.get("status")).get("phase"));
                if ("Succeeded".equals(phase)) {
                    result = new Outcome(Status.SUCCEEDED, "Training pod completed", false);
                    break;
                }
                if ("Failed".equals(phase)) {
                    result = new Outcome(Status.FAILED, "Training pod failed", false);
                    break;
                }
                long remaining = timeoutNanos - (System.nanoTime() - started);
                if (remaining <= 0) break;
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(remaining, pollInterval.toNanos()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = new Outcome(Status.FAILED, "Interrupted while waiting for training", false);
                    break;
                }
            }
        } catch (RuntimeException e) {
            result = new Outcome(Status.API_ERROR, "Training pod API request failed", (podCreated || createAttempted) && uid == null);
        }
        if (uid == null) return result;
        boolean cleanupRequestFailed;
        try {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("apiVersion", "v1");
            options.put("kind", "DeleteOptions");
            options.put("preconditions", Map.of("uid", uid));
            KubeApi.Response deleted = api.request("DELETE", item, options);
            cleanupRequestFailed = deleted.status() != 200 && deleted.status() != 202 && deleted.status() != 404;
        } catch (RuntimeException e) {
            cleanupRequestFailed = true;
        }
        return new Outcome(result.status(), result.detail(), cleanupRequestFailed);
    }

    private static String validate(String namespace, Map<String, Object> pod) {
        if (namespace == null || !namespace.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?") || namespace.length() > 63) {
            throw new IllegalArgumentException("invalid training namespace");
        }
        Map<String, Object> metadata = map(pod.get("metadata"));
        Map<String, Object> spec = map(pod.get("spec"));
        String name = string(metadata.get("name"));
        String serviceAccount = string(spec.get("serviceAccountName"));
        if (name == null || !name.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?") || name.length() > 63) {
            throw new IllegalArgumentException("training pod needs a valid explicit name");
        }
        Object podNamespace = metadata.get("namespace");
        if (podNamespace != null && !namespace.equals(podNamespace)) throw new IllegalArgumentException("training namespace mismatch");
        if (!"v1".equals(pod.get("apiVersion")) || !"Pod".equals(pod.get("kind"))) throw new IllegalArgumentException("expected a Pod");
        if (serviceAccount == null || serviceAccount.isBlank() || serviceAccount.equals("default")) {
            throw new IllegalArgumentException("training pod needs a dedicated service account");
        }
        if (!Boolean.FALSE.equals(spec.get("automountServiceAccountToken"))) {
            throw new IllegalArgumentException("training pod must disable automatic service-account token mounting");
        }
        for (String key : new String[] {"hostNetwork", "hostPID", "hostIPC", "shareProcessNamespace"}) {
            if (Boolean.TRUE.equals(spec.get(key))) throw new IllegalArgumentException("training pod cannot enable " + key);
        }
        return name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
    private static String string(Object value) { return value instanceof String s ? s : null; }
}
