package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkloadDiscoveryTest {
    private Map<String, Object> deployment(String extra, String command) {
        return ManifestStream.parse("""
            apiVersion: apps/v1
            kind: Deployment
            metadata: {name: app, namespace: test}
            spec:
              template:
                metadata:
                  annotations:
                    warmup.mnemo.io/enabled: "true"
                    warmup.mnemo.io/store: oci://example.com/app
                    warmup.mnemo.io/exit-mode: spring-refresh
            """ + extra + """
                spec:
                  containers:
                    - name: app
                      image: example/app:1
            """ + command).get(0);
    }
    @Test void discoversArgumentsWithoutLosingSpaces() {
        var found = WorkloadDiscovery.discover(deployment("", "          command: [java, '-Dname=a b', -jar, /app/a.jar, 'one two']\n"));
        assertEquals("/app/a.jar", found.jar());
        assertEquals("-Dname=a b", found.jvmArgs().get(0));
        assertEquals("one two", found.command().get(4));
    }
    @Test void missingEntrypointFailsInsteadOfGuessing() {
        assertThrows(IllegalArgumentException.class, () -> WorkloadDiscovery.discover(deployment("", "")));
    }
    @Test void unannotatedResourcesPassThrough() {
        assertNull(WorkloadDiscovery.discover(ManifestStream.parse("apiVersion: v1\nkind: Service\n").get(0)));
    }
    @Test void overrideMustBeJsonRatherThanYaml() {
        var workload = deployment("", "          command: [java, -jar, /app/a.jar]\n");
        var template = WorkloadDiscovery.map(WorkloadDiscovery.map(workload.get("spec"), "spec").get("template"), "template");
        var annotations = WorkloadDiscovery.map(WorkloadDiscovery.map(template.get("metadata"), "metadata").get("annotations"), "annotations");
        annotations.put("warmup.mnemo.io/command", "[java, -jar, /app/a.jar]");
        assertThrows(IllegalArgumentException.class, () -> WorkloadDiscovery.discover(workload));
        annotations.put("warmup.mnemo.io/command", "[\"java\",\"-jar\",\"/app/a.jar\"]");
        assertEquals("/app/a.jar", WorkloadDiscovery.discover(workload).jar());
    }
}
