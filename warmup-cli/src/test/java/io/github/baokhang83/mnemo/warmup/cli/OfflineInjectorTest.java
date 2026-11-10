package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OfflineInjectorTest {
    private static final String TOOLS = "registry.example.com/tools/mnemo-warmup-tools:9.9.9";
    private static OfflineInjector.Result inject(String input) { return OfflineInjector.inject(input, null, TOOLS); }
    private static OfflineInjector.Result inject(String input, String namespace) { return OfflineInjector.inject(input, namespace, TOOLS); }
    private static final String INPUT = """
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
            spec:
              containers:
                - name: app
                  image: example/demo:1
                  command: [java, -jar, /app/app.jar]
        ---
        apiVersion: v1
        kind: Service
        metadata: {name: service, namespace: test}
        """;
    @Test void emitsWorkloadAndTrainerAndIsStableOnReinjection() {
        var first = inject(INPUT);
        assertEquals(6, ManifestStream.parse(first.yaml()).size());
        assertEquals(ManifestStream.parse(first.yaml()), ManifestStream.parse(inject(first.yaml()).yaml()));
    }
    @Test void rejectsNameCollisionsBeforeReturningOutput() {
        String collision = INPUT.replace("containers:\n", "volumes: [{name: mnemo-tools, emptyDir: {}}]\n      containers:\n");
        assertThrows(IllegalArgumentException.class, () -> inject(collision));
    }
    @Test void unrelatedDeploymentWithoutAnnotationsIsPreserved() {
        String input = "apiVersion: apps/v1\nkind: Deployment\nmetadata: {name: other}\nspec: {template: {spec: {containers: []}}}\n";
        assertEquals(ManifestStream.parse(input), ManifestStream.parse(inject(input).yaml()));
    }
    @Test void editedManagedCommandFailsInsteadOfOverwriting() {
        String injected = inject(INPUT).yaml();
        String edited = injected.replace("/mnemo-tools/bin/mnemo-warmup-exec", "/usr/bin/other");
        assertThrows(IllegalArgumentException.class, () -> inject(edited));
    }
    @Test void failsWhenNamespaceCannotBeDetermined() {
        String input = INPUT.replace("name: demo, namespace: test", "name: demo");
        assertThrows(IllegalArgumentException.class, () -> inject(input));
        assertTrue(inject(input, "production").yaml().contains("production"));
    }
    @Test void unrelatedOwnerLabelDoesNotRemoveResource() {
        String input = "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: a, labels: {warmup.mnemo.io/owner: demo}}\n";
        assertEquals(ManifestStream.parse(input), ManifestStream.parse(inject(input).yaml()));
    }
    @Test void disablingRestoresCommandAndDropsGeneratedResources() {
        String injected = inject(INPUT).yaml();
        String disabled = injected.replace("warmup.mnemo.io/enabled: 'true'", "warmup.mnemo.io/enabled: 'false'")
                .replace("warmup.mnemo.io/enabled: \"true\"", "warmup.mnemo.io/enabled: \"false\"");
        assertNotEquals(injected, disabled, "expected serialized enabled annotation");
        String result = inject(disabled).yaml();
        assertEquals(2, ManifestStream.parse(result).size());
        assertFalse(result.contains("mnemo-warmup-exec"));
        assertFalse(result.contains("mnemo-tools"));
    }
    @Test void registrySecretsAreMountedSeparately() {
        String input = INPUT.replace("warmup.mnemo.io/exit-mode: spring-refresh", "warmup.mnemo.io/exit-mode: spring-refresh\n        warmup.mnemo.io/pull-secret: read-auth\n        warmup.mnemo.io/push-secret: write-auth");
        String yaml = inject(input).yaml();
        assertTrue(yaml.contains("read-auth"));
        assertTrue(yaml.contains("write-auth"));
        assertEquals(ManifestStream.parse(yaml), ManifestStream.parse(inject(yaml).yaml()));
    }
    @Test void editedManagedMountIsRejected() {
        String injected = inject(INPUT).yaml();
        String edited = injected.replace("mountPath: /mnemo-cache", "mountPath: /changed");
        assertThrows(IllegalArgumentException.class, () -> inject(edited));
    }
    @Test void editedGeneratedTrainerIsRejected() {
        String injected = inject(INPUT).yaml();
        String edited = injected.replace("*/10 * * * *", "*/15 * * * *");
        assertNotEquals(injected, edited);
        assertThrows(IllegalArgumentException.class, () -> inject(edited));
    }
    @Test void previewNeverDisplaysSecretValues() {
        String source = INPUT + "---\napiVersion: v1\nkind: Secret\nmetadata: {name: sensitive}\nstringData: {password: hidden-value}\n";
        var transformed = inject(source);
        assertTrue(ManifestDiff.describe(source, transformed.yaml()).stream().noneMatch(s -> s.contains("hidden-value")));
    }

    private static final String TWO_CONTAINERS = INPUT.replace(
            "      containers:\n        - name: app\n          image: example/demo:1\n          command: [java, -jar, /app/app.jar]",
            "      containers:\n        - name: proxy\n          image: example/proxy:1\n        - name: app\n          image: example/demo:1\n          command: [java, -jar, /app/app.jar]");

    @Test void aPodWithSeveralContainersNeedsTheOneToInjectToBeNamed() {
        assertNotEquals(INPUT, TWO_CONTAINERS, "fixture must have two containers");
        var e = assertThrows(IllegalArgumentException.class, () -> inject(TWO_CONTAINERS));
        assertTrue(e.getMessage().contains("select a container"), e.getMessage());
    }
    @Test void onlyTheNamedContainerIsChangedAndOthersArePreserved() {
        String named = TWO_CONTAINERS.replace("warmup.mnemo.io/exit-mode: spring-refresh", "warmup.mnemo.io/exit-mode: spring-refresh\n        warmup.mnemo.io/container: app");
        var docs = ManifestStream.parse(inject(named).yaml());
        @SuppressWarnings("unchecked")
        var containers = (java.util.List<java.util.Map<String, Object>>) ((java.util.Map<String, Object>) ((java.util.Map<String, Object>) ((java.util.Map<String, Object>) docs.get(0).get("spec")).get("template")).get("spec")).get("containers");
        assertEquals("proxy", containers.get(0).get("name"));
        assertNull(containers.get(0).get("command"), "the other container must be untouched");
        assertEquals(java.util.List.of("/mnemo-tools/bin/mnemo-warmup-exec"), containers.get(1).get("command"));
    }
    @Test void aNamedContainerThatDoesNotExistIsAnError() {
        String missing = TWO_CONTAINERS.replace("warmup.mnemo.io/exit-mode: spring-refresh", "warmup.mnemo.io/exit-mode: spring-refresh\n        warmup.mnemo.io/container: nope");
        var e = assertThrows(IllegalArgumentException.class, () -> inject(missing));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }
    @Test void aDeploymentNameTooLongForALabelValueIsRejectedNotMangled() {
        String longName = INPUT.replace("name: demo, namespace: test", "name: " + "a".repeat(64) + ", namespace: test");
        var e = assertThrows(IllegalArgumentException.class, () -> inject(longName));
        assertTrue(e.getMessage().contains("63"), e.getMessage());
        assertDoesNotThrow(() -> inject(INPUT.replace("name: demo, namespace: test", "name: " + "a".repeat(63) + ", namespace: test")));
    }
    @Test void theGivenToolsImageIsUsedForTheWorkloadAndTheTrainer() {
        String yaml = inject(INPUT).yaml();
        assertEquals(2, yaml.split(java.util.regex.Pattern.quote(TOOLS), -1).length - 1, "one install init container in the Deployment, one in the trainer");
        assertFalse(yaml.contains("mnemo-warmup-tools:0.2.0"), "no pinned release tag may leak in");
    }
    @Test void aDevelopmentBuildHasNoDefaultToolsImageAndSaysHowToFixIt() {
        var e = assertThrows(IllegalArgumentException.class, OfflineInjector::defaultToolsImage);
        assertTrue(e.getMessage().contains("--tools-image"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> OfflineInjector.inject(INPUT, null, "bad image"));
    }
    @Test void aStreamWithNothingOptedInNeedsNoToolsImage() {
        String plain = "apiVersion: v1\nkind: Service\nmetadata: {name: s}\n";
        assertEquals(ManifestStream.parse(plain), ManifestStream.parse(OfflineInjector.inject(plain, null, () -> { throw new AssertionError("must not be asked"); }).yaml()));
    }
    @Test void previewListsWhatTheTrainerCopiesByNameOnly() {
        String source = INPUT.replace("command: [java, -jar, /app/app.jar]",
                "command: [java, -jar, /app/app.jar]\n          env: [{name: DB_URL, value: jdbc-secret-value}]\n          envFrom: [{secretRef: {name: app-secrets}}]\n          volumeMounts: [{name: conf, mountPath: /etc/app}]")
                .replace("    spec:\n      containers:", "    spec:\n      volumes: [{name: conf, configMap: {name: app-conf}}]\n      containers:");
        var notes = String.join("\n", inject(source).notes());
        assertTrue(notes.contains("DB_URL"), notes);
        assertTrue(notes.contains("secret/app-secrets"), notes);
        assertTrue(notes.contains("conf (configMap)"), notes);
        assertTrue(notes.contains("/etc/app"), notes);
        assertFalse(notes.contains("jdbc-secret-value"), "values must never be shown");
        assertTrue(notes.contains("leases only"), notes);
        assertTrue(notes.contains("pull=none, push=none"), notes);
    }
}
