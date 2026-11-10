package io.github.baokhang83.mnemo.warmup.cli;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Validates an opted-in Deployment and discovers its explicit Java launch. */
final class WorkloadDiscovery {
    static final String PREFIX = "warmup.mnemo.io/";
    record Workload(String name, String namespace, String container, String image, String store,
                    String exitMode, String probeUrl, String pullSecret, String pushSecret, boolean plainHttp,
                    List<String> command, List<String> jvmArgs, String jar) {}

    static Workload discover(Map<String, Object> resource) {
        if (!"apps/v1".equals(resource.get("apiVersion")) || !"Deployment".equals(resource.get("kind"))) return null;
        Map<String, Object> metadata = map(resource.get("metadata"), "metadata");
        Map<String, Object> template = map(map(resource.get("spec"), "spec").get("template"), "spec.template");
        Map<String, Object> templateMeta = optionalMap(template.get("metadata"), "spec.template.metadata");
        Map<String, Object> annotations = optionalMap(templateMeta.get("annotations"), "annotations");
        Object enabled = annotations.get(PREFIX + "enabled");
        if (enabled == null || "false".equals(enabled)) return null;
        if (!"true".equals(enabled)) throw new IllegalArgumentException("enabled annotation must be the string true or false");
        String name = required(metadata, "name");
        // the generated resources carry the Deployment's name as a label value, and label values are limited to 63 characters
        if (name.length() > 63) throw new IllegalArgumentException("Deployment name is longer than 63 characters (" + name.length() + "); generated resources cannot be labelled with it");
        String namespace = required(metadata, "namespace");
        String store = required(annotations, PREFIX + "store");
        URI storeUri;
        try { storeUri = URI.create(store); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid OCI store URI", e); }
        if (!"oci".equals(storeUri.getScheme()) || storeUri.getHost() == null || storeUri.getPath() == null || storeUri.getPath().length() < 2
                || storeUri.getUserInfo() != null || storeUri.getQuery() != null || storeUri.getFragment() != null)
            throw new IllegalArgumentException("store must be an oci://host/repository URI");
        String mode = required(annotations, PREFIX + "exit-mode");
        String probe = annotations.get(PREFIX + "probe-url") instanceof String s ? s : null;
        if (!mode.equals("spring-refresh") && !mode.equals("http-probe")) throw new IllegalArgumentException("unsupported exit-mode: " + mode);
        if (mode.equals("http-probe") && (probe == null || probe.isBlank())) throw new IllegalArgumentException("http-probe needs probe-url");
        if (mode.equals("spring-refresh") && probe != null) throw new IllegalArgumentException("probe-url is only allowed with http-probe");
        Map<String, Object> pod = map(template.get("spec"), "pod spec");
        if (!(pod.get("containers") instanceof List<?> containers) || containers.isEmpty()) throw new IllegalArgumentException("Deployment has no containers");
        String selected = annotations.get(PREFIX + "container") instanceof String s ? s : null;
        if (selected == null && containers.size() != 1) throw new IllegalArgumentException("select a container when a pod has multiple containers");
        Map<String, Object> container = null;
        for (Object item : containers) {
            Map<String, Object> candidate = map(item, "container");
            if (selected == null || selected.equals(candidate.get("name"))) {
                if (container != null) throw new IllegalArgumentException("duplicate container selection");
                container = candidate;
            }
        }
        if (container == null) throw new IllegalArgumentException("selected container does not exist: " + selected);
        String containerName = required(container, "name");
        String image = required(container, "image");
        List<String> command;
        Object override = annotations.get(PREFIX + "command");
        if (override != null) {
            if (!(override instanceof String text) || !isJsonStringArray(text)) throw new IllegalArgumentException("command override must be a JSON string array");
            Object parsed;
            try { parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text); }
            catch (RuntimeException e) { throw new IllegalArgumentException("invalid command override", e); }
            command = strings(parsed, "command override");
        } else {
            command = new ArrayList<>(strings(container.get("command"), "explicit command"));
            if (container.get("args") != null) command.addAll(strings(container.get("args"), "args"));
        }
        if (command.isEmpty() || !(command.get(0).equals("java") || command.get(0).endsWith("/java")))
            throw new IllegalArgumentException("launch must start with java; specify a command override if needed");
        int jarIndex = command.indexOf("-jar");
        if (jarIndex < 1 || jarIndex + 1 >= command.size() || command.get(jarIndex + 1).isBlank()) throw new IllegalArgumentException("launch must contain -jar and a JAR path");
        List<String> jvm = new ArrayList<>();
        for (String arg : command.subList(1, jarIndex)) {
            if (!arg.startsWith("-") || arg.startsWith("@") || arg.equals("-cp") || arg.equals("--class-path") || arg.equals("-classpath"))
                throw new IllegalArgumentException("unsupported JVM argument: " + arg);
            jvm.add(arg);
        }
        String pullSecret = optionalString(annotations, PREFIX + "pull-secret");
        String pushSecret = optionalString(annotations, PREFIX + "push-secret");
        String plainHttp = optionalString(annotations, PREFIX + "plain-http");
        if (plainHttp != null && !plainHttp.equals("true") && !plainHttp.equals("false")) throw new IllegalArgumentException("plain-http must be true or false");
        return new Workload(name, namespace, containerName, image, store, mode, probe, pullSecret, pushSecret, "true".equals(plainHttp),
                List.copyOf(command), List.copyOf(jvm), command.get(jarIndex + 1));
    }

    private static String optionalString(Map<String, Object> values, String key) {
        if (!values.containsKey(key)) return null;
        return required(values, key);
    }

    private static boolean isJsonStringArray(String input) {
        String s = input.trim();
        if (s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']') return false;
        int i = 1, end = s.length() - 1;
        while (i < end && Character.isWhitespace(s.charAt(i))) i++;
        if (i == end) return true;
        while (i < end) {
            if (s.charAt(i++) != '"') return false;
            boolean closed = false;
            while (i < end) {
                char c = s.charAt(i++);
                if (c == '"') { closed = true; break; }
                if (c < 0x20) return false;
                if (c == '\\') {
                    if (i >= end) return false;
                    char escape = s.charAt(i++);
                    if (escape == 'u') {
                        if (i + 4 > end || !s.substring(i, i + 4).matches("[0-9a-fA-F]{4}")) return false;
                        i += 4;
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) return false;
                }
            }
            if (!closed) return false;
            while (i < end && Character.isWhitespace(s.charAt(i))) i++;
            if (i == end) return true;
            if (s.charAt(i++) != ',') return false;
            while (i < end && Character.isWhitespace(s.charAt(i))) i++;
        }
        return false;
    }

    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(name + " must be an object");
        return (Map<String, Object>) value;
    }
    static Map<String, Object> optionalMap(Object value, String name) { return value == null ? Map.of() : map(value, name); }
    static String required(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof String s) || s.isBlank()) throw new IllegalArgumentException("missing " + key);
        return s;
    }
    static List<String> strings(Object value, String name) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(name + " must be a string array");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) throw new IllegalArgumentException(name + " must contain only strings");
            result.add(s);
        }
        return result;
    }
}
