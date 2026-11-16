package io.github.baokhang83.mnemo.warmup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FailOpenOnErrorTest {

    @Test
    void anOrdinaryExitCodeIsPassedThrough() {
        assertEquals(3, Main.exitCode(new String[] {"fetch"}, () -> 3));
    }

    // fetch is an init container: running out of memory on a hostile response must not keep the pod from starting
    @Test
    void fetchThatRunsOutOfMemoryStartsWithoutACacheAndExitsZero() {
        assertEquals(0, Main.exitCode(new String[] {"fetch", "--store", "oci://r/x"}, () -> { throw new OutOfMemoryError("test"); }));
        assertEquals(0, Main.exitCode(new String[] {"fetch"}, () -> { throw new StackOverflowError(); }));
    }

    @Test
    void strictFetchStillFails() {
        var error = new OutOfMemoryError("test");
        assertSame(error, assertThrows(OutOfMemoryError.class, () -> Main.exitCode(new String[] {"fetch", "--strict"}, () -> { throw error; })));
    }

    @Test
    void otherCommandsKeepTheirNormalBehaviour() {
        for (String command : new String[] {"run", "train", "build", "bench"}) {
            assertThrows(OutOfMemoryError.class, () -> Main.exitCode(new String[] {command}, () -> { throw new OutOfMemoryError("test"); }));
        }
    }
}
