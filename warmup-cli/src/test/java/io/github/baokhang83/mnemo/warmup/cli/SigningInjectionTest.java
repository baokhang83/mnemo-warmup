package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warmup.core.ArchiveVerifier;
import io.github.baokhang83.mnemo.warmup.core.SigningKey;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The annotations that turn signed archives on (docs/adr/0013): the pods get the public keys, only the trainer gets the private key. */
class SigningInjectionTest {

    private static final String TOOLS = "registry.example.com/tools/mnemo-warmup-tools:9.9.9";
    private static final SigningKey KEY = SigningKey.generate();
    private static final String KEYS = KEY.publicKeyEntry();
    private static final String BASE = """
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
        @@EXTRA@@
            spec:
              containers:
                - name: app
                  image: example/demo:1
                  command: [java, -jar, /app/app.jar]
                  env: [{name: KEEP, value: "1"}]
        """;

    private static String input(String... annotations) {
        StringBuilder extra = new StringBuilder();
        for (String a : annotations) extra.append("        ").append(a).append("\n"); // 8 spaces: where the text block puts the annotation keys
        return BASE.replace("@@EXTRA@@\n", extra.toString());
    }

    private static OfflineInjector.Result inject(String input) { return OfflineInjector.inject(input, null, TOOLS); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> podOf(Map<String, Object> doc) {
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc.get("spec")).get("template")).get("spec");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> owner, String key) {
        return owner.get(key) == null ? List.of() : (List<Map<String, Object>>) owner.get(key);
    }

    private static Map<String, String> envOf(Map<String, Object> container) {
        return list(container, "env").stream().collect(Collectors.toMap(e -> (String) e.get("name"), e -> String.valueOf(e.get("value")), (a, b) -> a));
    }

    private static Map<String, Object> app(List<Map<String, Object>> docs) { return list(podOf(docs.get(0)), "containers").get(0); }

    private static Map<String, Object> trainerPod(List<Map<String, Object>> docs) {
        var cron = docs.stream().filter(d -> "CronJob".equals(d.get("kind"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var jobTemplate = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) cron.get("spec")).get("jobTemplate")).get("spec");
        @SuppressWarnings("unchecked")
        var template = (Map<String, Object>) ((Map<String, Object>) jobTemplate.get("template")).get("spec");
        return template;
    }

    @Test
    void publicKeysGoToThePodsAndTheFetchAndNeverToTheTrainer() {
        var docs = ManifestStream.parse(inject(input("warmup.mnemo.io/signing-public-keys: \"" + KEYS + "\"")).yaml());
        assertEquals(KEYS, envOf(app(docs)).get(ArchiveVerifier.ENV_PUBLIC_KEYS), "run needs the keys");
        assertEquals("1", envOf(app(docs)).get("KEEP"), "the application's own env is untouched");
        var fetch = list(podOf(docs.get(0)), "initContainers").stream().filter(c -> "mnemo-warmup-fetch".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals(KEYS, envOf(fetch).get(ArchiveVerifier.ENV_PUBLIC_KEYS), "fetch needs the keys");
        var trainer = list(trainerPod(docs), "containers").get(0);
        assertFalse(envOf(trainer).containsKey(ArchiveVerifier.ENV_PUBLIC_KEYS), "the trainer signs, it does not check");
        assertEquals("1", envOf(trainer).get("KEEP"));
    }

    @Test
    void theSigningKeyIsMountedIntoTheTrainerAndNowhereElse() {
        var docs = ManifestStream.parse(inject(input("warmup.mnemo.io/signing-key-secret: demo-signing")).yaml());
        var trainer = list(trainerPod(docs), "containers").get(0);
        assertEquals("/var/run/mnemo-warmup/signing/signing.key", envOf(trainer).get(SigningKey.ENV_KEY_FILE));
        var mount = list(trainer, "volumeMounts").stream().filter(m -> "mnemo-signing-key".equals(m.get("name"))).findFirst().orElseThrow();
        assertEquals("/var/run/mnemo-warmup/signing", mount.get("mountPath"));
        assertEquals(true, mount.get("readOnly"));
        var volume = list(trainerPod(docs), "volumes").stream().filter(v -> "mnemo-signing-key".equals(v.get("name"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var secret = (Map<String, Object>) volume.get("secret");
        assertEquals("demo-signing", secret.get("secretName"));
        assertEquals(256, secret.get("defaultMode"), "0400: readable by its owner only");

        // not in the application pod's spec: no volume, mount or variable for the key in the app, the fetch container or the pod
        // (the annotation on the Deployment names the Secret, and that is only a name)
        String podSpec = ManifestStream.render(List.of(podOf(docs.get(0))));
        assertFalse(podSpec.contains("mnemo-signing-key") || podSpec.contains("demo-signing") || podSpec.contains(SigningKey.ENV_KEY_FILE), podSpec);
    }

    @Test
    void signingInImageVolumeModeNeedsNoFetchContainerAndKeepsTheKeysOnTheAppContainer() {
        var docs = ManifestStream.parse(inject(input("warmup.mnemo.io/tools-delivery: image-volume",
                "warmup.mnemo.io/signing-public-keys: \"" + KEYS + "\"", "warmup.mnemo.io/signing-key-secret: demo-signing")).yaml());
        assertEquals(List.of(), list(podOf(docs.get(0)), "initContainers"), "no init containers on the start path");
        assertEquals(KEYS, envOf(app(docs)).get(ArchiveVerifier.ENV_PUBLIC_KEYS), "the application's entrypoint fetches and runs, so it checks");
    }

    @Test
    void reinjectionIsStableAndRemovingTheAnnotationsRestoresTheOriginal() {
        String input = input("warmup.mnemo.io/signing-public-keys: \"" + KEYS + "\"", "warmup.mnemo.io/signing-key-secret: demo-signing");
        var first = inject(input);
        assertEquals(ManifestStream.parse(first.yaml()), ManifestStream.parse(inject(first.yaml()).yaml()));

        // switch signing off in the injected file: the environment variable it added must go, and nothing else
        String signingOff = first.yaml()
                .replaceAll("(?m)^\\s*warmup\\.mnemo\\.io/signing-public-keys:.*\\n", "")
                .replaceAll("(?m)^\\s*warmup\\.mnemo\\.io/signing-key-secret:.*\\n", "");
        assertNotEquals(first.yaml(), signingOff);
        var after = ManifestStream.parse(inject(signingOff).yaml());
        assertFalse(envOf(app(after)).containsKey(ArchiveVerifier.ENV_PUBLIC_KEYS));
        assertEquals("/mnemo-cache", envOf(app(after)).get("MNEMO_WARMUP_CACHE_DIR"));
        assertFalse(ManifestStream.render(after).contains("mnemo-signing-key"));

        // and disabling warmup altogether gives back exactly the application's own env
        String disabled = first.yaml().replace("warmup.mnemo.io/enabled: 'true'", "warmup.mnemo.io/enabled: 'false'")
                .replace("warmup.mnemo.io/enabled: \"true\"", "warmup.mnemo.io/enabled: \"false\"");
        var restored = ManifestStream.parse(inject(disabled).yaml());
        assertEquals(List.of(Map.of("name", "KEEP", "value", "1")), list(app(restored), "env"));
    }

    private static void assertNotEquals(String a, String b) { assertTrue(!a.equals(b), "expected a change"); }

    @Test
    void aMarkerWrittenBeforeSigningExistedStillRestores() {
        // the same injection without signing annotations has no 'envNames' entry in its marker, like an older version's output
        var plain = inject(input());
        String marker = plain.yaml();
        assertTrue(!marker.contains("signing-public-keys"));
        String disabled = marker.replace("warmup.mnemo.io/enabled: 'true'", "warmup.mnemo.io/enabled: 'false'")
                .replace("warmup.mnemo.io/enabled: \"true\"", "warmup.mnemo.io/enabled: \"false\"");
        var restored = ManifestStream.parse(inject(disabled).yaml());
        assertEquals(List.of(Map.of("name", "KEEP", "value", "1")), list(app(restored), "env"));
    }

    @Test
    void aTypoInTheKeyListIsAnErrorAtPreviewNotAKeyThatTrustsNothing() {
        for (String bad : new String[] {"justtext", "0000000000000000=" + KEYS.substring(KEYS.indexOf('=') + 1), "abcd=!!!", " , "}) {
            var ex = assertThrows(IllegalArgumentException.class, () -> inject(input("warmup.mnemo.io/signing-public-keys: \"" + bad + "\"")), bad);
            assertTrue(ex.getMessage().startsWith("signing-public-keys:"), ex.getMessage());
        }
    }

    @Test
    void aBadSecretNameIsRefused() {
        for (String bad : new String[] {"Bad_Name", "-x", "x y", "a/b"}) {
            assertThrows(IllegalArgumentException.class, () -> inject(input("warmup.mnemo.io/signing-key-secret: \"" + bad + "\"")), bad);
        }
    }

    @Test
    void aClashWithTheApplicationsOwnEnvIsRefusedNotOverwritten() {
        String clash = input("warmup.mnemo.io/signing-public-keys: \"" + KEYS + "\"").replace("env: [{name: KEEP, value: \"1\"}]",
                "env: [{name: " + ArchiveVerifier.ENV_PUBLIC_KEYS + ", value: x}]");
        assertThrows(IllegalArgumentException.class, () -> inject(clash));
    }

    @Test
    void thePreviewSaysWhatIsAndIsNotProtected() {
        String both = String.join("\n", inject(input("warmup.mnemo.io/signing-public-keys: \"" + KEYS + "\"",
                "warmup.mnemo.io/signing-key-secret: demo-signing")).notes());
        assertTrue(both.contains(KEY.keyId()) && both.contains("secret/demo-signing") && both.contains("stage B"), both);
        assertFalse(both.contains(KEYS.substring(KEYS.indexOf('=') + 1)), "the preview names key ids, never prints keys");

        String keysOnly = String.join("\n", inject(input("warmup.mnemo.io/signing-public-keys: \"" + KEYS + "\"")).notes());
        assertTrue(keysOnly.contains("every archive is refused"), keysOnly);
        String secretOnly = String.join("\n", inject(input("warmup.mnemo.io/signing-key-secret: demo-signing")).notes());
        assertTrue(secretOnly.contains("pods do not check signatures"), secretOnly);
        assertFalse(String.join("\n", inject(input()).notes()).contains("signing:"), "nothing about signing when it is off");
        assertNotNull(KEY);
    }
}
