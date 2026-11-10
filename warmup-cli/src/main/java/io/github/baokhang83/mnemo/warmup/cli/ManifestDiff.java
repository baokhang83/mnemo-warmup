package io.github.baokhang83.mnemo.warmup.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Structural preview that reports paths without displaying workload or Secret values. */
final class ManifestDiff {
    private ManifestDiff() {}
    static List<String> describe(String before, String after) {
        List<Map<String, Object>> oldDocs = ManifestStream.parse(before), newDocs = ManifestStream.parse(after);
        List<String> changes = new ArrayList<>();
        int count = Math.max(oldDocs.size(), newDocs.size());
        for (int i = 0; i < count; i++) {
            if (i >= oldDocs.size()) { changes.add("+ resource " + identity(newDocs.get(i))); continue; }
            if (i >= newDocs.size()) { changes.add("- resource " + identity(oldDocs.get(i))); continue; }
            diff(oldDocs.get(i), newDocs.get(i), "resource[" + i + "]", changes);
        }
        return changes;
    }
    private static void diff(Object before, Object after, String path, List<String> out) {
        if (before instanceof Map<?, ?> a && after instanceof Map<?, ?> b) {
            TreeSet<String> keys = new TreeSet<>();
            a.keySet().forEach(k -> keys.add(String.valueOf(k))); b.keySet().forEach(k -> keys.add(String.valueOf(k)));
            for (String key : keys) {
                if (!a.containsKey(key)) out.add("+ " + path + "." + key);
                else if (!b.containsKey(key)) out.add("- " + path + "." + key);
                else diff(a.get(key), b.get(key), path + "." + key, out);
            }
        } else if (before instanceof List<?> a && after instanceof List<?> b) {
            int n = Math.max(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                if (i >= a.size()) out.add("+ " + path + "[" + i + "]");
                else if (i >= b.size()) out.add("- " + path + "[" + i + "]");
                else diff(a.get(i), b.get(i), path + "[" + i + "]", out);
            }
        } else if (!java.util.Objects.equals(before, after)) out.add("~ " + path + " (values hidden)");
    }
    private static String identity(Map<String, Object> doc) {
        Object meta = doc.get("metadata");
        Object name = meta instanceof Map<?, ?> m ? m.get("name") : null;
        return doc.get("kind") + "/" + name;
    }
}
