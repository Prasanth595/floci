package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.Environment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaWebEnvironmentManagerTest {

    private static final List<String> BASE = List.of("--expose-gc", "--max-http-header-size", "135168",
            "--no-experimental-require-module");
    private static final List<String> DEFAULT_HEAP = List.of("--max-semi-space-size=102", "--max-old-space-size=1024");

    @Test
    void heapFlagsInNodeOptionsReplaceBothDefaultHeapFlags() {
        assertEquals(flags(DEFAULT_HEAP), LambdaWebEnvironmentManager.nodeFlags(null));
        assertEquals(flags(List.of("--max-old-space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536"));
        assertEquals(flags(List.of("--max-semi-space-size=64")),
                LambdaWebEnvironmentManager.nodeFlags("--max-semi-space-size=64"));
        assertEquals(flags(List.of("--max-old-space-size=1536", "--max-semi-space-size=64")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536 --max-semi-space-size=64"));
        assertEquals(flags(List.of("--max-old-space-size=1536", "--max-old-space-size=1200")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536 --max-old-space-size=1200"));
        assertEquals(flags(List.of("--max_old_space_size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max_old_space_size=1536"));
        assertEquals(flags(List.of("--max-old_space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old_space-size=1536"));
        assertEquals(flags(List.of("--max-old-space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--expose-gc --max-old-space-size=1536 --title=y"));
        assertEquals(flags(List.of("--max-semi-space-size=32")),
                LambdaWebEnvironmentManager.nodeFlags("--expose-gc   --max-semi-space-size=32"));
        assertEquals(flags(List.of("--max-old-space-size")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size 1536"));
        assertEquals(flags(List.of("--max-old-space-size=\"1536\"")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=\"1536\""));
    }

    @Test
    void otherFlagsInNodeOptionsKeepTheDefaults() {
        List<String> defaults = flags(DEFAULT_HEAP);
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--expose-gc"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--max-http-header-size=1000"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--require ./pre.js"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("\"--max-old-space-size=1536\""));
    }

    private static List<String> flags(List<String> heap) {
        List<String> flags = new ArrayList<>(BASE);
        flags.addAll(heap);
        return flags;
    }

    @Test
    void onlyAnExitSeenBeforeTheRequestStartedAllowsARetry() {
        Environment environment = new Environment("pool", "rev-1", "000000000000");
        long started = System.nanoTime();
        assertFalse(environment.exitedBefore(started));

        environment.sawExit(started);

        assertFalse(environment.exitedBefore(started));
        assertFalse(environment.exitedBefore(started - 1));
        assertTrue(environment.exitedBefore(started + 1));
    }

    @Test
    void awaitExitEndsWhenTheExitIsSeen() throws Exception {
        Environment environment = new Environment("pool", "rev-1", "000000000000");
        CompletableFuture<Void> waited = environment.awaitExit(Duration.ofMinutes(1));
        assertFalse(waited.isDone());

        environment.sawExit(System.nanoTime());

        waited.get(5, TimeUnit.SECONDS);
    }

    @Test
    void awaitExitGivesUpOnAProcessThatKeepsRunning() throws Exception {
        Environment environment = new Environment("pool", "rev-1", "000000000000");

        environment.awaitExit(Duration.ofMillis(10)).get(5, TimeUnit.SECONDS);

        assertFalse(environment.exitedBefore(System.nanoTime()));
        environment.sawExit(System.nanoTime());
        assertTrue(environment.exitedBefore(System.nanoTime()));
    }
}
