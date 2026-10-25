package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ManagedSecretRotationServiceTest {
    private static final String REGION = "us-east-1";

    @Test
    void rotatesOwnerAndPublishesRequestedVersionExactlyOnce() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        ManagedSecretRotator owner = owner(rotation -> rotation.commit(() -> {
            changes.incrementAndGet();
            return "new-password";
        }, () -> fail("unexpected rollback")));
        SecretsManagerService service = service(owner);
        try {
            Secret original = create(service);
            String previous = original.getCurrentVersionId();
            String token = UUID.randomUUID().toString();
            service.rotateSecret(original.getArn(), token, null, rules(), true, REGION);
            await(() -> service.describeSecret(original.getArn(), REGION).getLastRotatedDate() != null);
            assertEquals(token, service.describeSecret(original.getArn(), REGION).getCurrentVersionId());
            assertEquals("new-password", service.getSecretValue(original.getArn(), null, null, REGION).getSecretString());
            assertEquals(previous, service.getSecretValue(original.getArn(), null, "AWSPREVIOUS", REGION).getVersionId());
            service.rotateSecret(original.getArn(), token, null, null, true, REGION);
            assertEquals(1, changes.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void activeRotationBlocksMutationsAndCancellationSurvivesPublication() throws Exception {
        CountDownLatch changing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        SecretsManagerService service = service(owner(rotation -> rotation.commit(() -> {
            changing.countDown();
            try {
                assertTrue(finish.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return "new-password";
        }, () -> { })));
        try {
            Secret original = create(service);
            String activeToken = UUID.randomUUID().toString();
            service.rotateSecret(original.getArn(), activeToken, null, rules(), true, REGION);
            assertTrue(changing.await(5, TimeUnit.SECONDS));
            assertThrows(AwsException.class, () -> service.rotateSecret(original.getArn(),
                    UUID.randomUUID().toString(), null, rules(), true, REGION));
            assertThrows(AwsException.class, () -> service.putSecretValue(original.getArn(), "other", null, null, REGION, null));
            assertThrows(AwsException.class, () -> service.updateSecret(original.getArn(), "other", null, REGION));
            assertThrows(AwsException.class, () -> service.deleteSecret(original.getArn(), null, true, REGION));
            service.cancelRotateSecret(original.getArn(), REGION);
            finish.countDown();
            await(() -> service.describeSecret(original.getArn(), REGION).getLastRotatedDate() != null);
            assertFalse(service.describeSecret(original.getArn(), REGION).isRotationEnabled());
            assertNull(service.describeSecret(original.getArn(), REGION).getNextRotationDate());
        } finally {
            finish.countDown();
            service.shutdown();
        }
    }

    @Test
    void failedOwnerChangeRollsBackAndSameTokenCanRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        SecretsManagerService service = service(owner(rotation -> rotation.commit(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("backend refused password");
            }
            return "new-password";
        }, rollbacks::incrementAndGet)));
        try {
            Secret original = create(service);
            String token = UUID.randomUUID().toString();
            service.rotateSecret(original.getArn(), token, null, rules(), true, REGION);
            await(() -> !service.describeSecret(original.getArn(), REGION).isRotationEnabled());
            assertEquals("old-password", service.getSecretValue(original.getArn(), null, null, REGION).getSecretString());
            assertNull(service.describeSecret(original.getArn(), REGION).getLastRotatedDate());
            assertEquals(1, rollbacks.get());
            service.rotateSecret(original.getArn(), token, null, null, true, REGION);
            await(() -> service.describeSecret(original.getArn(), REGION).getLastRotatedDate() != null);
            assertEquals(token, service.describeSecret(original.getArn(), REGION).getCurrentVersionId());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void configureOnlyDoesNotChangePasswordAndSchedulerRotatesSupportedOwners() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        SecretsManagerService service = service(owner(rotation -> rotation.commit(() -> {
            changes.incrementAndGet();
            return "new-password";
        }, () -> { })));
        try {
            Secret original = create(service);
            service.rotateSecret(original.getArn(), UUID.randomUUID().toString(), null, rules(), false, REGION);
            assertEquals(0, changes.get());
            assertNull(original.getLastRotatedDate());
            new SecretRotationScheduler(service).tick(Instant.now().plusSeconds(31L * 86400));
            await(() -> service.describeSecret(original.getArn(), REGION).getLastRotatedDate() != null);
            assertEquals(1, changes.get());
        } finally {
            service.shutdown();
        }
    }

    private static SecretsManagerService service(ManagedSecretRotator owner) {
        return new SecretsManagerService(new InMemoryStorage<>(), 30,
                new RegionResolver(REGION, "000000000000"), null, new ObjectMapper(), null, List.of(owner));
    }

    private static Secret create(SecretsManagerService service) {
        return service.createSecret("managed", "old-password", null, null, null, null, "redshift", REGION);
    }

    private static Secret.RotationRules rules() {
        return new Secret.RotationRules(30, null, null);
    }

    private static ManagedSecretRotator owner(Consumer<ManagedSecretRotator.Rotation> action) {
        return new ManagedSecretRotator() {
            public boolean supports(String arn, String region) {
                return true;
            }

            public void rotate(String arn, String region, Rotation rotation) {
                action.accept(rotation);
            }
        };
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "rotation did not finish");
    }
}
