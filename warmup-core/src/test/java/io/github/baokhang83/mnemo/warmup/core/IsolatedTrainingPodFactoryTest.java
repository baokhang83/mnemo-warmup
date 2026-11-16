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
    private static final Set<String> ALLOWED = Set.of("database-secret");
    private static Map<String, Object> template(Map<String, Object> container, List<Object> volumes) {
        return Map.of("containers", List.of(container), "volumes", volumes, "securityContext", Map.of("fsGroup", 1000));
    }
    private static Map<String, Object> container() {
        return Map.of("name", "trainer", "image", "example/app:1", "command", List.of("java", "-version"));
    }
    private static Map<String, Object> build(Map<String, Object> template) {
        return IsolatedTrainingPodFactory.build(template, "test", "train-one", "training-only", "handoff-claim", "mnemo-run-123", PROTECTED, ALLOWED, 600, null);
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
        assertThrows(IllegalArgumentException.class, () -> IsolatedTrainingPodFactory.build(template(container(), List.of()), "test", "train-one", "default", "handoff-claim", "mnemo-run-123", PROTECTED, ALLOWED, 600, null));
    }
    @Test void permitsApplicationSecretsThatAreNotPublisherSecrets() {
        var appSecret = Map.<String, Object>of("name", "db", "secret", Map.of("secretName", "database-secret"));
        var pod = build(template(container(), List.of(appSecret)));
        assertTrue(pod.containsKey("spec"));
    }

    private static Map<String, Object> withPodSecurity(Map<String, Object> extra) {
        var security = new java.util.HashMap<String, Object>(extra); security.put("fsGroup", 1000);
        return Map.of("containers", List.of(container()), "securityContext", security);
    }
    private static Map<String, Object> withContainerSecurity(Map<String, Object> security) {
        return template(Map.of("name", "trainer", "image", "app", "securityContext", security), List.of());
    }
    @SuppressWarnings("unchecked")
    @Test void meetsPodSecurityRestrictedAndIsLabelledAndOwned() {
        var pod = IsolatedTrainingPodFactory.build(template(container(), List.of()), "test", "train-one", "training-only", "handoff-claim",
                "mnemo-run-123", PROTECTED, ALLOWED, 600, new IsolatedTrainingPodFactory.Owner("coordinator-abc", "uid-1"));
        var metadata = (Map<String, Object>) pod.get("metadata");
        assertEquals(Map.of("warmup.mnemo.io/role", "training"), metadata.get("labels"));
        assertEquals(List.of(Map.of("apiVersion", "v1", "kind", "Pod", "name", "coordinator-abc", "uid", "uid-1")), metadata.get("ownerReferences"));
        var podSecurity = (Map<String, Object>) ((Map<String, Object>) pod.get("spec")).get("securityContext");
        assertEquals(true, podSecurity.get("runAsNonRoot"));
        assertEquals(Map.of("type", "RuntimeDefault"), podSecurity.get("seccompProfile"));
        assertFalse(((Map<String, Object>) build(template(container(), List.of())).get("metadata")).containsKey("ownerReferences"));
        var localhost = build(withPodSecurity(Map.of("seccompProfile", Map.of("type", "Localhost", "localhostProfile", "p.json"))));
        assertEquals("Localhost", ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) localhost.get("spec")).get("securityContext")).get("seccompProfile")).get("type"));
    }
    @Test void refusesPrivilegeRoutesOutsideThePrivilegedFlag() {
        List<Map<String, Object>> pods = List.of(
                Map.of("seLinuxOptions", Map.of("type", "spc_t")), Map.of("windowsOptions", Map.of("hostProcess", true)),
                Map.of("sysctls", List.of(Map.of("name", "kernel.shm_rmid_forced", "value", "0"))),
                Map.of("seccompProfile", Map.of("type", "Unconfined")), Map.of("appArmorProfile", Map.of("type", "Unconfined")),
                Map.of("runAsUser", 0), Map.of("runAsNonRoot", false));
        for (var extra : pods) assertThrows(IllegalArgumentException.class, () -> build(withPodSecurity(extra)), extra.toString());
        List<Map<String, Object>> containers = List.of(
                Map.of("seLinuxOptions", Map.of("type", "spc_t")), Map.of("windowsOptions", Map.of("hostProcess", true)),
                Map.of("procMount", "Unmasked"), Map.of("seccompProfile", Map.of("type", "Unconfined")),
                Map.of("appArmorProfile", Map.of("type", "Unconfined")), Map.of("runAsUser", 0), Map.of("runAsNonRoot", false),
                Map.of("allowPrivilegeEscalation", true));
        for (var security : containers) assertThrows(IllegalArgumentException.class, () -> build(withContainerSecurity(security)), security.toString());
        build(withContainerSecurity(Map.of("runAsUser", 10001, "readOnlyRootFilesystem", true, "procMount", "Default")));
    }
    @Test void allowsOnlyListedSecretsAndUncredentialedProjections() {
        for (String kind : List.of("serviceAccountToken", "podCertificate", "clusterTrustBundle")) {
            var source = Map.<String, Object>of("name", "x", "projected", Map.of("sources", List.of(Map.of(kind, Map.of("path", "p")))));
            var ex = assertThrows(IllegalArgumentException.class, () -> build(template(container(), List.of(source))));
            assertTrue(ex.getMessage().contains(kind), ex.getMessage());
        }
        var configs = Map.<String, Object>of("name", "x", "projected", Map.of("sources", List.of(Map.of("configMap", Map.of("name", "c")),
                Map.of("downwardAPI", Map.of("items", List.of())), Map.of("secret", Map.of("name", "database-secret")))));
        build(template(container(), List.of(configs)));
        var leftover = Map.<String, Object>of("name", "t", "secret", Map.of("secretName", "coordinator-token-xyz"));
        var ex = assertThrows(IllegalArgumentException.class, () -> build(template(container(), List.of(leftover))));
        assertTrue(ex.getMessage().contains("not in the allowed training Secrets"), ex.getMessage());
        var env = Map.<String, Object>of("name", "trainer", "image", "app", "envFrom", List.of(Map.of("secretRef", Map.of("name", "other"))));
        assertThrows(IllegalArgumentException.class, () -> build(template(env, List.of())));
        assertThrows(IllegalArgumentException.class, () -> IsolatedTrainingPodFactory.build(template(container(), List.of()), "test", "train-one",
                "training-only", "handoff-claim", "mnemo-run-123", PROTECTED, Set.of("signing-secret"), 600, null));
    }

    @Test void allowsOnlyReviewedContainerFieldsAndNoHostPorts() {
        java.util.function.Function<Map<String, Object>, Map<String, Object>> with = extra -> {
            var c = new java.util.HashMap<String, Object>(container()); c.putAll(extra); return c;
        };
        build(template(with.apply(Map.of("ports", List.of(Map.of("name", "http", "containerPort", 8080, "protocol", "TCP")))), List.of()));
        List<Map<String, Object>> refused = List.of(
                Map.of("ports", List.of(Map.of("containerPort", 8080, "hostPort", 8080))),
                Map.of("ports", List.of(Map.of("containerPort", 8080, "hostIP", "0.0.0.0"))),
                Map.of("livenessProbe", Map.of("httpGet", Map.of("host", "169.254.169.254", "port", 80))),
                Map.of("lifecycle", Map.of("postStart", Map.of("httpGet", Map.of("host", "10.0.0.1", "port", 80)))),
                Map.of("restartPolicy", "Always"), Map.of("stdin", true), Map.of("volumeDevices", List.of()));
        for (var extra : refused) {
            assertThrows(IllegalArgumentException.class, () -> build(template(with.apply(extra), List.of())), extra.toString());
            var init = Map.<String, Object>of("containers", List.of(container()), "initContainers", List.of(with.apply(extra)),
                    "securityContext", Map.of("fsGroup", 1000));
            assertThrows(IllegalArgumentException.class, () -> build(init), "init " + extra);
        }
    }
}
