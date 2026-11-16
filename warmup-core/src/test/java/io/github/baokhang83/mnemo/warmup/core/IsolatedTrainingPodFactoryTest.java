package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IsolatedTrainingPodFactoryTest {
    private static final Set<String> PROTECTED = Set.of("signing-secret", "push-secret");
    private static Map<String, Object> template(Map<String, Object> container, List<Object> volumes) {
        return Map.of("containers", List.of(container), "volumes", volumes, "securityContext", Map.of("fsGroup", 1000));
    }
    private static Map<String, Object> container() {
        return Map.of("name", "trainer", "image", "example/app:1", "command", List.of("java", "-version"));
    }
    private static Map<String, Object> build(Map<String, Object> template) {
        return IsolatedTrainingPodFactory.build(template, "test", "train-one", "training-only", "handoff-claim", "mnemo-run-123", PROTECTED, 600);
    }
    @SuppressWarnings("unchecked")
    @Test void createsAnIsolatedPodAndMountsOnlyTheUniqueHandoffSubpath() {
        var input = template(container(), List.of(Map.of("name", "config", "configMap", Map.of("name", "app-config"))));
        var pod = build(input);
        var metadata = (Map<String, Object>) pod.get("metadata");
        assertEquals("mnemo-run-123", ((Map<?, ?>) metadata.get("annotations")).get("warmup.mnemo.io/handoff"));
        var spec = (Map<String, Object>) pod.get("spec");
        assertEquals("training-only", spec.get("serviceAccountName"));
        assertEquals(false, spec.get("automountServiceAccountToken"));
        assertEquals("Never", spec.get("restartPolicy"));
        var training = (Map<String, Object>) ((List<?>) spec.get("containers")).get(0);
        var mounts = (List<Map<String, Object>>) training.get("volumeMounts");
        assertEquals(Map.of("name", "mnemo-handoff", "mountPath", "/mnemo-handoff", "subPath", "mnemo-run-123"), mounts.get(0));
        var security = (Map<String, Object>) training.get("securityContext");
        assertEquals(false, security.get("privileged"));
        assertEquals(false, security.get("allowPrivilegeEscalation"));
        assertEquals(List.of("ALL"), ((Map<?, ?>) security.get("capabilities")).get("drop"));
        assertFalse(container().containsKey("securityContext"), "input remains unchanged");
    }
    @Test void rejectsPublisherSecretsThroughVolumesAndEnvironment() {
        var secretVolume = Map.<String, Object>of("name", "x", "secret", Map.of("secretName", "signing-secret"));
        var projected = Map.<String, Object>of("name", "x", "projected", Map.of("sources", List.of(Map.of("secret", Map.of("name", "push-secret")))));
        for (var v : List.of(secretVolume, projected)) {
            assertThrows(IllegalArgumentException.class, () -> build(template(container(), List.of(v))));
        }
        var env = Map.<String, Object>of("name", "trainer", "image", "app", "env", List.of(Map.of("name", "X", "valueFrom", Map.of("secretKeyRef", Map.of("name", "signing-secret", "key", "key")))));
        var envFrom = Map.<String, Object>of("name", "trainer", "image", "app", "envFrom", List.of(Map.of("secretRef", Map.of("name", "push-secret"))));
        for (var c : List.of(env, envFrom)) assertThrows(IllegalArgumentException.class, () -> build(template(c, List.of())));
    }
    @Test void rejectsProjectedTokensHostPathsAndSharedClaimBypass() {
        var token = Map.<String, Object>of("name", "x", "projected", Map.of("sources", List.of(Map.of("serviceAccountToken", Map.of("path", "token")))));
        var host = Map.<String, Object>of("name", "x", "hostPath", Map.of("path", "/"));
        var claim = Map.<String, Object>of("name", "x", "persistentVolumeClaim", Map.of("claimName", "handoff-claim"));
        for (var v : List.of(token, host, claim)) assertThrows(IllegalArgumentException.class, () -> build(template(container(), List.of(v))));
    }
    @Test void rejectsPrivilegedContainersAndUnsafePodSettings() {
        var privileged = Map.<String, Object>of("name", "trainer", "image", "app", "securityContext", Map.of("privileged", true));
        assertThrows(IllegalArgumentException.class, () -> build(template(privileged, List.of())));
        var addCaps = Map.<String, Object>of("name", "trainer", "image", "app", "securityContext", Map.of("capabilities", Map.of("add", List.of("SYS_ADMIN"))));
        assertThrows(IllegalArgumentException.class, () -> build(template(addCaps, List.of())));
        assertThrows(IllegalArgumentException.class, () -> build(Map.of("containers", List.of(container()), "securityContext", Map.of("fsGroup", 1000), "hostNetwork", true)));
        assertThrows(IllegalArgumentException.class, () -> build(Map.of("containers", List.of(container()))));
        assertThrows(IllegalArgumentException.class, () -> IsolatedTrainingPodFactory.build(template(container(), List.of()), "test", "train-one", "default", "handoff-claim", "mnemo-run-123", PROTECTED, 600));
    }
    @Test void permitsApplicationSecretsThatAreNotPublisherSecrets() {
        var appSecret = Map.<String, Object>of("name", "db", "secret", Map.of("secretName", "database-secret"));
        var pod = build(template(container(), List.of(appSecret)));
        assertTrue(pod.containsKey("spec"));
    }
}
