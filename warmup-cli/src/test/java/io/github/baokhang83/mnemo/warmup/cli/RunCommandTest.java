package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandTest {

    private static final Path SCRIPT = Path.of("..", "scripts", "mnemo-warmup-exec").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    private record Result(int exit, String out, String err) {}

    private static Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        var cmd = Main.commandLine();
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return new Result(cmd.execute(args), out.toString(), err.toString());
    }

    @Test
    void printWithoutAnArchiveEmitsTheOriginalCommandShellQuoted() {
        Result r = run("run", "--print", "--cache-dir", dir.toString(), "--", "java", "-Dgreeting=hello world", "-jar", "/app/it's.jar");
        assertEquals(0, r.exit(), r.err());
        assertEquals("'java' '-Dgreeting=hello world' '-jar' '/app/it'\\''s.jar'\n", r.out().replace("\r\n", "\n"));
        assertTrue(r.err().contains("run: no archive was fetched"), r.err());
    }

    @Test
    void quotingRoundTripsThroughARealShell() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        List<String> args = List.of("a b", "it's", "$HOME", "`x`", "\"q\"", "", "*");
        String quoted = Main.Run.shellQuote(args);
        Process p = new ProcessBuilder("/bin/sh", "-c", "for a in " + quoted + "; do printf '[%s]' \"$a\"; done").start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals("[a b][it's][$HOME][`x`][\"q\"][][*]", out);
    }

    @Test
    void spawnPropagatesTheChildExitCode() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        assertEquals(7, run("run", "--spawn", "--cache-dir", dir.toString(), "--", "/bin/sh", "-c", "exit 7").exit());
        assertEquals(0, run("run", "--cache-dir", dir.toString(), "--", "/bin/sh", "-c", "exit 0").exit());
    }

    @Test
    void spawnOfAMissingProgramIs127() {
        Result r = run("run", "--spawn", "--cache-dir", dir.toString(), "--", "/no/such/program");
        assertEquals(127, r.exit());
        assertTrue(r.err().contains("cannot start"), r.err());
    }

    @Test
    void printAndSpawnAreExclusive() {
        assertEquals(2, run("run", "--print", "--spawn", "--", "java").exit());
    }

    @Test
    void badOptionsBeforeTheDoubleDashStillStartTheCommandUnchanged() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        Result printed = run("run", "--print", "--no-such-option", "--", "java", "-jar", "app.jar");
        assertEquals(0, printed.exit(), printed.err());
        assertEquals("'java' '-jar' 'app.jar'\n", printed.out().replace("\r\n", "\n"));
        assertTrue(printed.err().contains("starting the command unchanged"), printed.err());

        assertEquals(5, run("run", "--no-such-option", "--", "/bin/sh", "-c", "exit 5").exit());
    }

    @Test
    void withoutACommandItIsAUsageError() {
        assertEquals(2, run("run", "--print").exit());
        assertEquals(2, run("run", "--bogus").exit());
    }

    @Test
    void theWrapperScriptExecsWhatRunPrintsAndFallsBackToTheOriginalOtherwise() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")) && Files.isRegularFile(SCRIPT), "needs /bin/sh and scripts/mnemo-warmup-exec");
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path stub = bin.resolve("mnemo-warmup");

        // run succeeds and plans a different command: that one is exec'd
        Files.writeString(stub, "#!/bin/sh\necho \"'echo' 'planned' 'with space'\"\n");
        stub.toFile().setExecutable(true);
        assertEquals("planned with space\n", wrapper(bin, "echo", "original"));

        // run fails: the original command is exec'd unchanged
        Files.writeString(stub, "#!/bin/sh\nexit 3\n");
        assertEquals("original\n", wrapper(bin, "echo", "original"));

        // run prints nothing: the original command is exec'd unchanged
        Files.writeString(stub, "#!/bin/sh\nexit 0\n");
        assertEquals("original\n", wrapper(bin, "echo", "original"));

        // run is not installed at all
        Files.delete(stub);
        assertEquals("original\n", wrapper(bin, "echo", "original"));
    }

    @Test
    void theWrapperExecsSoTheProgramIsTheProcessItself() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")) && Files.isRegularFile(SCRIPT), "needs /bin/sh and scripts/mnemo-warmup-exec");
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path stub = bin.resolve("mnemo-warmup");
        Files.writeString(stub, "#!/bin/sh\ncat <<'EOT'\n'/bin/sh' '-c' 'echo $$ >&2'\nEOT\n");
        stub.toFile().setExecutable(true);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", SCRIPT.toString(), "unused");
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        Process p = pb.start();
        long wrapperPid = p.pid();
        String err = new String(p.getErrorStream().readAllBytes()).trim();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals(String.valueOf(wrapperPid), err.split("\\s+")[0], "exec must keep the wrapper's PID, so signals reach the program: " + err);
    }

    private String wrapper(Path bin, String... command) throws Exception {
        List<String> argv = new java.util.ArrayList<>(List.of("/bin/sh", SCRIPT.toString()));
        argv.addAll(List.of(command));
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        return out;
    }
}
