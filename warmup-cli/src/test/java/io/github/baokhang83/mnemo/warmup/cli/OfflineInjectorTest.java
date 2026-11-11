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
        String edited = injected.replace("/mnemo-tools/bin/mnemo-launcher", "/usr/bin/other");
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
        assertFalse(result.contains("mnemo-launcher"));
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
        assertEquals(java.util.List.of("/mnemo-tools/bin/mnemo-launcher"), containers.get(1).get("command"));
    }
    @Test void everyGeneratedContainerRunsThroughTheShellFreeLauncher() {
        String yaml = inject(INPUT).yaml();
        assertTrue(yaml.contains("/opt/mnemo-warmup/bin/mnemo-launcher"), "install runs the launcher from the tools image");
        assertFalse(yaml.contains("mnemo-warmup-exec"), "no sh wrapper");
        assertFalse(java.util.regex.Pattern.compile("bin/mnemo-warmup(?![-\\w])").matcher(yaml).find(), "no sh launcher script");
        assertFalse(java.util.regex.Pattern.compile("(?m)^\\s*-\\s*cp\\s*$").matcher(yaml).find(), "no cp");
        assertTrue(java.util.regex.Pattern.compile("(?s)- jar\\s+- train").matcher(yaml).find(), "the trainer goes through the jar pass-through");
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

    // ---- tools-delivery: image-volume (docs/adr/0012) ----

    private static final String IMAGE_VOLUME_INPUT = INPUT.replace("warmup.mnemo.io/exit-mode: spring-refresh",
            "warmup.mnemo.io/exit-mode: spring-refresh\n        warmup.mnemo.io/tools-delivery: image-volume");
    private static final String LAUNCHER_IV = "/mnemo-tools/opt/mnemo-warmup/bin/mnemo-launcher";

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> podSpec(java.util.Map<String, Object> deployment) {
        return (java.util.Map<String, Object>) ((java.util.Map<String, Object>) ((java.util.Map<String, Object>) deployment.get("spec")).get("template")).get("spec");
    }
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> only(java.util.List<?> list) { return (java.util.Map<String, Object>) list.get(0); }

    @Test void imageVolumeModeHasNoInitContainersAndTheToolsAreAnImageVolume() {
        var docs = ManifestStream.parse(inject(IMAGE_VOLUME_INPUT).yaml());
        var pod = podSpec(docs.get(0));
        assertNull(pod.get("initContainers"), "no init container on the start path");
        @SuppressWarnings("unchecked") var volumes = (java.util.List<java.util.Map<String, Object>>) pod.get("volumes");
        var tools = volumes.stream().filter(v -> "mnemo-tools".equals(v.get("name"))).findFirst().orElseThrow();
        assertEquals(java.util.Map.of("reference", TOOLS, "pullPolicy", "IfNotPresent"), tools.get("image"));
        assertNull(tools.get("emptyDir"));
        var app = only((java.util.List<?>) pod.get("containers"));
        assertEquals(java.util.List.of(LAUNCHER_IV), app.get("command"));
        @SuppressWarnings("unchecked") var args = (java.util.List<Object>) app.get("args");
        assertEquals("exec", args.get(0));
        assertEquals(java.util.List.of("--out", "/mnemo-cache", "--jar", "/app/app.jar"), args.subList(1, 5), "the fetch options come first");
        int sep = args.indexOf("--");
        assertTrue(sep > 5, "options, then --, then the original command");
        assertEquals(java.util.List.of("java", "-jar", "/app/app.jar"), args.subList(sep + 1, args.size()));
        assertTrue(args.contains("--store") && args.contains("--work-dir") && args.contains("--cache-type"));
        @SuppressWarnings("unchecked") var mounts = (java.util.List<java.util.Map<String, Object>>) app.get("volumeMounts");
        var cache = mounts.stream().filter(m -> "mnemo-cache".equals(m.get("name"))).findFirst().orElseThrow();
        var work = mounts.stream().filter(m -> "mnemo-work".equals(m.get("name"))).findFirst().orElseThrow();
        var toolsMount = mounts.stream().filter(m -> "mnemo-tools".equals(m.get("name"))).findFirst().orElseThrow();
        assertEquals(false, cache.get("readOnly"), "the application container fetches into the cache, so it writes it");
        assertEquals("/tmp/mnemo-warmup", work.get("mountPath"));
        assertEquals(true, toolsMount.get("readOnly"));
        assertFalse(inject(IMAGE_VOLUME_INPUT).yaml().contains("mnemo-warmup-install"), "no install container anywhere");
        assertFalse(inject(IMAGE_VOLUME_INPUT).yaml().contains("mnemo-warmup-fetch"));
    }
    @Test void imageVolumeTrainerUsesTheSameVolumeAndLauncherPath() {
        var docs = ManifestStream.parse(inject(IMAGE_VOLUME_INPUT).yaml());
        var cron = docs.stream().filter(d -> "CronJob".equals(d.get("kind"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var trainerPod = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) ((java.util.Map<String, Object>) ((java.util.Map<String, Object>) ((java.util.Map<String, Object>) cron.get("spec")).get("jobTemplate")).get("spec")).get("template")).get("spec");
        assertNull(trainerPod.get("initContainers"));
        assertEquals(java.util.List.of(LAUNCHER_IV), only((java.util.List<?>) trainerPod.get("containers")).get("command"));
        @SuppressWarnings("unchecked") var volumes = (java.util.List<java.util.Map<String, Object>>) trainerPod.get("volumes");
        assertEquals(TOOLS, ((java.util.Map<?, ?>) volumes.stream().filter(v -> "mnemo-tools".equals(v.get("name"))).findFirst().orElseThrow().get("image")).get("reference"));
    }
    @Test void imageVolumeIsStableOnReinjection() {
        var first = inject(IMAGE_VOLUME_INPUT);
        assertEquals(6, ManifestStream.parse(first.yaml()).size());
        assertEquals(ManifestStream.parse(first.yaml()), ManifestStream.parse(inject(first.yaml()).yaml()));
        assertTrue(String.join("\n", first.notes()).contains("tools-delivery=image-volume"));
    }
    @Test void disablingAnImageVolumeInjectionRestoresTheOriginalDeployment() {
        String injected = inject(IMAGE_VOLUME_INPUT).yaml();
        String disabled = injected.replace("warmup.mnemo.io/enabled: 'true'", "warmup.mnemo.io/enabled: 'false'")
                .replace("warmup.mnemo.io/enabled: \"true\"", "warmup.mnemo.io/enabled: \"false\"");
        assertNotEquals(injected, disabled);
        String result = inject(disabled).yaml();
        assertEquals(2, ManifestStream.parse(result).size());
        assertFalse(result.contains("mnemo-launcher") || result.contains("mnemo-tools") || result.contains("mnemo-work"));
        var original = podSpec(ManifestStream.parse(INPUT).get(0));
        var restored = podSpec(ManifestStream.parse(result).get(0));
        assertEquals(only((java.util.List<?>) original.get("containers")).get("command"), only((java.util.List<?>) restored.get("containers")).get("command"));
        assertNull(restored.get("volumes"));
        assertNull(restored.get("initContainers"));
    }
    @Test void switchingTheDeliveryModeReplacesTheInjection() {
        String viaInit = inject(INPUT).yaml();
        String toImage = viaInit.replaceFirst("(?m)^(\\s*)warmup.mnemo.io/exit-mode: (.*)$", "$1warmup.mnemo.io/exit-mode: $2\n$1warmup.mnemo.io/tools-delivery: image-volume");
        assertNotEquals(viaInit, toImage, "the fixture must have changed");
        var pod = podSpec(ManifestStream.parse(inject(toImage).yaml()).get(0));
        assertNull(pod.get("initContainers"));
        assertEquals(java.util.List.of(LAUNCHER_IV), only((java.util.List<?>) pod.get("containers")).get("command"));
        // and back
        String backToInit = inject(toImage).yaml().replaceFirst("(?m)^\\s*warmup.mnemo.io/tools-delivery: .*\\n", "");
        var back = podSpec(ManifestStream.parse(inject(backToInit).yaml()).get(0));
        assertEquals(2, ((java.util.List<?>) back.get("initContainers")).size());
        assertEquals(java.util.List.of("/mnemo-tools/bin/mnemo-launcher"), only((java.util.List<?>) back.get("containers")).get("command"));
    }
    @Test void imageVolumeRefusesAPullSecretBecauseTheApplicationWouldReadIt() {
        String input = IMAGE_VOLUME_INPUT.replace("warmup.mnemo.io/exit-mode: spring-refresh", "warmup.mnemo.io/exit-mode: spring-refresh\n        warmup.mnemo.io/pull-secret: read-auth");
        var e = assertThrows(IllegalArgumentException.class, () -> inject(input));
        assertTrue(e.getMessage().contains("pull-secret") && e.getMessage().contains("init-container"), e.getMessage());
        // the push secret belongs to the trainer and is fine
        assertDoesNotThrow(() -> inject(IMAGE_VOLUME_INPUT.replace("warmup.mnemo.io/exit-mode: spring-refresh", "warmup.mnemo.io/exit-mode: spring-refresh\n        warmup.mnemo.io/push-secret: write-auth")));
    }
    @Test void anUnknownDeliveryModeIsAnError() {
        var e = assertThrows(IllegalArgumentException.class, () -> inject(INPUT.replace("warmup.mnemo.io/exit-mode: spring-refresh", "warmup.mnemo.io/exit-mode: spring-refresh\n        warmup.mnemo.io/tools-delivery: sidecar")));
        assertTrue(e.getMessage().contains("tools-delivery"), e.getMessage());
    }
    @Test void explicitInitContainerIsTheDefault() {
        String explicit = INPUT.replace("warmup.mnemo.io/exit-mode: spring-refresh", "warmup.mnemo.io/exit-mode: spring-refresh\n        warmup.mnemo.io/tools-delivery: init-container");
        assertEquals(ManifestStream.parse(inject(INPUT).yaml()).size(), ManifestStream.parse(inject(explicit).yaml()).size());
        assertEquals(2, ((java.util.List<?>) podSpec(ManifestStream.parse(inject(explicit).yaml()).get(0)).get("initContainers")).size());
    }
    @Test void editedImageVolumeCommandIsRejected() {
        String edited = inject(IMAGE_VOLUME_INPUT).yaml().replace(LAUNCHER_IV, "/usr/bin/other");
        assertThrows(IllegalArgumentException.class, () -> inject(edited));
    }
}
