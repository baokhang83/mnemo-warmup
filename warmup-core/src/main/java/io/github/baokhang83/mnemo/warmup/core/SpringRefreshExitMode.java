package io.github.baokhang83.mnemo.warmup.core;

import java.util.List;

/**
 * Lets Spring end the run itself: {@code -Dspring.context.exit=onRefresh} makes the
 * application exit with status 0 as soon as the application context has refreshed.
 *
 * <p>Limitation: only the startup path is trained. Classes first loaded while serving a
 * request are not in the archive; use an HTTP-driven exit mode for those.
 */
public final class SpringRefreshExitMode implements ExitMode {

    public static final String NAME = "spring-refresh";
    static final String JVM_ARG = "-Dspring.context.exit=onRefresh";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> jvmArgs() {
        return List.of(JVM_ARG);
    }

    @Override
    public ReadyCondition readyCondition() {
        return ReadyCondition.processExits();
    }

    @Override
    public boolean supports(JarExtractor.Kind kind) {
        return kind == JarExtractor.Kind.SPRING_BOOT;
    }
}
