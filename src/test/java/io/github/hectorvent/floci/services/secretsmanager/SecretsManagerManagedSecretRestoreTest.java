package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretsManagerManagedSecretRestoreTest {

    private static final String REGION = "us-east-1";
    private static final String NAME = "redshift-serverless/namespace-id";

    private final SecretsManagerService service = new SecretsManagerService(new InMemoryStorage<>(), 30);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rollbackRefusesAReusedNameAndPreservesTheReplacementSecret() {
        Secret original = service.createSecret(NAME, "original", null, null, null, null,
                "redshift-serverless", REGION);
        Secret snapshot = mapper.convertValue(original, Secret.class);
        service.deleteSecret(original.getArn(), null, true, REGION);
        Secret replacement = service.createSecret(NAME, "replacement-v1", null, null, null, null,
                "redshift-serverless", REGION);
        service.putSecretValue(replacement.getArn(), "replacement-v2", null, null, REGION,
                List.of("AWSCURRENT", "custom-stage"));
        Secret replacementBefore = mapper.convertValue(replacement, Secret.class);
        assertNotEquals(snapshot.getArn(), replacement.getArn());

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.restoreManagedSecret(snapshot, REGION));

        assertEquals("ResourceExistsException", conflict.getErrorCode());
        assertEquals(mapper.valueToTree(replacementBefore),
                mapper.valueToTree(service.describeSecret(NAME, REGION)));
    }

    @Test
    void rollbackChecksTheAccountNamedInTheSnapshotArn() {
        AccountAwareStorageBackend<Secret> store = AccountAwareStorageBackend.inMemory("000000000000");
        SecretsManagerService accountService = new SecretsManagerService(store, 30);
        Secret snapshot = accountService.createSecret(NAME, "original", null, null, null, null,
                "redshift", REGION);
        Secret replacement = mapper.convertValue(snapshot, Secret.class);
        replacement.setArn("arn:aws:secretsmanager:us-east-1:111122223333:secret:" + NAME + "-AbCdEf");
        store.putForAccount("111122223333", REGION + "::" + NAME, replacement);
        Secret foreignSnapshot = mapper.convertValue(snapshot, Secret.class);
        foreignSnapshot.setArn("arn:aws:secretsmanager:us-east-1:111122223333:secret:" + NAME + "-GhIjKl");

        AwsException conflict = assertThrows(AwsException.class,
                () -> accountService.restoreManagedSecret(foreignSnapshot, REGION));

        assertEquals("ResourceExistsException", conflict.getErrorCode());
        assertEquals(replacement.getArn(), store.getForAccount("111122223333", REGION + "::" + NAME)
                .orElseThrow().getArn());
        assertEquals(snapshot.getArn(), accountService.describeSecret(NAME, REGION).getArn());
    }

    @Test
    void rollbackRestoresADeletedSecretWithItsOriginalArnAndVersions() {
        Secret original = service.createSecret(NAME, "original-v1", null, null, null, null,
                "redshift-serverless", REGION);
        service.putSecretValue(original.getArn(), "original-v2", null, null, REGION, null);
        Secret snapshot = mapper.convertValue(original, Secret.class);
        service.deleteSecret(original.getArn(), null, true, REGION);

        service.restoreManagedSecret(snapshot, REGION);

        assertEquals(mapper.valueToTree(snapshot), mapper.valueToTree(service.describeSecret(NAME, REGION)));
    }

    @Test
    void rollbackRestoresTheSnapshotWhenTheCurrentArnMatches() {
        Secret original = service.createSecret(NAME, "original", null, null, null, null,
                "redshift-serverless", REGION);
        Secret snapshot = mapper.convertValue(original, Secret.class);
        SecretVersion changed = service.putSecretValue(original.getArn(), "changed", null, null, REGION, null);
        assertNotEquals(snapshot.getCurrentVersionId(), changed.getVersionId());

        service.restoreManagedSecret(snapshot, REGION);

        assertEquals(mapper.valueToTree(snapshot), mapper.valueToTree(service.describeSecret(NAME, REGION)));
    }
}
