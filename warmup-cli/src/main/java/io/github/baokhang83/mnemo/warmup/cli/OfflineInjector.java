package io.github.baokhang83.mnemo.warmup.cli;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import io.github.baokhang83.mnemo.warmup.core.AwsRegistryAuth;
import io.github.baokhang83.mnemo.warmup.core.OciImageConfigReader;
import io.github.baokhang83.mnemo.warmup.core.RegistryAuth;
import io.github.baokhang83.mnemo.warmup.core.RegistryCredentials;

/** Offline manifest transformation; all input is validated before output is returned. */
final class OfflineInjector {
    private static final Pattern CACHE_OPTION = Pattern.compile("(?:^|\\s)-XX:(?:SharedArchiveFile|AOTCache|AOTCacheOutput)(?:=|\\s|$)");
    private static final String MARKER = WorkloadDiscovery.PREFIX + "injected-v1";
    private static final String OWNER = WorkloadDiscovery.PREFIX + "owner";
    private static final String CHECKSUM = WorkloadDiscovery.PREFIX + "generated-checksum";
    private static final String TOOLS_REPOSITORY = "ghcr.io/baokhang83/mnemo-warmup-tools";

    record Result(String yaml, List<String> notes) {}

    /** Preview-only registry inspection. Errors are deliberately reduced to a generic note. */
    static List<String> previewImageWarnings(String input, java.util.Map<String, String> env) {
        return previewImageWarnings(input, image -> OciImageConfigReader.read(image, previewAuth(OciImageConfigReader.registryHost(image), env), Duration.ofSeconds(5)));
    }

    @FunctionalInterface
    interface ImageConfigInspector {
        List<OciImageConfigReader.ImageConfig> inspect(String image);
    }

