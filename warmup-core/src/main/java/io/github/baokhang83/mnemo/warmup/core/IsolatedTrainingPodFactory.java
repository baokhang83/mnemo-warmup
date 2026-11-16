package io.github.baokhang83.mnemo.warmup.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a training pod from trusted operator configuration and an isolated handoff name. */
public final class IsolatedTrainingPodFactory {
    /** Set by this factory only (the template cannot set metadata), so policies can select training pods on it. */
    public static final String ROLE_LABEL = "warmup.mnemo.io/role";
    public static final String ROLE_TRAINING = "training";
    private static final String HANDOFF = "mnemo-handoff";
    private static final Set<String> VOLUME_KINDS = Set.of("emptyDir", "secret", "configMap", "persistentVolumeClaim", "projected", "downwardAPI");
    private static final Set<String> PROJECTED_KINDS = Set.of("configMap", "secret", "downwardAPI");
    private static final Set<String> POD_FIELDS = Set.of("containers", "initContainers", "volumes", "securityContext", "imagePullSecrets",
            "nodeSelector", "tolerations", "affinity", "dnsPolicy", "dnsConfig", "priorityClassName");
    /** Allow-lists, not deny-lists: a field Kubernetes adds later is refused until it has been reviewed. */
    private static final Set<String> POD_SECURITY_FIELDS = Set.of("runAsUser", "runAsGroup", "runAsNonRoot", "fsGroup",
            "fsGroupChangePolicy", "supplementalGroups", "supplementalGroupsPolicy", "seccompProfile", "appArmorProfile");
    private static final Set<String> CONTAINER_SECURITY_FIELDS = Set.of("runAsUser", "runAsGroup", "runAsNonRoot", "readOnlyRootFilesystem",
            "allowPrivilegeEscalation", "privileged", "capabilities", "seccompProfile", "appArmorProfile", "procMount");

    /** The coordinator pod that owns a training pod, so garbage collection removes the training pod if the coordinator is deleted. */
    public record Owner(String name, String uid) {
        public Owner {
            if (name == null || name.isBlank() || uid == null || uid.isBlank()) throw new IllegalArgumentException("owner pod name and uid are required");
        }
    }

    private IsolatedTrainingPodFactory() {}

