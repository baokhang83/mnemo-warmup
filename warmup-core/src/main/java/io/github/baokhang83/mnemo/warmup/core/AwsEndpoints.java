package io.github.baokhang83.mnemo.warmup.core;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which AWS hosts to call for a region: the partition decides the DNS suffix ({@code .cn} for China). */
final class AwsEndpoints {

    /** {@code 123456789012.dkr.ecr.eu-west-1.amazonaws.com}, also {@code ecr-fips} and the China suffix. */
    private static final Pattern ECR_HOST =
            Pattern.compile("^\\d{12}\\.dkr\\.ecr(?:-fips)?\\.([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?$", Pattern.CASE_INSENSITIVE);

    private AwsEndpoints() {}

    /** The region of an ECR registry host (with optional port), or empty if it is not an ECR host. */
    static Optional<String> ecrRegion(String registryHost) {
        String host = registryHost.contains(":") ? registryHost.substring(0, registryHost.indexOf(':')) : registryHost;
        Matcher m = ECR_HOST.matcher(host);
        return m.matches() ? Optional.of(m.group(1).toLowerCase()) : Optional.empty();
    }

    static String dnsSuffix(String region) {
        return region.startsWith("cn-") ? "amazonaws.com.cn" : "amazonaws.com";
    }

    /** Regional STS endpoint: the global one is a single point of failure and is not served in every partition. */
    static URI sts(String region) {
        return URI.create("https://sts." + region + "." + dnsSuffix(region) + "/");
    }

    static URI ecrApi(String region) {
        return URI.create("https://api.ecr." + region + "." + dnsSuffix(region) + "/");
    }
}
