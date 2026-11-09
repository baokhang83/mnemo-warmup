package io.github.baokhang83.mnemo.warmup.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls an HTTP endpoint (for example {@code /actuator/health}) until it answers 2xx, optionally
 * drives some warmup traffic, and then lets the runner stop the application.
 *
 * <p>Compared with {@link SpringRefreshExitMode} this trains classes used when serving
 * requests, and it works for any framework.
 */
public final class HttpProbeExitMode implements ExitMode {

    public static final String NAME = "http-probe";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SCRIPT_TIMEOUT = Duration.ofMinutes(5);

    /**
     * @param url             the endpoint that signals readiness
     * @param pollInterval    pause between probes
     * @param warmupRequests  GET requests made once the probe succeeds, in order; absolute URLs, or
     *                        paths resolved against the probe URL's scheme and host
     * @param warmupScript    optional program run after the requests; it receives the base URL as its only
     *                        argument and must exit 0
     */
    public record Settings(URI url, Duration pollInterval, List<String> warmupRequests, Path warmupScript) {

        public Settings {
            Objects.requireNonNull(url, "url");
            String scheme = url.getScheme();
            if (url.getAuthority() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                throw new IllegalArgumentException("The probe URL must be an absolute http(s) URL: " + url);
            }
            if (pollInterval.isNegative() || pollInterval.isZero()) {
                throw new IllegalArgumentException("The poll interval must be positive");
            }
            warmupRequests = List.copyOf(warmupRequests);
        }

        public static Settings of(URI url) {
            return new Settings(url, Duration.ofMillis(500), List.of(), null);
        }

        public Settings withPollInterval(Duration interval) {
            return new Settings(url, interval, warmupRequests, warmupScript);
        }

        URI baseUrl() {
            return URI.create(url.getScheme() + "://" + url.getAuthority());
        }
    }

    private final Settings settings;
    private final CommandRunner scriptRunner;

    public HttpProbeExitMode(Settings settings) {
        this(settings, CommandRunner.system(SCRIPT_TIMEOUT));
    }

    HttpProbeExitMode(Settings settings, CommandRunner scriptRunner) {
        this.settings = settings;
        this.scriptRunner = scriptRunner;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> jvmArgs() {
        return List.of();
    }

    @Override
    public boolean supports(JarExtractor.Kind kind) {
        return true;
    }

    @Override
    public ReadyCondition readyCondition() {
        return this::await;
    }

    private void await(Process process, Duration timeout) throws TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            String lastProblem = "no response yet";
            while (true) {
                if (!process.isAlive()) {
                    return; // it ended on its own; the runner judges the exit code
                }
                try {
                    int status = get(client, settings.url());
                    if (status / 100 == 2) {
                        break;
                    }
                    lastProblem = "HTTP " + status;
                } catch (IOException e) {
                    lastProblem = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMillis <= 0) {
                    throw new TimeoutException(settings.url() + " did not return 2xx within " + timeout
                            + " (last result: " + lastProblem + ")");
                }
                // Sleeps between probes but wakes immediately if the application dies.
                process.waitFor(Math.min(settings.pollInterval().toMillis(), remainingMillis), TimeUnit.MILLISECONDS);
            }
            warmUp(client);
        }
    }

    private void warmUp(HttpClient client) throws InterruptedException {
        URI base = settings.baseUrl();
        for (String request : settings.warmupRequests()) {
            URI target = base.resolve(request);
            try {
                int status = get(client, target);
                if (status / 100 != 2) {
                    throw new IllegalStateException("Warmup request " + target + " returned HTTP " + status);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Warmup request " + target + " failed: " + e.getMessage(), e);
            }
        }
        if (settings.warmupScript() != null) {
            runScript(base);
        }
    }

    private void runScript(URI base) throws InterruptedException {
        try {
            var result = scriptRunner.run(List.of(settings.warmupScript().toString(), base.toString()));
            if (result.exitCode() != 0) {
                throw new IllegalStateException("Warmup script " + settings.warmupScript() + " exited with "
                        + result.exitCode() + ":\n" + result.output());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run warmup script " + settings.warmupScript() + ": " + e.getMessage(), e);
        }
    }

    private static int get(HttpClient client, URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