    /**
     * The template is operator configuration, pinned by digest, but it is still checked: the result must satisfy the Pod Security
     * {@code restricted} profile and must not reach a Secret the operator did not allow. Cluster admission may still mutate the pod;
     * cloud and Kubernetes policies must keep its service account unprivileged.
     *
     * @param protectedSecrets Secrets the training pod must never reference (the signing key and the push credential)
     * @param allowedSecrets the only Secrets the training pod may reference; every other Secret reference is refused
     * @param owner the coordinator pod, or null when there is none to own the training pod
     */
    public static Map<String, Object> build(Map<String, Object> template, String namespace, String podName,
            String trainingServiceAccount, String claimName, String handoffName, Set<String> protectedSecrets,
            Set<String> allowedSecrets, long activeDeadlineSeconds, Owner owner) {
        if (!dnsLabel(namespace) || !dnsLabel(podName) || !dnsLabel(trainingServiceAccount)
                || "default".equals(trainingServiceAccount) || !dnsLabel(claimName)
                || handoffName == null || !handoffName.matches("mnemo-run-[a-zA-Z0-9-]{1,80}")
                || activeDeadlineSeconds <= 0) {
            throw new IllegalArgumentException("invalid isolated training pod identity or handoff");
        }
        if (template == null || protectedSecrets == null || allowedSecrets == null) {
            throw new IllegalArgumentException("training template, protected and allowed secrets are required");
        }
        for (String name : allowedSecrets) {
            if (protectedSecrets.contains(name)) throw new IllegalArgumentException("a publisher credential Secret cannot be allowed for training: " + name);
        }
        var secrets = new SecretPolicy(protectedSecrets, allowedSecrets);
        Object copy = Json.parse(Json.writeCompact(template));
        Map<String, Object> spec = object(copy, "pod spec");
        for (String field : spec.keySet()) {
            if (!POD_FIELDS.contains(field)) throw new IllegalArgumentException("unsupported training pod field: " + field);
        }
        Map<String, Object> podSecurity = object(spec.get("securityContext"), "pod securityContext", true);
        checkSecurity(podSecurity, POD_SECURITY_FIELDS, "pod");
        Object fsGroup = podSecurity.get("fsGroup");
        if (!(fsGroup instanceof Number n) || n.longValue() <= 0) {
            throw new IllegalArgumentException("isolated training requires a positive fsGroup for the handoff");
        }
        podSecurity.put("runAsNonRoot", true);
        podSecurity.putIfAbsent("seccompProfile", new LinkedHashMap<>(Map.of("type", "RuntimeDefault")));
        spec.put("securityContext", podSecurity);
        List<Object> containers = array(spec.get("containers"), "containers", false);
        if (containers.size() != 1) throw new IllegalArgumentException("isolated training requires exactly one application container");
        List<Object> init = array(spec.get("initContainers"), "initContainers", true);
        List<Object> volumes = array(spec.get("volumes"), "volumes", true);
        for (Object volume : volumes) checkVolume(object(volume, "volume"), secrets, claimName);
        for (Object container : containers) checkContainer(object(container, "container"), secrets);
        for (Object container : init) checkContainer(object(container, "init container"), secrets);

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
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", podName);
        metadata.put("namespace", namespace);
        metadata.put("labels", Map.of(ROLE_LABEL, ROLE_TRAINING));
        metadata.put("annotations", Map.of("warmup.mnemo.io/handoff", handoffName));
        if (owner != null) {
            // not a controller and no blockOwnerDeletion: that would need update rights on the owner's finalizers
            metadata.put("ownerReferences", List.of(Map.of("apiVersion", "v1", "kind", "Pod", "name", owner.name(), "uid", owner.uid())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiVersion", "v1");
        result.put("kind", "Pod");
        result.put("metadata", metadata);
        result.put("spec", spec);
        return result;
    }

    private record SecretPolicy(Set<String> protectedNames, Set<String> allowed) {
        void check(Map<String, Object> ref, String field) {
            if (!(ref.get(field) instanceof String name) || name.isBlank()) throw new IllegalArgumentException("Secret reference without a name");
            if (protectedNames.contains(name)) throw new IllegalArgumentException("training pod cannot reference a publisher credential Secret");
            if (!allowed.contains(name)) throw new IllegalArgumentException("training pod references Secret " + name + ", which is not in the allowed training Secrets");
        }
    }

    private static boolean dnsLabel(String text) {
        return text != null && text.length() <= 63 && text.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    }

    /** Pod Security {@code restricted}, plus no root user: the levels Kubernetes would otherwise leave to admission policy. */
    private static void checkSecurity(Map<String, Object> security, Set<String> allowedFields, String where) {
        for (String field : security.keySet()) {
            if (!allowedFields.contains(field)) throw new IllegalArgumentException(where + " securityContext." + field + " is not allowed for training");
        }
        if (security.get("runAsUser") instanceof Number user && user.longValue() == 0) throw new IllegalArgumentException(where + " cannot run as root (runAsUser 0)");
        if (Boolean.FALSE.equals(security.get("runAsNonRoot"))) throw new IllegalArgumentException(where + " cannot set runAsNonRoot to false");
        if (security.containsKey("procMount") && !"Default".equals(security.get("procMount"))) throw new IllegalArgumentException(where + " procMount must be Default");
        Map<String, Object> seccomp = object(security.get("seccompProfile"), "seccompProfile", true);
        if (security.containsKey("seccompProfile") && !Set.of("RuntimeDefault", "Localhost").contains(seccomp.get("type"))) {
            throw new IllegalArgumentException(where + " seccompProfile must be RuntimeDefault or Localhost");
        }
        Map<String, Object> apparmor = object(security.get("appArmorProfile"), "appArmorProfile", true);
        if (security.containsKey("appArmorProfile") && !Set.of("RuntimeDefault", "Localhost").contains(apparmor.get("type"))) {
            throw new IllegalArgumentException(where + " appArmorProfile must be RuntimeDefault or Localhost");
        }
    }

    private static void checkVolume(Map<String, Object> volume, SecretPolicy secrets, String claim) {
        if (HANDOFF.equals(volume.get("name"))) throw new IllegalArgumentException("reserved handoff volume name");
        int types = 0;
        for (String key : volume.keySet()) {
            if (key.equals("name")) continue;
            if (!VOLUME_KINDS.contains(key)) throw new IllegalArgumentException("unsupported training volume type: " + key);
            types++;
        }
        if (types != 1) throw new IllegalArgumentException("training volume must have one supported source");
        if (volume.containsKey("secret")) secrets.check(object(volume.get("secret"), "secret"), "secretName");
        if (volume.containsKey("persistentVolumeClaim") && claim.equals(object(volume.get("persistentVolumeClaim"), "claim").get("claimName"))) {
            throw new IllegalArgumentException("handoff claim must not be mounted outside its run subPath");
        }
        if (volume.containsKey("projected")) {
            for (Object raw : array(object(volume.get("projected"), "projected").get("sources"), "projected sources", false)) {
                Map<String, Object> source = object(raw, "projected source");
                for (String kind : source.keySet()) {
                    // serviceAccountToken, podCertificate, clusterTrustBundle and anything newer: a credential or not reviewed
                    if (!PROJECTED_KINDS.contains(kind)) throw new IllegalArgumentException("training pod cannot project " + kind);
                }
                if (source.containsKey("secret")) secrets.check(object(source.get("secret"), "projected secret"), "name");
            }
        }
    }

    private static void checkContainer(Map<String, Object> container, SecretPolicy secrets) {
        for (Object raw : array(container.get("env"), "env", true)) {
            Map<String, Object> env = object(raw, "env entry");
            Object name = env.get("name");
            if ("MNEMO_WARMUP_SIGNING_KEY_FILE".equals(name) || "MNEMO_WARMUP_REGISTRY_AUTH".equals(name)) {
                throw new IllegalArgumentException("training container cannot inherit publisher credential paths");
            }
            Map<String, Object> from = object(env.get("valueFrom"), "env valueFrom", true);
            if (from.containsKey("secretKeyRef")) secrets.check(object(from.get("secretKeyRef"), "secretKeyRef"), "name");
        }
        for (Object raw : array(container.get("envFrom"), "envFrom", true)) {
            Map<String, Object> env = object(raw, "envFrom entry");
            if (env.containsKey("secretRef")) secrets.check(object(env.get("secretRef"), "secretRef"), "name");
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
        checkSecurity(security, CONTAINER_SECURITY_FIELDS, "container");
        if (Boolean.TRUE.equals(security.get("privileged")) || Boolean.TRUE.equals(security.get("allowPrivilegeEscalation"))) {
            throw new IllegalArgumentException("training container cannot run privileged or allow privilege escalation");
        }
        Map<String, Object> capabilities = object(security.get("capabilities"), "capabilities", true);
        for (String key : capabilities.keySet()) {
            if (!key.equals("add") && !key.equals("drop")) throw new IllegalArgumentException("unsupported capabilities field: " + key);
        }
        if (!array(capabilities.get("add"), "added capabilities", true).isEmpty()) throw new IllegalArgumentException("training container cannot add capabilities");
        capabilities.put("drop", List.of("ALL"));
        security.put("capabilities", capabilities);
        security.put("privileged", false);
        security.put("allowPrivilegeEscalation", false);
        container.put("securityContext", security);
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
