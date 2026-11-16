package io.github.baokhang83.mnemo.warmup.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a training pod from trusted operator configuration and an isolated handoff name. */
public final class IsolatedTrainingPodFactory {
    private static final String HANDOFF = "mnemo-handoff";
    private static final Set<String> VOLUME_KINDS = Set.of("emptyDir", "secret", "configMap", "persistentVolumeClaim", "projected", "downwardAPI");
    private static final Set<String> POD_FIELDS = Set.of("containers", "initContainers", "volumes", "securityContext", "imagePullSecrets",
            "nodeSelector", "tolerations", "affinity", "dnsPolicy", "dnsConfig", "priorityClassName");

    private IsolatedTrainingPodFactory() {}

    /**
     * The template is trusted configuration, not application-generated data. Cluster admission may
     * still mutate the pod; cloud and Kubernetes policies must keep its service account unprivileged.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> build(Map<String, Object> template, String namespace, String podName,
            String trainingServiceAccount, String claimName, String handoffName, Set<String> protectedSecrets,
            long activeDeadlineSeconds) {
        if (!dnsLabel(namespace) || !dnsLabel(podName) || !dnsLabel(trainingServiceAccount)
                || "default".equals(trainingServiceAccount) || !dnsLabel(claimName)
                || handoffName == null || !handoffName.matches("mnemo-run-[a-zA-Z0-9-]{1,80}")
                || activeDeadlineSeconds <= 0) {
            throw new IllegalArgumentException("invalid isolated training pod identity or handoff");
        }
        if (template == null || protectedSecrets == null) throw new IllegalArgumentException("training template and protected secrets are required");
        Object copy = Json.parse(Json.writeCompact(template));
        Map<String, Object> spec = object(copy, "pod spec");
        for (String field : spec.keySet()) {
            if (!POD_FIELDS.contains(field)) throw new IllegalArgumentException("unsupported training pod field: " + field);
        }
        Map<String, Object> podSecurity = object(spec.get("securityContext"), "pod securityContext", true);
        Object fsGroup = podSecurity.get("fsGroup");
        if (!(fsGroup instanceof Number n) || n.longValue() <= 0) {
            throw new IllegalArgumentException("isolated training requires a positive fsGroup for the handoff");
        }
        List<Object> containers = array(spec.get("containers"), "containers", false);
        if (containers.size() != 1) throw new IllegalArgumentException("isolated training requires exactly one application container");
        List<Object> init = array(spec.get("initContainers"), "initContainers", true);
        List<Object> volumes = array(spec.get("volumes"), "volumes", true);
        for (Object volume : volumes) checkVolume(object(volume, "volume"), protectedSecrets, claimName);
        for (Object container : containers) checkContainer(object(container, "container"), protectedSecrets);
        for (Object container : init) checkContainer(object(container, "init container"), protectedSecrets);

        Map<String, Object> training = object(containers.get(0), "container");
        List<Object> mounts = array(training.get("volumeMounts"), "volumeMounts", true);
        mounts.add(new LinkedHashMap<>(Map.of("name", HANDOFF, "mountPath", "/mnemo-handoff", "subPath", handoffName)));
        training.put("volumeMounts", mounts);
        volumes.add(new LinkedHashMap<>(Map.of("name", HANDOFF, "persistentVolumeClaim", Map.of("claimName", claimName))));
        spec.put("volumes", volumes);
        spec.put("containers", containers);
        if (!init.isEmpty()) spec.put("initContainers", init);
        spec.put("serviceAccountName", trainingServiceAccount);
        spec.put("automountServiceAccountToken", false);
        spec.put("restartPolicy", "Never");
        spec.put("activeDeadlineSeconds", activeDeadlineSeconds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiVersion", "v1");
        result.put("kind", "Pod");
        result.put("metadata", new LinkedHashMap<>(Map.of("name", podName, "namespace", namespace)));
        result.put("spec", spec);
        return result;
    }

    private static boolean dnsLabel(String text) {
        return text != null && text.length() <= 63 && text.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    }

    private static void checkVolume(Map<String, Object> volume, Set<String> protectedSecrets, String claim) {
        if (HANDOFF.equals(volume.get("name"))) throw new IllegalArgumentException("reserved handoff volume name");
        int types = 0;
        for (String key : volume.keySet()) {
            if (key.equals("name")) continue;
            if (!VOLUME_KINDS.contains(key)) throw new IllegalArgumentException("unsupported training volume type: " + key);
            types++;
        }
        if (types != 1) throw new IllegalArgumentException("training volume must have one supported source");
        if (volume.containsKey("secret")) checkSecret(object(volume.get("secret"), "secret"), "secretName", protectedSecrets);
        if (volume.containsKey("persistentVolumeClaim") && claim.equals(object(volume.get("persistentVolumeClaim"), "claim").get("claimName"))) {
            throw new IllegalArgumentException("handoff claim must not be mounted outside its run subPath");
        }
        if (volume.containsKey("projected")) {
            for (Object raw : array(object(volume.get("projected"), "projected").get("sources"), "projected sources", false)) {
                Map<String, Object> source = object(raw, "projected source");
                if (source.containsKey("serviceAccountToken")) throw new IllegalArgumentException("training pod cannot project a service-account token");
                if (source.containsKey("secret")) checkSecret(object(source.get("secret"), "projected secret"), "name", protectedSecrets);
            }
        }
    }

    private static void checkContainer(Map<String, Object> container, Set<String> protectedSecrets) {
        for (Object raw : array(container.get("env"), "env", true)) {
            Map<String, Object> env = object(raw, "env entry");
            Object name = env.get("name");
            if ("MNEMO_WARMUP_SIGNING_KEY_FILE".equals(name) || "MNEMO_WARMUP_REGISTRY_AUTH".equals(name)) {
                throw new IllegalArgumentException("training container cannot inherit publisher credential paths");
            }
            Map<String, Object> from = object(env.get("valueFrom"), "env valueFrom", true);
            if (from.containsKey("secretKeyRef")) checkSecret(object(from.get("secretKeyRef"), "secretKeyRef"), "name", protectedSecrets);
        }
        for (Object raw : array(container.get("envFrom"), "envFrom", true)) {
            Map<String, Object> env = object(raw, "envFrom entry");
            if (env.containsKey("secretRef")) checkSecret(object(env.get("secretRef"), "secretRef"), "name", protectedSecrets);
        }
        for (Object raw : array(container.get("volumeMounts"), "volumeMounts", true)) {
            Map<String, Object> mount = object(raw, "volume mount");
            String path = mount.get("mountPath") instanceof String s ? s : "";
            if (HANDOFF.equals(mount.get("name")) || path.equals("/") || path.equals("/mnemo-handoff")
                    || path.startsWith("/mnemo-handoff/")) {
                throw new IllegalArgumentException("training container conflicts with the handoff mount");
            }
            if ("Bidirectional".equals(mount.get("mountPropagation"))) throw new IllegalArgumentException("bidirectional mount propagation is not allowed");
        }
        if (container.containsKey("volumeDevices")) throw new IllegalArgumentException("training block devices are not supported");
        Map<String, Object> security = object(container.get("securityContext"), "container securityContext", true);
        if (Boolean.TRUE.equals(security.get("privileged")) || Boolean.TRUE.equals(security.get("allowPrivilegeEscalation"))) {
            throw new IllegalArgumentException("training container cannot run privileged or allow privilege escalation");
        }
        Map<String, Object> capabilities = object(security.get("capabilities"), "capabilities", true);
        if (!array(capabilities.get("add"), "added capabilities", true).isEmpty()) throw new IllegalArgumentException("training container cannot add capabilities");
        capabilities.put("drop", List.of("ALL"));
        security.put("capabilities", capabilities);
        security.put("privileged", false);
        security.put("allowPrivilegeEscalation", false);
        container.put("securityContext", security);
    }

    private static void checkSecret(Map<String, Object> ref, String field, Set<String> protectedSecrets) {
        Object name = ref.get(field);
        if (name instanceof String s && protectedSecrets.contains(s)) {
            throw new IllegalArgumentException("training pod cannot reference a publisher credential Secret");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) { return object(value, name, false); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name, boolean optional) {
        if (value == null && optional) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(name + " must be an object");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String name, boolean optional) {
        if (value == null && optional) return new ArrayList<>();
        if (!(value instanceof List<?>)) throw new IllegalArgumentException(name + " must be an array");
        return (List<Object>) value;
    }
}
