package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpProbeExitModeTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger healthCalls = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<Process> processes = new ArrayList<>();
    private volatile int healthFailuresLeft;
    private volatile int healthFailureStatus = 503;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", ex -> {
            healthCalls.incrementAndGet();
            int status = healthFailuresLeft-- > 0 ? healthFailureStatus : 200;
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.createContext("/bad", ex -> {
            requests.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.createContext("/w", ex -> {
            requests.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void cleanUp() {
        server.stop(0);
        processes.forEach(Process::destroyForcibly);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /** A process that stays alive until the test ends. */
    private Process aliveProcess() throws IOException {
        Path jar = TestApps.jar(dir, "idle", TestApps.SLEEPER);
        Process p = new ProcessBuilder(JdkInfo.current().javaExecutable().toString(), "-jar", jar.toString())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("idle.log").toFile())
                .start();
        processes.add(p);
        return p;
    }

    private static HttpProbeExitMode.Settings settings(URI url, List<String> warmup, Path script) {
        return new HttpProbeExitMode.Settings(url, Duration.ofMillis(50), warmup, script);
    }

    @Test
    void becomesReadyOnceTheEndpointReturns2xx() throws Exception {
        healthFailuresLeft = 3;
        var mode = new HttpProbeExitMode(settings(url("/health"), List.of(), null));

        mode.readyCondition().await(aliveProcess(), Duration.ofSeconds(20));

        assertEquals(4, healthCalls.get(), "three 503s, then the 200");
    }

    @Test
    void timeoutNamesTheLastResponse() throws Exception {
        healthFailuresLeft = Integer.MAX_VALUE;
        var mode = new HttpProbeExitMode(settings(url("/health"), List.of(), null));

        TimeoutException e = assertThrows(
                TimeoutException.class, () -> mode.readyCondition().await(aliveProcess(), Duration.ofSeconds(1)));

        assertTrue(e.getMessage().contains("HTTP 503"), e.getMessage());
        assertTrue(e.getMessage().contains("/health"), e.getMessage());
    }

    @Test
    void anUnreachableEndpointTimesOutToo() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var mode = new HttpProbeExitMode(settings(URI.create("http://127.0.0.1:" + closedPort + "/health"), List.of(), null));

        TimeoutException e = assertThrows(
                TimeoutException.class, () -> mode.readyCondition().await(aliveProcess(), Duration.ofSeconds(1)));

        assertTrue(e.getMessage().contains("did not return 2xx"), e.getMessage());
    }

    @Test
    void returnsAtOnceWhenTheApplicationHasAlreadyExited() throws Exception {
        healthFailuresLeft = Integer.MAX_VALUE;
        Process finished = new ProcessBuilder(JdkInfo.current().javaExecutable().toString(), "-version")
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("v.log").toFile())
                .start();
        finished.waitFor();
        long start = System.nanoTime();

        new HttpProbeExitMode(settings(url("/health"), List.of(), null))
                .readyCondition()
                .await(finished, Duration.ofSeconds(30));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
        assertEquals(0, healthCalls.get(), "no point probing a dead process");
    }

    @Test
    void warmupRequestsRunInOrderAfterTheProbe() throws Exception {
        var mode = new HttpProbeExitMode(
                settings(url("/health"), List.of("/w?n=1", "/w?n=2", url("/w?n=3").toString()), null));

        mode.readyCondition().await(aliveProcess(), Duration.ofSeconds(20));

        assertEquals(List.of("/w?n=1", "/w?n=2", "/w?n=3"), requests);
    }

    @Test
    void aFailingWarmupRequestIsAnError() throws Exception {
        var mode = new HttpProbeExitMode(settings(url("/health"), List.of("/bad"), null));

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> mode.readyCondition().await(aliveProcess(), Duration.ofSeconds(20)));

        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
    }

    @Test
    void theWarmupScriptGetsTheBaseUrlAndMustSucceed() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        Path script = dir.resolve("warm.sh");
        var ok = new HttpProbeExitMode(settings(url("/health"), List.of(), script), command -> {
            commands.add(command);
            return new CommandRunner.CommandResult(0, "");
        });
        ok.readyCondition().await(aliveProcess(), Duration.ofSeconds(20));
        assertEquals(List.of(List.of(script.toString(), "http://127.0.0.1:" + server.getAddress().getPort())), commands);

        var failing = new HttpProbeExitMode(
                settings(url("/health"), List.of(), script),
                command -> new CommandRunner.CommandResult(2, "script said no"));
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> failing.readyCondition().await(aliveProcess(), Duration.ofSeconds(20)));
        assertTrue(e.getMessage().contains("exited with 2"), e.getMessage());
        assertTrue(e.getMessage().contains("script said no"), e.getMessage());
    }

    @Test
    void settingsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> HttpProbeExitMode.Settings.of(URI.create("/health")));
        assertThrows(IllegalArgumentException.class, () -> HttpProbeExitMode.Settings.of(URI.create("ftp://host/x")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpProbeExitMode.Settings(url("/health"), Duration.ZERO, List.of(), null));
    }

    @Test
    void parsingHttpProbeNeedsSettings() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExitMode.parse("http-probe"));
        assertTrue(e.getMessage().contains("--url"), e.getMessage());
        assertEquals("http-probe", ExitMode.parse("HTTP-Probe", HttpProbeExitMode.Settings.of(url("/health"))).name());
        assertThrows(IllegalArgumentException.class, () -> ExitMode.parse("magic", null));
    }

    @Test
    void trainsARealHttpApplicationEndToEnd() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        JdkInfo jdk = JdkInfo.current();
        CacheStrategy strategy = CacheType.AUTO.resolve(jdk);
        Path jar = TestApps.jar(dir, "http", TestApps.HTTP_SERVER);
        var mode = new HttpProbeExitMode(new HttpProbeExitMode.Settings(
                URI.create("http://127.0.0.1:" + port + "/health"), Duration.ofMillis(100), List.of("/warm?n=1"), null));
        var spec = TrainingSpec.builder(jdk.javaExecutable(), strategy, dir.resolve("app." + strategy.extension()))
                .jvmArg("-Dport=" + port)
                .appArgs("-jar", jar.toString())
                .ready(mode.readyCondition())
                .timeout(Duration.ofSeconds(60))
                .shutdownGrace(Duration.ofSeconds(60))
                .build();

        var result = new TrainingRunner().run(spec);

        assertTrue(result.stoppedByUs(), "a server never exits by itself");
        assertTrue(Files.size(result.archive()) > 0);
        assertTrue(Files.readString(result.logFile()).contains("WARM /warm?n=1"));
    }
}
