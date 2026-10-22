package io.github.hectorvent.floci.core.storage;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.fail;

/** Thread helpers for the storage race tests. */
final class StorageTestThreads {

    private StorageTestThreads() {
    }

    /**
     * Waits until the thread has finished or is parked, on a lock for instance. Fails by name
     * after ten seconds, so a thread that neither finishes nor waits cannot hang the test.
     */
    static void awaitParkedOrDone(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.TERMINATED || state == Thread.State.WAITING) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("the thread neither finished nor waited: " + thread.getState());
    }
}
