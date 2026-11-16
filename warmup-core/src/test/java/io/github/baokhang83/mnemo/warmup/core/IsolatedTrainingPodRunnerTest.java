package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IsolatedTrainingPodRunnerTest {
    private static final String UID = "example-uid";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final Duration POLL = Duration.ofMillis(1);

    private static Map<String, Object> pod(Map<String, Object> spec) {
        return Map.of("apiVersion", "v1", "kind", "Pod", "metadata", Map.of("name", "train-one"), "spec", spec);
    }
    private static Map<String, Object> validSpec() {
        return Map.of("serviceAccountName", "training-only", "automountServiceAccountToken", false);
    }
    private static String state(String uid, String phase) {
        return "{\"metadata\":{\"uid\":\"" + uid + "\"},\"status\":{\"phase\":\"" + phase + "\"}}";
    }

    @Test void waitsForCompletionAndDeletesUsingTheCreatedUid() {
        List<String> calls = new ArrayList<>();
        IsolatedTrainingPodRunner.Api api = (method, path, body) -> {
            calls.add(method);
            assertTrue(path.startsWith("/api/v1/namespaces/test/pods"));
            if (method.equals("POST")) return new KubeApi.Response(201, state(UID, "Pending"));
            if (method.equals("GET")) return new KubeApi.Response(200, state(UID, "Succeeded"));
            assertEquals(Map.of("uid", UID), body.get("preconditions"));
            return new KubeApi.Response(202, "{}");
        };
        var result = new IsolatedTrainingPodRunner(api).run("test", pod(validSpec()), TIMEOUT, POLL);
        assertEquals(IsolatedTrainingPodRunner.Status.SUCCEEDED, result.status());
        assertFalse(result.cleanupRequestFailed());
        assertEquals(List.of("POST", "GET", "DELETE"), calls);
    }

    @Test void neverDeletesAConflictingOrReplacementPod() {
        List<String> calls = new ArrayList<>();
        IsolatedTrainingPodRunner.Api conflict = (method, path, body) -> {
            calls.add(method);
            return new KubeApi.Response(409, "{}");
        };
        var result = new IsolatedTrainingPodRunner(conflict).run("test", pod(validSpec()), TIMEOUT, POLL);
        assertEquals(IsolatedTrainingPodRunner.Status.API_ERROR, result.status());
        assertEquals(List.of("POST"), calls);

        IsolatedTrainingPodRunner.Api replaced = (method, path, body) -> {
            if (method.equals("POST")) return new KubeApi.Response(201, state(UID, "Pending"));
            if (method.equals("GET")) return new KubeApi.Response(200, state("replacement", "Succeeded"));
            assertEquals(Map.of("uid", UID), body.get("preconditions"));
            return new KubeApi.Response(409, "{}");
        };
        result = new IsolatedTrainingPodRunner(replaced).run("test", pod(validSpec()), TIMEOUT, POLL);
        assertEquals(IsolatedTrainingPodRunner.Status.API_ERROR, result.status());
        assertTrue(result.cleanupRequestFailed());
    }

    @Test void reportsAnUnidentifiedCreatedPodAsNeedingManualCleanup() {
        var runner = new IsolatedTrainingPodRunner((method, path, body) -> new KubeApi.Response(201, "not-json"));
        var result = runner.run("test", pod(validSpec()), TIMEOUT, POLL);
        assertEquals(IsolatedTrainingPodRunner.Status.API_ERROR, result.status());
        assertTrue(result.cleanupRequestFailed());
    }

    @Test void anAmbiguousCreateFailureRequiresCleanupInvestigation() {
        var runner = new IsolatedTrainingPodRunner((method, path, body) -> { throw new StoreException("connection lost"); });
        var result = runner.run("test", pod(validSpec()), TIMEOUT, POLL);
        assertEquals(IsolatedTrainingPodRunner.Status.API_ERROR, result.status());
        assertTrue(result.cleanupRequestFailed());
    }

    @Test void rejectsSharedIdentityAndHostNamespacesBeforeCallingTheApi() {
        var runner = new IsolatedTrainingPodRunner((method, path, body) -> { throw new AssertionError("unexpected API call"); });
        for (var spec : List.of(
                Map.<String, Object>of("serviceAccountName", "default", "automountServiceAccountToken", false),
                Map.<String, Object>of("serviceAccountName", "training-only", "automountServiceAccountToken", true),
                Map.<String, Object>of("serviceAccountName", "training-only", "automountServiceAccountToken", false, "hostNetwork", true))) {
            assertThrows(IllegalArgumentException.class, () -> runner.run("test", pod(spec), TIMEOUT, POLL));
        }
    }

    @Test void failedAndTimedOutPodsStillRequestDeletion() {
        for (String phase : List.of("Failed", "Pending")) {
            List<String> calls = new ArrayList<>();
            var runner = new IsolatedTrainingPodRunner((method, path, body) -> {
                calls.add(method);
                if (method.equals("POST")) return new KubeApi.Response(201, state(UID, "Pending"));
                if (method.equals("GET")) return new KubeApi.Response(200, state(UID, phase));
                return new KubeApi.Response(200, "{}");
            });
            var result = runner.run("test", pod(validSpec()), Duration.ofMillis(5), POLL);
            assertEquals(phase.equals("Failed") ? IsolatedTrainingPodRunner.Status.FAILED : IsolatedTrainingPodRunner.Status.TIMED_OUT, result.status());
            assertEquals("DELETE", calls.get(calls.size() - 1));
        }
    }
}
