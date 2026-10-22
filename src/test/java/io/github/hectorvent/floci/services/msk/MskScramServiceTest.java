package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskScramAssociations;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService.BatchGetSecretValueResult;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService.BatchSecretValue;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MskScramServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER = "arn:aws:kafka:us-east-1:000000000000:cluster/scram/id";
    private static final String SECRET =
            "arn:aws:secretsmanager:us-east-1:000000000000:secret:AmazonMSK_alice";
    private AccountAwareStorageBackend<MskScramAssociations> associations;
    private SecretsManagerService secrets;
    private MskScramSecretsService service;

    @BeforeEach
    void setUp() {
        associations = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.<MskScramAssociations>create(
                eq("msk"), eq("msk-scram-associations.json"), any())).thenReturn(associations);
        MskService clusters = mock(MskService.class);
        MskCluster cluster = new MskCluster();
        cluster.setClusterArn(CLUSTER);
        when(clusters.describeCluster(CLUSTER)).thenReturn(cluster);
        secrets = mock(SecretsManagerService.class);
        Secret metadata = new Secret();
        metadata.setKmsKeyId("customer-key");
        when(secrets.describeSecret(anyString(), eq("us-east-1"))).thenReturn(metadata);
        KmsService kms = mock(KmsService.class);
        when(kms.describeKey("customer-key", "us-east-1")).thenReturn(new KmsKey());
        service = new MskScramSecretsService(storageFactory, clusters, secrets, kms, new ObjectMapper());
        secretValue(SECRET, "{\"username\":\"alice\",\"password\":\"password\"}");
    }

    private void secretValue(String arn, String value) {
        when(secrets.batchGetSecretValue(List.of(arn), "us-east-1")).thenReturn(
                new BatchGetSecretValueResult(List.of(new BatchSecretValue(
                        arn, "AmazonMSK_fixture", value, null, "version", List.of("AWSCURRENT"), null)), List.of()));
    }

    @Test
    void associatesListsAndDisassociatesMetadata() {
        assertTrue(service.batchAssociateScramSecret(CLUSTER, List.of(SECRET)).isEmpty());
        assertEquals(List.of(SECRET), service.listScramSecrets(CLUSTER, null, null).items());
        assertEquals("alice", associations.getForAccount(ACCOUNT, CLUSTER).orElseThrow()
                .getAssociations().get(SECRET).getUsername());

        assertTrue(service.batchDisassociateScramSecret(CLUSTER, List.of(SECRET)).isEmpty());
        assertTrue(service.listScramSecrets(CLUSTER, null, null).items().isEmpty());
        assertTrue(associations.getForAccount(ACCOUNT, CLUSTER).isEmpty());
    }

    @Test
    void acceptsReturnedSecretArnWithGeneratedSuffix() {
        String fullArn = SECRET + "-Ab12Cd";
        when(secrets.batchGetSecretValue(List.of(SECRET), "us-east-1"))
            .thenReturn(
                new BatchGetSecretValueResult(List.of(new BatchSecretValue(
                        fullArn, "AmazonMSK_alice", "{\"username\":\"alice\",\"password\":\"password\"}",
                        null, "version", List.of("AWSCURRENT"), null)), List.of())
            );

        assertTrue(service.batchAssociateScramSecret(CLUSTER, List.of(SECRET)).isEmpty());
        assertEquals(List.of(SECRET), service.listScramSecrets(CLUSTER, null, null).items());
        assertTrue(service.batchDisassociateScramSecret(CLUSTER, List.of(SECRET)).isEmpty());
    }

    @Test
    void rejectsReturnedSecretArnForDifferentSecret() {
        when(secrets.batchGetSecretValue(List.of(SECRET), "us-east-1"))
            .thenReturn(
                new BatchGetSecretValueResult(List.of(new BatchSecretValue(
                        SECRET + "Other", "AmazonMSK_fixture", "{\"username\":\"alice\",\"password\":\"password\"}",
                        null, "version", List.of("AWSCURRENT"), null)), List.of())
            );

        List<Map<String, String>> failures = service.batchAssociateScramSecret(CLUSTER, List.of(SECRET));

        assertEquals("InternalServerError", failures.getFirst().get("errorCode"));
        assertTrue(service.listScramSecrets(CLUSTER, null, null).items().isEmpty());
    }

    @Test
    void reportsItemFailuresWithoutBlockingValidSecrets() {
        String invalid = SECRET + "-invalid";
        secretValue(invalid, "{}");

        List<Map<String, String>> failures = service.batchAssociateScramSecret(CLUSTER, List.of(invalid, SECRET));

        assertEquals(1, failures.size());
        assertEquals(invalid, failures.getFirst().get("secretArn"));
        assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
        assertEquals(List.of(SECRET), service.listScramSecrets(CLUSTER, null, null).items());
    }

    @Test
    void rejectsRepeatedAssociationAndDisassociation() {
        service.batchAssociateScramSecret(CLUSTER, List.of(SECRET));
        assertEquals("InvalidSecretArn", service.batchAssociateScramSecret(CLUSTER, List.of(SECRET))
                .getFirst().get("errorCode"));
        service.batchDisassociateScramSecret(CLUSTER, List.of(SECRET));
        assertEquals("InvalidSecretArn", service.batchDisassociateScramSecret(CLUSTER, List.of(SECRET))
                .getFirst().get("errorCode"));
    }

    @Test
    void rejectsDuplicateAssociationBatchBeforeStoringMetadata() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.batchAssociateScramSecret(CLUSTER, List.of(SECRET, SECRET)));

        assertEquals("BadRequestException", error.getErrorCode());
        assertTrue(service.listScramSecrets(CLUSTER, null, null).items().isEmpty());
    }

    @Test
    void listsPersistedAssociationsAfterServiceRecreation() {
        service.batchAssociateScramSecret(CLUSTER, List.of(SECRET));
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.<MskScramAssociations>create(
                eq("msk"), eq("msk-scram-associations.json"), any())).thenReturn(associations);
        MskService clusters = mock(MskService.class);
        when(clusters.describeCluster(CLUSTER)).thenReturn(new MskCluster());
        MskScramSecretsService restored = new MskScramSecretsService(
                storageFactory, clusters, secrets, mock(KmsService.class), new ObjectMapper());

        assertEquals(List.of(SECRET), restored.listScramSecrets(CLUSTER, null, null).items());
    }
}
