package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.*;
import io.github.baokhang83.mnemo.warmup.core.SigningKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IsolatedInjectorTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String TOOLS = "example.com/tools@" + DIGEST;
    private static final String KEYS = SigningKey.generate().publicKeyEntry();
    private static String input(String extra, String containerExtra) {
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata: {name: demo, namespace: test}
                spec:
                  template:
                    metadata:
                      annotations:
                        warmup.mnemo.io/enabled: "true"
                        warmup.mnemo.io/store: oci://example.com/demo
                        warmup.mnemo.io/exit-mode: spring-refresh
                        warmup.mnemo.io/isolated-training: "true"
                        warmup.mnemo.io/publisher-image: example.com/publisher@%s
                        warmup.mnemo.io/publisher-java: /opt/java/bin/java
                        warmup.mnemo.io/handoff-claim: handoff
                        warmup.mnemo.io/signing-key-secret: signer
                        warmup.mnemo.io/signing-public-keys: "%s"
                        warmup.mnemo.io/push-secret: push-secret
                %s    spec:
                      containers:
                        - name: app
                          image: example.com/app:1
                          command: [java, -jar, /app/app.jar]
                %s
                """.formatted(DIGEST, KEYS, extra, containerExtra);
    }
    @SuppressWarnings("unchecked")
    @Test void generatesIsolatedResourcesAndIsIdempotent() {
        String original = input("", "          env: [{name: JAVA_TOOL_OPTIONS, value: '-Xmx512m'}, {name: PASSWORD, value: secret}] ");
        String output = OfflineInjector.inject(original, null, TOOLS).yaml();
        List<Map<String, Object>> docs = ManifestStream.parse(output);
        assertEquals(7, docs.size());
        var cm = docs.stream().filter(d -> "ConfigMap".equals(d.get("kind"))).findFirst().orElseThrow();
        String template = (String) ((Map<?, ?>) cm.get("data")).get("training.yaml");
        assertFalse(template.contains("signer")); assertFalse(template.contains("push-secret"));
        assertTrue(template.contains("--group-readable-output"));
        var cron = docs.stream().filter(d -> "CronJob".equals(d.get("kind"))).findFirst().orElseThrow();
        String yaml = ManifestStream.render(List.of(cron));
        assertTrue(yaml.contains("coordinate-isolated")); assertTrue(yaml.contains("MNEMO_TARGET_JAVA_TOOL_OPTIONS"));
        assertFalse(yaml.contains("PASSWORD")); assertTrue(yaml.contains("signer")); assertTrue(yaml.contains("push-secret"));
        assertEquals(output, OfflineInjector.inject(output, null, TOOLS).yaml());
    }
    @Test void rejectsUnsafeOrIncompleteConfigurations() {
        String good = input("", "");
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(good, null, "example.com/tools:latest"));
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(good.replace("example.com/publisher@" + DIGEST, "example.com/publisher:latest"), null, TOOLS));
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("", "          envFrom: [{secretRef: {name: app-secret}}]"), null, TOOLS));
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("", "          envFrom: [{secretRef: {name: signer}}]"), null, TOOLS));
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("", "          env: [{name: KEY, valueFrom: {secretKeyRef: {name: signer, key: signing.key}}}]"), null, TOOLS));
    }

    private static Map<String, Object> doc(List<Map<String, Object>> docs, String kind, String suffix) {
        return docs.stream().filter(d -> kind.equals(d.get("kind")) && ((String) ((Map<?, ?>) d.get("metadata")).get("name")).endsWith(suffix)).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    @Test void generatedTemplateIsAcceptedByThePodFactoryAndCarriesNoCredentials() {
        var result = OfflineInjector.inject(input("", "          env: [{name: PLAIN, value: x}]"), null, TOOLS);
        List<Map<String, Object>> docs = ManifestStream.parse(result.yaml());
        var cm = doc(docs, "ConfigMap", "-template");
        var template = ManifestStream.parse((String) ((Map<?, ?>) cm.get("data")).get("training.yaml")).get(0);
        assertEquals(65532, ((Number) ((Map<?, ?>) template.get("securityContext")).get("fsGroup")).intValue());
        String trainingSa = (String) ((Map<?, ?>) doc(docs, "ServiceAccount", "-training").get("metadata")).get("name");
        assertEquals(false, doc(docs, "ServiceAccount", "-training").get("automountServiceAccountToken"));
        var pod = io.github.baokhang83.mnemo.warmup.core.IsolatedTrainingPodFactory.build(template, "test", "mnemo-train-x", trainingSa, "handoff",
                "mnemo-run-x", java.util.Set.of("signer", "push-secret"), 60);
        String rendered = ManifestStream.render(List.of(pod));
        assertFalse(rendered.contains("signing")); assertFalse(rendered.contains("REGISTRY_AUTH"));
        var cron = doc(docs, "CronJob", "");
        var podSpec = (Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) cron.get("spec")).get("jobTemplate")).get("spec")).get("template")).get("spec");
        String coordinatorSa = (String) podSpec.get("serviceAccountName");
        assertNotEquals(trainingSa, coordinatorSa);
        var publisher = (Map<String, Object>) ((List<?>) podSpec.get("containers")).get(0);
        assertFalse(ManifestStream.render(List.of(publisher)).contains("PLAIN"), "application env must not reach the publisher");
        assertEquals(List.of("/mnemo-tools/bin/mnemo-launcher"), publisher.get("command"));
        assertEquals(List.of("jar-isolated", "/opt/java/bin/java", "coordinate-isolated"), ((List<?>) publisher.get("args")).subList(0, 3));
        String role = ManifestStream.render(List.of(doc(docs, "Role", "")));
        assertTrue(role.contains("pods")); assertFalse(role.contains("secrets"));
        assertTrue(String.join("\n", result.notes()).contains("only the coordinator pod mounts secret/signer"));
        assertFalse(String.join("\n", result.notes()).contains("until the trainer is split"));
    }
    @Test void rejectsTemplatesThePodFactoryWouldRefuse() {
        String volume = "      volumes: [{name: host, hostPath: {path: /}}]\n";
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("", "").replace("    spec:\n      containers:", "    spec:\n" + volume + "      containers:"), null, TOOLS));
        String claim = "      volumes: [{name: h, persistentVolumeClaim: {claimName: handoff}}]\n";
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("", "").replace("    spec:\n      containers:", "    spec:\n" + claim + "      containers:"), null, TOOLS));
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("", "          securityContext: {privileged: true}"), null, TOOLS));
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("        warmup.mnemo.io/tools-delivery: image-volume\n", ""), null, TOOLS));
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("        warmup.mnemo.io/handoff-fs-group: \"0\"\n", ""), null, TOOLS));
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(input("", "").replace("/opt/java/bin/java", "java"), null, TOOLS));
    }
    @Test void isolationAnnotationsRequireOptInAndDisablingRestoresTheClassicTrainer() {
        String off = input("", "").replace("isolated-training: \"true\"", "isolated-training: \"false\"");
        var ex = assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(off, null, TOOLS));
        assertTrue(ex.getMessage().contains("only valid with isolated-training"), ex.getMessage());
        String isolated = OfflineInjector.inject(input("", ""), null, TOOLS).yaml();
        String classic = isolated.replaceAll("        warmup.mnemo.io/(isolated-training|publisher-image|publisher-java|handoff-claim): .*\n", "");
        List<Map<String, Object>> docs = ManifestStream.parse(OfflineInjector.inject(classic, null, TOOLS).yaml());
        assertEquals(5, docs.size());
        assertTrue(docs.stream().noneMatch(d -> "ConfigMap".equals(d.get("kind"))));
        assertFalse(ManifestStream.render(docs).contains("coordinate-isolated"));
    }
}
