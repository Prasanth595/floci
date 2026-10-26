package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #6247: a call that arrives while the first call for an account and
 * region is still seeding the default network waits for it instead of reading a partial network.
 */
class Ec2DefaultResourcesSeedTest {

    private static final String REGION = "us-east-1";

    @Test
    void aConcurrentFirstCallSeesTheWholeDefaultNetwork() throws Exception {
        CountDownLatch seeding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Holds the first call in the middle of seeding, before any default subnet is stored.
        InMemoryStorage<String, Subnet> subnets = new InMemoryStorage<>() {
            @Override
            public void put(String key, Subnet value) {
                seeding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.put(key, value);
            }
        };
        Ec2Service service = newService(subnets);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.ensureDefaultResources(REGION));
            assertTrue(seeding.await(10, TimeUnit.SECONDS), "the first call starts seeding");
            Future<Integer> second = pool.submit(() -> service.describeSubnets(REGION, List.of(), Map.of()).size());
            Integer early = null;
            try {
                early = second.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException waiting) {
                // The second call waits for the first one to finish seeding.
            }
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            int seen = early != null ? early : second.get(10, TimeUnit.SECONDS);
            assertEquals(3, seen, "the second call sees the three default subnets");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Ec2Service newService(StorageBackend<String, Subnet> subnets) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        return new Ec2Service(config, null, mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog,
                new Ec2InstanceTypeCatalog(),
                new InMemoryStorage<>(), subnets, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
    }
}
