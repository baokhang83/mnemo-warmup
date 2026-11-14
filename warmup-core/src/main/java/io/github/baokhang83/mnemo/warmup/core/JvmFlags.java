package io.github.baokhang83.mnemo.warmup.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Picks the JVM options that can make an archive unusable (heap size, GC, pointer layout) out of
 * everything passed to the JVM.
 *
 * <p>This is a <em>conservative</em> set: it errs towards including a flag. SoftMaxHeapSize is
 * excluded: the compatibility study found that changing this soft target preserves archive reuse.
 * Heap and GC settings remain because they can change the effective pointer layout.
 */
final class JvmFlags {

    private static final List<Pattern> RELEVANT = List.of(
            Pattern.compile("-Xm[sx]\\S+"),
            Pattern.compile("-XX:[+-]Use\\w*GC"),
            Pattern.compile("-XX:(Max|Min|Initial)RAMPercentage=\\S+"),
            Pattern.compile("-XX:(MaxHeapSize|MinHeapSize|InitialHeapSize)=\\S+"),
            Pattern.compile("-XX:(ObjectAlignmentInBytes|CompressedClassSpaceSize)=\\S+"),
            Pattern.compile("-XX:[+-]UseCompressed(Oops|ClassPointers)"),
            Pattern.compile("-XX:[+-]UseCompactObjectHeaders"));

    private JvmFlags() {}

    /**
     * Collects the relevant options from {@code jvmArgs} followed by the option strings the JVM
     * also reads from the environment ({@code JAVA_TOOL_OPTIONS}, {@code JDK_JAVA_OPTIONS}).
     * If an option is given more than once, the last occurrence wins, as in the JVM.
     * The result is sorted so it does not depend on the order of the command line.
     *
     * <p>Environment values are split on whitespace; quoting is not interpreted.
     */
    static List<String> relevant(List<String> jvmArgs, Map<String, String> environment) {
        List<String> all = new ArrayList<>(jvmArgs);
        for (String variable : new String[] {"JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS"}) {
            String value = environment.get(variable);
            if (value != null && !value.isBlank()) {
                all.addAll(List.of(value.trim().split("\\s+")));
            }
        }
        Map<String, String> byKey = new TreeMap<>();
        for (String arg : all) {
            if (RELEVANT.stream().anyMatch(p -> p.matcher(arg).matches())) {
                byKey.put(key(arg), arg);
            }
        }
        return List.copyOf(byKey.values());
    }

    /** {@code -Xmx512m -> -Xmx}, {@code -XX:+UseG1GC -> -XX:UseG1GC}, {@code -XX:Foo=1 -> -XX:Foo}. */
    private static String key(String arg) {
        if (arg.startsWith("-Xmx") || arg.startsWith("-Xms")) {
            return arg.substring(0, 4);
        }
        String name = arg.substring("-XX:".length());
        if (name.startsWith("+") || name.startsWith("-")) {
            return "-XX:" + name.substring(1);
        }
        int eq = name.indexOf('=');
        return "-XX:" + (eq < 0 ? name : name.substring(0, eq));
    }
}
