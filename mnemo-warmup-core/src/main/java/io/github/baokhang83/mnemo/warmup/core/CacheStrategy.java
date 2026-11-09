package io.github.baokhang83.mnemo.warmup.core;

/**
 * Which archive mechanism to use for a given target JDK.
 *
 * <ul>
 *   <li>{@link #APP_CDS}: dynamic AppCDS archive ({@code .jsa}) via {@code -XX:ArchiveClassesAtExit}.</li>
 *   <li>{@link #LEYDEN_AOT}: Project Leyden AOT cache ({@code .aot}) via {@code -XX:AOTCacheOutput}
 *       (single-step ergonomics, JEP 514, JDK 25+).</li>
 * </ul>
 */
public enum CacheStrategy {
    APP_CDS("jsa"),
    LEYDEN_AOT("aot");

    /**
     * First JDK feature release with single-step {@code -XX:AOTCacheOutput} (JEP 514).
     * JDK 24 has the AOT cache but needs a two-step record/create flow, so it uses AppCDS here.
     */
    public static final int LEYDEN_MIN_FEATURE = 25;
    /** Oldest JDK feature release we support for AppCDS. */
    public static final int APP_CDS_MIN_FEATURE = 21;

    private final String extension;

    CacheStrategy(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    /** Picks the best strategy for the given JDK feature version (e.g. 21, 25). */
    public static CacheStrategy forFeatureVersion(int feature) {
        if (feature < APP_CDS_MIN_FEATURE) {
            throw new IllegalArgumentException(
                    "Unsupported JDK " + feature + "; mnemo-warmup requires JDK " + APP_CDS_MIN_FEATURE + "+");
        }
        return feature >= LEYDEN_MIN_FEATURE ? LEYDEN_AOT : APP_CDS;
    }

    /** JVM flag that records the archive during the training run. */
    public String trainingFlag(String archivePath) {
        return switch (this) {
            case APP_CDS -> "-XX:ArchiveClassesAtExit=" + archivePath;
            case LEYDEN_AOT -> "-XX:AOTCacheOutput=" + archivePath;
        };
    }

    /** JVM flag that consumes the archive at runtime. */
    public String runtimeFlag(String archivePath) {
        return switch (this) {
            case APP_CDS -> "-XX:SharedArchiveFile=" + archivePath;
            case LEYDEN_AOT -> "-XX:AOTCache=" + archivePath;
        };
    }
}
