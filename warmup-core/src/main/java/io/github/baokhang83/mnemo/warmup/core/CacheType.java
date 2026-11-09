package io.github.baokhang83.mnemo.warmup.core;

import java.util.Locale;

/**
 * What the user asked for. {@link CacheStrategy} is what that request resolves to for a
 * concrete JDK.
 *
 * <ul>
 *   <li>{@link #AUTO}: AOT on JDK 25+, AppCDS otherwise.</li>
 *   <li>{@link #APPCDS}: always AppCDS.</li>
 *   <li>{@link #AOT}: always the Leyden AOT cache; an error on JDKs older than 25.</li>
 * </ul>
 */
public enum CacheType {
    AUTO,
    APPCDS,
    AOT;

    /** Parses {@code auto}, {@code appcds} or {@code aot}, ignoring case. */
    public static CacheType parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Unknown cache type '" + value + "'; expected one of: auto, appcds, aot");
        }
    }

    /**
     * Resolves this request for the given JDK feature version.
     *
     * @throws IllegalArgumentException if the JDK cannot provide the requested cache
     */
    public CacheStrategy resolve(int jdkFeatureVersion) {
        return switch (this) {
            case AUTO -> CacheStrategy.forFeatureVersion(jdkFeatureVersion);
            case APPCDS -> {
                requireMinimum(jdkFeatureVersion, CacheStrategy.APP_CDS_MIN_FEATURE, "AppCDS");
                yield CacheStrategy.APP_CDS;
            }
            case AOT -> {
                requireMinimum(jdkFeatureVersion, CacheStrategy.LEYDEN_MIN_FEATURE, "The AOT cache");
                yield CacheStrategy.LEYDEN_AOT;
            }
        };
    }

    public CacheStrategy resolve(JdkInfo jdk) {
        return resolve(jdk.featureVersion());
    }

    private static void requireMinimum(int actual, int minimum, String what) {
        if (actual < minimum) {
            throw new IllegalArgumentException(
                    what + " requires JDK " + minimum + "+ but the target is JDK " + actual
                            + "; use cache type 'auto' or 'appcds' instead");
        }
    }
}
