package io.github.baokhang83.mnemo.warmup.core;

import java.util.Optional;

/**
 * Keeps two trainers from training the same fingerprint at once (ADR 0002). A lock is advisory: training
 * is idempotent, so the worst outcome of a lost lock is duplicated work, never a wrong archive.
 */
public interface TrainingLock {

    /**
     * @param key what is being trained, normally the fingerprint
     * @return the held lock, or empty if another live holder has it
     * @throws StoreException if the lock service cannot be reached or refuses (for example missing RBAC)
     */
    Optional<Handle> tryAcquire(String key);

    /** A held lock. Closing releases it. */
    interface Handle extends AutoCloseable {
        /** Whether this holder still owns the lock (false after a failed renewal). */
        boolean held();

        @Override
        void close();
    }

    /** No coordination: always acquired. For a single trainer or a cluster without the Lease API. */
    static TrainingLock none() {
        return key -> Optional.of(new Handle() {
            @Override
            public boolean held() {
                return true;
            }

            @Override
            public void close() {}
        });
    }
}