    static List<String> previewImageWarnings(String input, ImageConfigInspector inspector) {
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> doc : ManifestStream.parse(input)) {
            if (!"Deployment".equals(doc.get("kind")) || !"apps/v1".equals(doc.get("apiVersion"))) continue;
            String workloadName = "opted-in workload";
            try {
                Map<String, Object> template = map(map(doc.get("spec"), "spec").get("template"), "template");
                if (!(template.get("metadata") instanceof Map<?, ?> metadata)
                        || !(metadata.get("annotations") instanceof Map<?, ?> annotations)
                        || !"true".equals(annotations.get(WorkloadDiscovery.PREFIX + "enabled"))) continue;
                WorkloadDiscovery.Workload workload = WorkloadDiscovery.discover(doc);
                if (workload == null) continue;
                workloadName = workload.namespace() + "/" + workload.name();
                List<OciImageConfigReader.ImageConfig> configs = inspector.inspect(workload.image());
                boolean marker = workload.command().stream().anyMatch(OfflineInjector::hasCacheOption);
                for (OciImageConfigReader.ImageConfig config : configs) {
                    marker |= config.entrypoint().stream().anyMatch(OfflineInjector::hasCacheOption);
                    marker |= config.cmd().stream().anyMatch(OfflineInjector::hasCacheOption);
                    marker |= config.env().values().stream().anyMatch(OfflineInjector::hasCacheOption);
                    marker |= "true".equalsIgnoreCase(config.env().get("BP_JVM_AOTCACHE_ENABLED"));
                    marker |= config.labels().keySet().stream().anyMatch(key -> key.toLowerCase(java.util.Locale.ROOT).contains("aotcache"));
                }
                if (marker) warnings.add(workload.namespace() + "/" + workload.name()
                        + ": image appears to carry a build-time JVM cache; check its JVM cache flags for compatibility with the cluster-trained archive");
            } catch (RuntimeException e) {
                // A preview must remain useful when an image is private, missing, malformed, or unreachable.
                warnings.add(workloadName + ": image metadata could not be inspected; cache presence is unknown");
            }
        }
        return List.copyOf(warnings);
    }

    private static boolean hasCacheOption(String value) { return CACHE_OPTION.matcher(value).find(); }

    private static RegistryAuth previewAuth(String host, java.util.Map<String, String> env) {
        String configured = env.get("DOCKER_CONFIG");
        Path config = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".docker", "config.json")
                : Path.of(configured).resolve("config.json");
        RegistryCredentials credentials = null;
        if (Files.isRegularFile(config)) {
            credentials = RegistryCredentials.fromDockerConfig(config, host).orElse(null);
            if (credentials == null && host.equals("registry-1.docker.io"))
                credentials = RegistryCredentials.fromDockerConfig(config, "index.docker.io").orElse(null);
        }
        return credentials != null ? RegistryAuth.fixed(credentials)
                : AwsRegistryAuth.fromEnvironment(host, env).orElse(RegistryAuth.anonymous());
    }

    /**
     * The tools image that matches this CLI: the CLI runs from that image, so its release version is the image tag. A development
     * build has no release version; it must be told which image to use rather than silently pinning a tag that goes stale.
     */
    static String defaultToolsImage() {
        String version = Main.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank() || version.endsWith("-SNAPSHOT"))
            throw new IllegalArgumentException("this build has no release version; pass --tools-image <image> (for example " + TOOLS_REPOSITORY + ":<release>)");
        return TOOLS_REPOSITORY + ":" + version;
    }

    static Result inject(String input, String namespace, String tools) {
        return inject(input, namespace, () -> tools);
    }

    /** The tools image is resolved only when a workload actually needs it, so a stream with nothing opted in passes through untouched. */
    static Result inject(String input, String namespace, java.util.function.Supplier<String> toolsImage) {
        List<Map<String, Object>> docs = ManifestStream.parse(input);
        List<Map<String, Object>> output = new ArrayList<>();
        List<Map<String, Object>> generated = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            if (isGenerated(doc)) continue;
            if (!"Deployment".equals(doc.get("kind")) || !"apps/v1".equals(doc.get("apiVersion"))) {
                output.add(doc); continue;
            }
            Map<String, Object> template = map(map(doc.get("spec"), "spec").get("template"), "template");
            if (template.get("metadata") == null) { output.add(doc); continue; }
            if (map(template.get("metadata"), "metadata").get("annotations") == null) { output.add(doc); continue; }
            Map<String, Object> meta = ensureMap(template, "metadata");
            Map<String, Object> ann = ensureMap(meta, "annotations");
            if (ann.containsKey(MARKER)) restore(template, ann);
            if ("true".equals(ann.get(WorkloadDiscovery.PREFIX + "enabled")) && namespace != null) {
                Map<String, Object> resourceMeta = map(doc.get("metadata"), "metadata");
                if (!resourceMeta.containsKey("namespace")) resourceMeta.put("namespace", namespace);
            }
            WorkloadDiscovery.Workload w = WorkloadDiscovery.discover(doc);
            if (w == null) { output.add(doc); continue; }
            String tools = toolsImage.get();
            if (tools == null || tools.isBlank() || tools.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("invalid tools image");
            Map<String, Object> pod = map(template.get("spec"), "pod spec");
            Map<String, Object> app = selected(pod, w.container());
            List<String> managedVolumes = new ArrayList<>(List.of("mnemo-tools", "mnemo-cache", "mnemo-work"));
            if (w.pullSecret() != null) managedVolumes.add("mnemo-registry-pull");
            if (w.nodeCacheUrl() != null) managedVolumes.addAll(List.of("mnemo-nodecache-identity", "mnemo-nodecache-ca"));
            requireFree(pod, "volumes", managedVolumes);
            requireFree(pod, "initContainers", List.of("mnemo-warmup-install", "mnemo-warmup-fetch"));
            boolean imageVolume = w.imageVolume();
            boolean inline = imageVolume && w.pullSecret() == null && w.nodeCacheUrl() == null;
            String launcher = launcherPath(imageVolume);
            List<String> appMounts = appMountNames(inline);
            requireFree(app, "volumeMounts", appMounts);
            requireFree(app, "env", List.of("MNEMO_WARMUP_CACHE_DIR"));
            requireFree(app, "env", List.of("POD_NAMESPACE"));
            requireFreePaths(app, List.of("/mnemo-tools", "/mnemo-cache", "/tmp/mnemo-warmup", "/var/run/mnemo-warmup/registry"));
            if (w.nodeCacheUrl() != null) requireFreePaths(app, List.of("/var/run/mnemo-warmup/nodecache/identity", "/var/run/mnemo-warmup/nodecache/ca"));
            String copies = describeCopies(pod, app, w);
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("container", w.container());
            saved.put("commandPresent", app.containsKey("command")); saved.put("command", app.get("command"));
            saved.put("argsPresent", app.containsKey("args")); saved.put("args", app.get("args"));
            List<Object> commonArgs = commonArgs(w);
            // Without a pull secret, image-volume mode fetches in the application entrypoint before the "--".
            List<Object> inlineFetch = new ArrayList<>();
            if (inline) { inlineFetch.addAll(List.of("--out", "/mnemo-cache")); inlineFetch.addAll(commonArgs); }
            saved.put("delivery", w.toolsDelivery());
            saved.put("launcher", launcher);
            saved.put("injectedArgs", launcherExecArgs(w.command(), inlineFetch));
            saved.put("volumesPresent", pod.containsKey("volumes"));
            saved.put("initContainersPresent", pod.containsKey("initContainers"));
            saved.put("volumeMountsPresent", app.containsKey("volumeMounts"));
            saved.put("envPresent", app.containsKey("env"));
            append(pod, "volumes", toolsVolume(imageVolume, tools), obj("name", "mnemo-cache", "emptyDir", obj()), obj("name", "mnemo-work", "emptyDir", obj()));
            if (w.pullSecret() != null) append(pod, "volumes", secretVolume("mnemo-registry-pull", w.pullSecret()));
            if (w.nodeCacheUrl() != null) {
                append(pod, "volumes", obj("name", "mnemo-nodecache-identity", "projected", obj("sources", List.of(
                    obj("serviceAccountToken", obj("audience", w.nodeCacheAudience(), "expirationSeconds", 600, "path", "token"))))),
                    obj("name", "mnemo-nodecache-ca", "configMap", obj("name", w.nodeCacheCAConfigMap(), "optional", true,
                        "items", List.of(obj("key", "ca.crt", "path", "ca.crt")))));
            }
            if (!inline) {
                List<Object> fetchArgs = new ArrayList<>(List.of("fetch", "--out", "/mnemo-cache")); fetchArgs.addAll(commonArgs);
                Map<String, Object> fetch = obj("name", "mnemo-warmup-fetch", "image", w.image(), "command", List.of(launcher), "args", fetchArgs,
                    "volumeMounts", new ArrayList<>(List.of(mount("mnemo-tools", "/mnemo-tools", true), mount("mnemo-cache", "/mnemo-cache", false), mount("mnemo-work", "/tmp/mnemo-warmup", false))));
                copyMounts(app, fetch);
                copyIfPresent(app, fetch, "env", "envFrom", "imagePullPolicy", "securityContext");
                if (w.pullSecret() != null) {
                    requireFree(fetch, "env", List.of("MNEMO_WARMUP_REGISTRY_AUTH"));
                    append(fetch, "env", obj("name", "MNEMO_WARMUP_REGISTRY_AUTH", "value", "/var/run/mnemo-warmup/registry/config.json"));
                    append(fetch, "volumeMounts", mount("mnemo-registry-pull", "/var/run/mnemo-warmup/registry", true));
                }
                if (w.nodeCacheUrl() != null) {
                    requireFree(fetch, "env", List.of("MNEMO_WARMUP_NODECACHE_URL", "MNEMO_WARMUP_NODECACHE_CA_FILE", "MNEMO_WARMUP_NODECACHE_TOKEN_FILE"));
                    append(fetch, "env", obj("name", "MNEMO_WARMUP_NODECACHE_URL", "value", w.nodeCacheUrl()),
                        obj("name", "MNEMO_WARMUP_NODECACHE_CA_FILE", "value", "/var/run/mnemo-warmup/nodecache/ca/ca.crt"),
                        obj("name", "MNEMO_WARMUP_NODECACHE_TOKEN_FILE", "value", "/var/run/mnemo-warmup/nodecache/identity/token"));
                    append(fetch, "volumeMounts", mount("mnemo-nodecache-identity", "/var/run/mnemo-warmup/nodecache/identity", true),
                        mount("mnemo-nodecache-ca", "/var/run/mnemo-warmup/nodecache/ca", true));
                }
                if (!imageVolume) append(pod, "initContainers", install(tools));
                append(pod, "initContainers", fetch);
                append(app, "volumeMounts", mount("mnemo-tools", "/mnemo-tools", true), mount("mnemo-cache", "/mnemo-cache", true));
            } else {
                // Inline fetch writes the application container's cache and scratch volumes.
                append(app, "volumeMounts", mount("mnemo-tools", "/mnemo-tools", true), mount("mnemo-cache", "/mnemo-cache", false), mount("mnemo-work", "/tmp/mnemo-warmup", false));
            }
            append(app, "env", obj("name", "MNEMO_WARMUP_CACHE_DIR", "value", "/mnemo-cache"));
            app.put("command", List.of(launcher)); app.put("args", launcherExecArgs(w.command(), inlineFetch));
            saved.put("volumeNames", managedVolumes);
            saved.put("volumesManaged", managed(pod, "volumes", managedVolumes));
            if (!inline) saved.put("initContainersManaged", managed(pod, "initContainers", imageVolume ? List.of("mnemo-warmup-fetch") : List.of("mnemo-warmup-install", "mnemo-warmup-fetch")));
            saved.put("volumeMountsManaged", managed(app, "volumeMounts", appMounts));
            saved.put("envManaged", managed(app, "env", List.of("MNEMO_WARMUP_CACHE_DIR")));
            String savedYaml = ManifestStream.render(List.of(saved));
            ann.put(MARKER, Base64.getEncoder().encodeToString(savedYaml.getBytes(StandardCharsets.UTF_8)));
            output.add(doc);
            generated.addAll(trainer(w, pod, app, commonArgs, tools));
            notes.add(w.namespace() + "/" + w.name() + ": container=" + w.container() + ", image=" + w.image() + ", jar=" + w.jar() + ", store=" + w.store() + ", trainer=" + w.exitMode() + ", tools=" + tools
                    + ", tools-delivery=" + w.toolsDelivery());
            if (imageVolume) notes.add("  image-volume support cannot be verified offline; check the feature gate and runtime on every eligible node, or use init-container");
            if (w.nodeCacheUrl() != null) notes.add("  node cache uses a projected service-account token in the fetch init container; cache failure falls back to the registry");
            notes.add("  trainer " + trainerName(w.name()) + " (CronJob, ServiceAccount, Role: leases only) copies from the workload, names only: " + copies);
        }
        output.addAll(generated);
        checkUnique(output, namespace);
        return new Result(ManifestStream.render(output), List.copyOf(notes));
    }

    /** What the trainer and fetch containers inherit from the workload, by name only: never a value, never a Secret's content. */
    private static String describeCopies(Map<String, Object> pod, Map<String, Object> app, WorkloadDiscovery.Workload w) {
        List<String> env = new ArrayList<>(), envFrom = new ArrayList<>(), volumes = new ArrayList<>(), mounts = new ArrayList<>();
        if (app.get("env") instanceof List<?> list) for (Object e : list) if (e instanceof Map<?, ?> m && m.get("name") != null) env.add(String.valueOf(m.get("name")));
        if (app.get("envFrom") instanceof List<?> list) for (Object e : list) if (e instanceof Map<?, ?> m) {
            if (m.get("secretRef") instanceof Map<?, ?> r) envFrom.add("secret/" + r.get("name"));
            else if (m.get("configMapRef") instanceof Map<?, ?> r) envFrom.add("configMap/" + r.get("name"));
        }
        if (pod.get("volumes") instanceof List<?> list) for (Object v : list) if (v instanceof Map<?, ?> m) {
            String kind = m.keySet().stream().map(String::valueOf).filter(k -> !k.equals("name")).findFirst().orElse("?");
            volumes.add(m.get("name") + " (" + kind + ")");
        }
        if (app.get("volumeMounts") instanceof List<?> list) for (Object v : list) if (v instanceof Map<?, ?> m) mounts.add(String.valueOf(m.get("mountPath")));
        return "env=" + env + ", envFrom=" + envFrom + ", volumes=" + volumes + ", mounts=" + mounts
                + "; archive credentials: pull=" + (w.pullSecret() == null ? "none" : "secret/" + w.pullSecret())
                + ", push=" + (w.pushSecret() == null ? "none" : "secret/" + w.pushSecret());
    }

    private static List<Map<String, Object>> trainer(WorkloadDiscovery.Workload w, Map<String, Object> pod, Map<String, Object> app, List<Object> common, String tools) {
        String name = trainerName(w.name());
        Map<String, Object> labels = obj(OWNER, w.name());
        List<Object> args = new ArrayList<>(List.of("jar", "train")); args.addAll(common); args.addAll(List.of("--exit-mode", w.exitMode()));
        if (w.probeUrl() != null) args.addAll(List.of("--url", w.probeUrl()));
        Map<String, Object> trainer = obj("name", "trainer", "image", w.image(), "command", List.of(launcherPath(w.imageVolume())), "args", args,
                "volumeMounts", new ArrayList<>(List.of(mount("mnemo-tools", "/mnemo-tools", true), mount("mnemo-work", "/tmp/mnemo-warmup", false))));
        copyMounts(app, trainer);
        copyIfPresent(app, trainer, "envFrom", "imagePullPolicy", "securityContext");
        List<Object> env = new ArrayList<>();
        if (app.get("env") instanceof List<?> existing) for (Object e : existing) {
            if (e instanceof Map<?, ?> m && !"MNEMO_WARMUP_CACHE_DIR".equals(m.get("name"))) env.add(e);
        }
        env.add(obj("name", "POD_NAMESPACE", "valueFrom", obj("fieldRef", obj("fieldPath", "metadata.namespace")))); trainer.put("env", env);
        if (w.pushSecret() != null) {
            requireFree(trainer, "env", List.of("MNEMO_WARMUP_REGISTRY_AUTH"));
            append(trainer, "env", obj("name", "MNEMO_WARMUP_REGISTRY_AUTH", "value", "/var/run/mnemo-warmup/registry/config.json"));
            append(trainer, "volumeMounts", mount("mnemo-registry-push", "/var/run/mnemo-warmup/registry", true));
        }
        Map<String, Object> trainerPod = obj("restartPolicy", "Never", "serviceAccountName", name, "automountServiceAccountToken", true,
            "containers", List.of(trainer), "volumes", new ArrayList<>(List.of(toolsVolume(w.imageVolume(), tools), obj("name", "mnemo-work", "emptyDir", obj()))));
        if (!w.imageVolume()) trainerPod.put("initContainers", List.of(install(tools)));
        if (pod.get("volumes") instanceof List<?> volumes) for (Object volume : volumes) {
            if (volume instanceof Map<?, ?> v && !List.of("mnemo-tools", "mnemo-work", "mnemo-cache", "mnemo-registry-pull", "mnemo-nodecache-identity", "mnemo-nodecache-ca").contains(v.get("name"))) append(trainerPod, "volumes", volume);
        }
        if (w.pushSecret() != null) append(trainerPod, "volumes", secretVolume("mnemo-registry-push", w.pushSecret()));
        copyIfPresent(pod, trainerPod, "imagePullSecrets", "nodeSelector", "tolerations", "affinity", "securityContext");
        Map<String, Object> metadata = obj("name", name, "namespace", w.namespace(), "labels", labels, "annotations", obj(MARKER, "generated"));
        Map<String, Object> cron = obj("apiVersion", "batch/v1", "kind", "CronJob", "metadata", metadata, "spec", obj("schedule", "*/10 * * * *", "concurrencyPolicy", "Forbid", "jobTemplate", obj("spec", obj("backoffLimit", 0, "activeDeadlineSeconds", 3600, "template", obj("spec", trainerPod)))));
        Map<String, Object> sa = obj("apiVersion", "v1", "kind", "ServiceAccount", "metadata", metadata);
        Map<String, Object> role = obj("apiVersion", "rbac.authorization.k8s.io/v1", "kind", "Role", "metadata", metadata, "rules", List.of(obj("apiGroups", List.of("coordination.k8s.io"), "resources", List.of("leases"), "verbs", List.of("get", "create", "update", "delete"))));
        Map<String, Object> binding = obj("apiVersion", "rbac.authorization.k8s.io/v1", "kind", "RoleBinding", "metadata", metadata, "roleRef", obj("apiGroup", "rbac.authorization.k8s.io", "kind", "Role", "name", name), "subjects", List.of(obj("kind", "ServiceAccount", "name", name, "namespace", w.namespace())));
        List<Map<String, Object>> resources = List.of(sa, role, binding, cron);
        List<Map<String, Object>> marked = new ArrayList<>();
        for (Map<String, Object> resource : resources) {
            Map<String, Object> copy = ManifestStream.parse(ManifestStream.render(List.of(resource))).get(0);
            Map<String, Object> annotations = map(map(copy.get("metadata"), "metadata").get("annotations"), "annotations");
            annotations.put(CHECKSUM, checksum(copy));
            marked.add(copy);
        }
        return marked;
    }
    private static String trainerName(String name) { return (name.length() > 42 ? name.substring(0, 42).replaceAll("-+$", "") : name) + "-mw-" + Integer.toUnsignedString(name.hashCode(), 36); }
    private static List<Object> commonArgs(WorkloadDiscovery.Workload w) {
        List<Object> args = new ArrayList<>(List.of("--jar", w.jar(), "--work-dir", "/tmp/mnemo-warmup", "--store", w.store(), "--cache-type", "auto"));
        for (String arg : w.jvmArgs()) args.add("--jvm-arg=" + arg);
        if (w.plainHttp()) args.add("--plain-http");
        return args;
    }
    /** The shell-free launcher: works in distroless images, unlike the sh scripts the Helm chart still uses. */
    private static final String LAUNCHER = "/mnemo-tools/bin/mnemo-launcher";
    /** An image volume holds the whole tools image, and the tools sit under /opt/mnemo-warmup in it (docs/adr/0012). */
    private static final String LAUNCHER_IMAGE_VOLUME = "/mnemo-tools/opt/mnemo-warmup/bin/mnemo-launcher";
    private static String launcherPath(boolean imageVolume) { return imageVolume ? LAUNCHER_IMAGE_VOLUME : LAUNCHER; }
    /** The application container's volume mounts that this tool owns. */
    private static List<String> appMountNames(boolean inlineFetch) { return inlineFetch ? List.of("mnemo-tools", "mnemo-cache", "mnemo-work") : List.of("mnemo-tools", "mnemo-cache"); }
    private static Map<String, Object> toolsVolume(boolean imageVolume, String tools) {
        return imageVolume ? obj("name", "mnemo-tools", "image", obj("reference", tools, "pullPolicy", "IfNotPresent")) : obj("name", "mnemo-tools", "emptyDir", obj());
    }
    /** {@code exec [fetch options] -- command}: with fetch options the launcher fetches first (image-volume mode). */
    private static List<Object> launcherExecArgs(List<String> command, List<Object> fetchOptions) {
        List<Object> args = new ArrayList<>(List.of("exec"));
        args.addAll(fetchOptions);
        args.add("--");
        args.addAll(command);
        return args;
    }
    private static Map<String, Object> install(String tools) {
        return obj("name", "mnemo-warmup-install", "image", tools,
                "command", List.of("/opt/mnemo-warmup/bin/mnemo-launcher", "install", "/mnemo-tools"),
                "securityContext", obj("runAsNonRoot", true, "runAsUser", 65532, "allowPrivilegeEscalation", false,
                        "readOnlyRootFilesystem", true, "capabilities", obj("drop", List.of("ALL")),
                        "seccompProfile", obj("type", "RuntimeDefault")),
                "volumeMounts", List.of(mount("mnemo-tools", "/mnemo-tools", false)));
    }
    private static Map<String, Object> mount(String name, String path, boolean readOnly) { return obj("name", name, "mountPath", path, "readOnly", readOnly); }
    private static Map<String, Object> secretVolume(String name, String secret) {
        return obj("name", name, "secret", obj("secretName", secret, "items", List.of(obj("key", ".dockerconfigjson", "path", "config.json"))));
    }
    private static void copyMounts(Map<String, Object> from, Map<String, Object> to) {
        if (from.get("volumeMounts") instanceof List<?> mounts) for (Object mount : mounts) {
            if (!(mount instanceof Map<?, ?> m)) throw new IllegalArgumentException("invalid volume mount");
            if (!List.of("mnemo-tools", "mnemo-work", "mnemo-cache").contains(m.get("name"))) append(to, "volumeMounts", mount);
        }
    }
    private static boolean isGenerated(Map<String, Object> doc) {
        Object metadata = doc.get("metadata"); if (!(metadata instanceof Map<?, ?> m)) return false;
        Object labels = m.get("labels");
        Object annotations = m.get("annotations");
        if (!(labels instanceof Map<?, ?> l) || !(l.get(OWNER) instanceof String owner)
                || !(annotations instanceof Map<?, ?> a) || !"generated".equals(a.get(MARKER))) return false;
        if (!trainerName(owner).equals(m.get("name")) || !List.of("ServiceAccount", "Role", "RoleBinding", "CronJob").contains(doc.get("kind")))
            throw new IllegalArgumentException("invalid generated resource marker");
        Map<String, Object> annotationsMap = map(annotations, "annotations");
        Object expected = annotationsMap.remove(CHECKSUM);
        String actual = checksum(doc);
        if (!(expected instanceof String) || !expected.equals(actual)) throw new IllegalArgumentException("generated resource was changed: " + doc.get("kind") + "/" + m.get("name"));
        return true;
    }
    private static String checksum(Map<String, Object> resource) {
        try {
            byte[] bytes = ManifestStream.render(List.of(resource)).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void restore(Map<String, Object> template, Map<String, Object> ann) {
        Object encoded = ann.remove(MARKER);
        if (!(encoded instanceof String s)) throw new IllegalArgumentException("invalid injector marker");
        Map<String, Object> saved;
        try { saved = ManifestStream.parse(new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8)).get(0); }
        catch (RuntimeException e) { throw new IllegalArgumentException("invalid injector marker", e); }
        Map<String, Object> pod = map(template.get("spec"), "pod spec"); Map<String, Object> app = selected(pod, String.valueOf(saved.get("container")));
        boolean imageVolume = WorkloadDiscovery.IMAGE_VOLUME.equals(saved.get("delivery"));
        boolean inline = imageVolume && !saved.containsKey("initContainersManaged");
        List<String> appMounts = appMountNames(inline);
        List<String> initNames = imageVolume ? List.of("mnemo-warmup-fetch") : List.of("mnemo-warmup-install", "mnemo-warmup-fetch");
        if (!List.of(launcherPath(imageVolume)).equals(app.get("command"))) throw new IllegalArgumentException("managed command was changed");
        if (!java.util.Objects.equals(saved.get("injectedArgs"), app.get("args"))) throw new IllegalArgumentException("managed arguments were changed");
        List<String> volumeNames = WorkloadDiscovery.strings(saved.get("volumeNames"), "managed volume names");
        verifyManaged(pod, saved, "volumes", volumeNames);
        if (!inline) verifyManaged(pod, saved, "initContainers", initNames);
        verifyManaged(app, saved, "volumeMounts", appMounts);
        verifyManaged(app, saved, "env", List.of("MNEMO_WARMUP_CACHE_DIR"));
        restoreField(app, saved, "command"); restoreField(app, saved, "args");
        removeNamed(pod, "volumes", volumeNames, Boolean.TRUE.equals(saved.get("volumesPresent")));
        if (!inline) removeNamed(pod, "initContainers", initNames, Boolean.TRUE.equals(saved.get("initContainersPresent")));
        removeNamed(app, "volumeMounts", appMounts, Boolean.TRUE.equals(saved.get("volumeMountsPresent")));
        removeNamed(app, "env", List.of("MNEMO_WARMUP_CACHE_DIR"), Boolean.TRUE.equals(saved.get("envPresent")));
    }
    private static void restoreField(Map<String, Object> target, Map<String, Object> saved, String key) { if (Boolean.TRUE.equals(saved.get(key + "Present"))) target.put(key, saved.get(key)); else target.remove(key); }
    private static List<Object> managed(Map<String, Object> owner, String key, List<String> names) {
        if (!(owner.get(key) instanceof List<?> list)) throw new IllegalArgumentException("managed " + key + " missing");
        List<Object> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> m && names.contains(m.get("name"))) result.add(item);
        return result;
    }
    private static void verifyManaged(Map<String, Object> owner, Map<String, Object> saved, String key, List<String> names) {
        if (!managed(owner, key, names).equals(saved.get(key + "Managed"))) throw new IllegalArgumentException("managed " + key + " was changed");
    }
    private static void removeNamed(Map<String, Object> owner, String key, List<String> names, boolean wasPresent) {
        Object v = owner.get(key); if (!(v instanceof List<?> list)) throw new IllegalArgumentException("managed " + key + " missing");
        List<Object> remaining = new ArrayList<>(); for (Object e : list) if (!(e instanceof Map<?, ?> m) || !names.contains(m.get("name"))) remaining.add(e);
        if (remaining.isEmpty() && !wasPresent) owner.remove(key); else owner.put(key, remaining);
    }
    private static void requireFree(Map<String, Object> owner, String key, List<String> names) {
        if (owner.get(key) instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m && names.contains(m.get("name"))) throw new IllegalArgumentException("name collision in " + key + ": " + m.get("name"));
    }
    private static void requireFreePaths(Map<String, Object> owner, List<String> paths) {
        if (owner.get("volumeMounts") instanceof List<?> mounts) for (Object mount : mounts) {
            if (mount instanceof Map<?, ?> m && paths.contains(m.get("mountPath"))) throw new IllegalArgumentException("mount path collision: " + m.get("mountPath"));
        }
    }
    private static Map<String, Object> selected(Map<String, Object> pod, String name) {
        for (Object c : (List<?>) pod.get("containers")) { Map<String, Object> m = map(c, "container"); if (name.equals(m.get("name"))) return m; }
        throw new IllegalArgumentException("container missing: " + name);
    }
    private static void checkUnique(List<Map<String, Object>> docs, String defaultNamespace) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Map<String, Object> doc : docs) if (doc.get("metadata") instanceof Map<?, ?> meta && meta.get("name") != null) {
            Object namespace = meta.containsKey("namespace") ? meta.get("namespace") : defaultNamespace;
            String key = doc.get("apiVersion") + "/" + doc.get("kind") + "/" + namespace + "/" + meta.get("name");
            if (!keys.add(key)) throw new IllegalArgumentException("resource collision: " + key);
        }
    }
    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String... keys) { for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key)); }
    @SuppressWarnings("unchecked") private static void append(Map<String, Object> m, String key, Object... values) {
        List<Object> list = m.get(key) instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>(); list.addAll(List.of(values)); m.put(key, list);
    }
    private static Map<String, Object> ensureMap(Map<String, Object> m, String key) { if (m.get(key) == null) m.put(key, new LinkedHashMap<String,Object>()); return map(m.get(key), key); }
    private static Map<String, Object> map(Object value, String name) { return WorkloadDiscovery.map(value, name); }
    private static Map<String, Object> obj(Object... entries) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) m.put((String) entries[i], entries[i + 1]); return m; }
}
