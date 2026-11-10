package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class KubernetesLeaseLockTest {

    private static final String KEY = "sha256:" + "ab".repeat(32);
    private static final String NAME = "mnemo-warmup-" + "ab".repeat(32);

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static KubernetesLeaseLock lock(FakeKubeApi fake, String holder, Clock clock, long seconds) {
        return new KubernetesLeaseLock(fake.api(), "default", holder, Duration.ofSeconds(seconds), clock);
    }

    @Test
    void leaseNamesAreDerivedFromTheFingerprintAndGarbageIsRejected() {
        assertEquals(NAME, KubernetesLeaseLock.leaseName(KEY));
        assertThrows(IllegalArgumentException.class, () -> KubernetesLeaseLock.leaseName("sha256:NOT_HEX!"));
    }

    @Test
    void onlyOneHolderAtATimeAndCloseReleases() throws Exception {
        try (var fake = new FakeKubeApi()) {
            var clock = new MutableClock();
            var a = lock(fake, "trainer-a", clock, 600);
            var b = lock(fake, "trainer-b", clock, 600);

            Optional<TrainingLock.Handle> first = a.tryAcquire(KEY);
            assertTrue(first.isPresent());
            assertTrue(first.get().held());
            assertEquals("trainer-a", fake.spec(NAME).get("holderIdentity"));
            assertTrue(b.tryAcquire(KEY).isEmpty(), "a live lease must not be taken");

            first.get().close();
            assertFalse(fake.leases.containsKey(NAME), "close deletes the lease");
            assertTrue(b.tryAcquire(KEY).isPresent());
        }
    }

    @Test
    void anExpiredLeaseIsTakenOver() throws Exception {
        try (var fake = new FakeKubeApi()) {
            var clock = new MutableClock();
            assertTrue(lock(fake, "crashed", clock, 600).tryAcquire(KEY).isPresent()); // never closed: a crash
            var b = lock(fake, "trainer-b", clock, 600);

            clock.now = clock.now.plusSeconds(599);
            assertTrue(b.tryAcquire(KEY).isEmpty(), "not expired yet");
            clock.now = clock.now.plusSeconds(2);
            assertTrue(b.tryAcquire(KEY).isPresent(), "expired, so taken over");
            assertEquals("trainer-b", fake.spec(NAME).get("holderIdentity"));
        }
    }

    @Test
    void theSameHolderMayReacquireAfterARestart() throws Exception {
        try (var fake = new FakeKubeApi()) {
            var clock = new MutableClock();
            assertTrue(lock(fake, "pod-1", clock, 600).tryAcquire(KEY).isPresent());
            assertTrue(lock(fake, "pod-1", clock, 600).tryAcquire(KEY).isPresent());
        }
    }

    @Test
    void ofManyTakersOfAnExpiredLeaseExactlyOneWins() throws Exception {
        try (var fake = new FakeKubeApi()) {
            var clock = new MutableClock();
            assertTrue(lock(fake, "crashed", clock, 600).tryAcquire(KEY).isPresent());
            clock.now = clock.now.plusSeconds(1000);

            int takers = 8;
            var pool = Executors.newFixedThreadPool(takers);
            var start = new CountDownLatch(1);
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < takers; i++) {
                var taker = lock(fake, "taker-" + i, clock, 600);
                Callable<Boolean> task = () -> {
                    start.await();
                    return taker.tryAcquire(KEY).isPresent();
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> f : results) {
                if (f.get()) {
                    winners++;
                }
            }
            pool.shutdownNow();
            assertEquals(1, winners);
        }
    }

    @Test
    void aForbiddenRequestExplainsTheMissingRbac() throws Exception {
        try (var fake = new FakeKubeApi()) {
            fake.forceStatus = 403;
            var ex = assertThrows(StoreException.class, () -> lock(fake, "a", new MutableClock(), 600).tryAcquire(KEY));
            assertTrue(ex.getMessage().contains("HTTP 403") && ex.getMessage().contains("leases"), ex.getMessage());
        }
    }

    @Test
    void anUnreachableApiServerIsAStoreException() {
        var lock = new KubernetesLeaseLock(
                KubeApi.at(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(2)), "default", "a", Duration.ofSeconds(60), Clock.systemUTC());
        assertThrows(StoreException.class, () -> lock.tryAcquire(KEY));
    }

    @Test
    void theHolderRenewsInTheBackgroundAndNoticesWhenItLosesTheLease() throws Exception {
        try (var fake = new FakeKubeApi()) {
            var handle = lock(fake, "a", Clock.systemUTC(), 3).tryAcquire(KEY).orElseThrow(); // renews every second
            String acquired = (String) fake.spec(NAME).get("renewTime");
            Thread.sleep(2300);
            assertTrue(!acquired.equals(fake.spec(NAME).get("renewTime")), "renewTime must advance while held");
            assertTrue(handle.held());

            fake.setHolder(NAME, "somebody-else", acquired, 3); // stolen
            Thread.sleep(1500);
            assertFalse(handle.held(), "a renewal that finds another holder must report the lock lost");
            handle.close();
            assertTrue(fake.leases.containsKey(NAME), "a lease held by someone else must not be deleted");
        }
    }

    @Test
    void noneAlwaysAcquires() {
        assertTrue(TrainingLock.none().tryAcquire(KEY).orElseThrow().held());
    }

    /** Real API server semantics: {@code kubectl proxy --port 8001} and {@code -Dwarmup.kubeApi=http://127.0.0.1:8001}. */
    @Test
    void behavesLikeTheRealLeaseApi() {
        String api = System.getProperty("warmup.kubeApi");
        org.junit.jupiter.api.Assumptions.assumeTrue(api != null && !api.isBlank(), "set -Dwarmup.kubeApi=http://127.0.0.1:8001 to run");
        String key = "sha256:" + java.util.UUID.randomUUID().toString().replace("-", "").repeat(2);
        var clock = new MutableClock();
        clock.now = Instant.now();
        var kube = KubeApi.at(URI.create(api), Duration.ofSeconds(10));
        var a = new KubernetesLeaseLock(kube, "default", "trainer-a", Duration.ofSeconds(60), clock);
        var b = new KubernetesLeaseLock(kube, "default", "trainer-b", Duration.ofSeconds(60), clock);

        var held = a.tryAcquire(key).orElseThrow();
        assertTrue(b.tryAcquire(key).isEmpty());
        clock.now = clock.now.plusSeconds(120);
        var taken = b.tryAcquire(key).orElseThrow(); // expired: takeover with resourceVersion
        assertTrue(a.tryAcquire(key).isEmpty());
        held.close(); // not the holder any more: must not delete b's lease
        assertTrue(a.tryAcquire(key).isEmpty());
        taken.close();
        var again = a.tryAcquire(key).orElseThrow();
        again.close(); // leave the cluster clean
    }
}
