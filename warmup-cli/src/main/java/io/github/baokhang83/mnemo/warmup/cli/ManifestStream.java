package io.github.baokhang83.mnemo.warmup.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/** Parses a complete Kubernetes YAML stream without constructing arbitrary Java objects. */
final class ManifestStream {
    private ManifestStream() {}

    static List<Map<String, Object>> parse(String input) {
        LoaderOptions limits = new LoaderOptions();
        limits.setAllowDuplicateKeys(false);
        limits.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(limits));
        List<Map<String, Object>> documents = new ArrayList<>();
        try {
            for (Object document : yaml.loadAll(input)) {
                if (document == null) continue;
                if (!(document instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("Every YAML document must be a Kubernetes object");
                }
                Map<String, Object> object = new LinkedHashMap<>();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Resource keys must be strings");
                    }
                    object.put(key, entry.getValue());
                }
                documents.add(object);
            }
            return documents;
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new IllegalArgumentException("Invalid Kubernetes YAML: " + e.getMessage(), e);
        }
    }

    static String render(List<Map<String, Object>> documents) {
        if (documents.isEmpty()) return "";
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.Constructor(new LoaderOptions()),
                new Representer(options), options);
        return yaml.dumpAll(documents.iterator());
    }
}
