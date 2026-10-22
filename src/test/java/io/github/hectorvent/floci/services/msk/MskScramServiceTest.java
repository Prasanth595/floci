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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MskScramServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER = "arn:aws:kafka:us-east-1:000000000000:cluster/scram/id";
    private static final String SECRET =
            "arn:aws:secretsmanager:us-east-1:000000000000:secret:AmazonMSK_alice";
    private AccountAwareStorageBackend<MskScramAssociations> associations;
    private SecretsManagerService secrets;
    private KmsService kms;
    private MskService clusters;
    private MskScramSecretsService service;

    @BeforeEach
    void setUp() {
        associations = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.<MskScramAssociations>create(
                eq("msk"), eq("msk-scram-associations.json"), any())).thenReturn(associations);
        clusters = mock(MskService.class);
        MskCluster cluster = new MskCluster();
        cluster.setClusterArn(CLUSTER);
        when(clusters.describeCluster(CLUSTER)).thenReturn(cluster);
        when(clusters.getClusterForAccount(ACCOUNT, CLUSTER)).thenReturn(Optional.of(cluster));
        secrets = mock(SecretsManagerService.class);
        when(secrets.describeSecret(anyString(), eq("us-east-1"))).thenAnswer(invocation -> {
            Secret metadata = new Secret();
            metadata.setArn(invocation.getArgument(0));
            metadata.setKmsKeyId("customer-key");
            return metadata;
        });
        kms = mock(KmsService.class);
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
    void reconciliationRemovesOnlyDeletedClusterAssociationsAcrossAccounts() {
        service.batchAssociateScramSecret(CLUSTER, List.of(SECRET));
        String otherAccount = "111111111111";
        String deletedCluster = CLUSTER.replace(ACCOUNT, otherAccount);
        associations.putForAccount(otherAccount, deletedCluster, new MskScramAssociations());
        when(clusters.getClusterForAccount(otherAccount, deletedCluster)).thenReturn(Optional.empty());

        service.reconcileScramSecrets();

        assertTrue(associations.getForAccount(ACCOUNT, CLUSTER).isPresent());
        assertTrue(associations.getForAccount(otherAccount, deletedCluster).isEmpty());
        verify(clusters).getClusterForAccount(otherAccount, deletedCluster);
    }

    @Test
    void reconciliationPreservesAssociationsWhenClusterLookupFails() {
        service.batchAssociateScramSecret(CLUSTER, List.of(SECRET));
        AwsException failure = new AwsException("InternalServerErrorException", "Lookup failed.", 500);
        when(clusters.getClusterForAccount(ACCOUNT, CLUSTER)).thenThrow(failure);

        assertEquals(failure, assertThrows(AwsException.class, () -> service.reconcileScramSecrets()));
        assertTrue(associations.getForAccount(ACCOUNT, CLUSTER).isPresent());
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

    @ParameterizedTest
    @ValueSource(strings = {"alias/msk-scram",
            "arn:aws:kms:us-east-1:000000000000:alias/msk-scram"})
    void rejectsKmsAliasReferencesBeforeKeyLookup(String keyReference) {
        Secret metadata = new Secret();
        metadata.setArn(SECRET);
        metadata.setKmsKeyId(keyReference);
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(CLUSTER, List.of(SECRET));

        assertEquals("BadRequestException", failures.getFirst().get("errorCode"));
        assertTrue(service.listScramSecrets(CLUSTER, null, null).items().isEmpty());
        verifyNoInteractions(kms);
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678-1234-1234-1234-123456789012",
            "arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012"})
    void acceptsKmsKeyIdsAndKeyArns(String keyReference) {
        Secret metadata = new Secret();
        metadata.setArn(SECRET);
        metadata.setKmsKeyId(keyReference);
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);
        when(kms.describeKey(keyReference, "us-east-1")).thenReturn(new KmsKey());

        assertTrue(service.batchAssociateScramSecret(CLUSTER, List.of(SECRET)).isEmpty());

        verify(kms).describeKey(keyReference, "us-east-1");
        assertEquals(List.of(SECRET), service.listScramSecrets(CLUSTER, null, null).items());
    }

    @Test
    void rejectsPartialSecretArnForAssociation() {
        String fullArn = SECRET + "-Ab12Cd";
        Secret metadata = new Secret();
        metadata.setArn(fullArn);
        metadata.setKmsKeyId("customer-key");
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(CLUSTER, List.of(SECRET));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", "InvalidSecretArn",
                "errorMessage", "Amazon MSK wasn't able to access the provided secret.")), failures);
        assertTrue(service.listScramSecrets(CLUSTER, null, null).items().isEmpty());
        verifyNoInteractions(kms);
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
        secretValue(fullArn, "{\"username\":\"alice\",\"password\":\"password\"}");
        assertTrue(service.batchAssociateScramSecret(CLUSTER, List.of(fullArn)).isEmpty());
        assertEquals(List.of(fullArn), service.listScramSecrets(CLUSTER, null, null).items());
    }

    @Test
    void rejectsPartialSecretArnForDisassociation() {
        String fullArn = SECRET + "-Ab12Cd";
        secretValue(fullArn, "{\"username\":\"alice\",\"password\":\"password\"}");
        assertTrue(service.batchAssociateScramSecret(CLUSTER, List.of(fullArn)).isEmpty());
        Secret metadata = new Secret();
        metadata.setArn(fullArn);
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchDisassociateScramSecret(CLUSTER, List.of(SECRET));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", "InvalidSecretArn",
                "errorMessage", "The provided secret ARN is invalid.")), failures);
        assertEquals(List.of(fullArn), service.listScramSecrets(CLUSTER, null, null).items());
        assertTrue(service.batchDisassociateScramSecret(CLUSTER, List.of(fullArn)).isEmpty());
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
