package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskScramAssociations;
import io.github.hectorvent.floci.services.msk.model.ScramSecretAssociation;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService.BatchGetSecretValueError;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService.BatchGetSecretValueResult;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService.BatchSecretValue;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class MskScramSecretsService {

    private static final int MAX_PAGE = 100;

    private final AccountAwareStorageBackend<MskScramAssociations> storage;
    private final MskService mskService;
    private final SecretsManagerService secretsManagerService;
    private final KmsService kmsService;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();

    @Inject
    public MskScramSecretsService(StorageFactory storageFactory, MskService mskService,
                                  SecretsManagerService secretsManagerService,
                                  KmsService kmsService, ObjectMapper objectMapper) {
        this.storage = storageFactory.create("msk", "msk-scram-associations.json",
                new TypeReference<Map<String, MskScramAssociations>>() {});
        this.mskService = mskService;
        this.secretsManagerService = secretsManagerService;
        this.kmsService = kmsService;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, String>> batchAssociateScramSecret(String clusterArn, List<String> secretArns) {
        synchronized (lock) {
            MskCluster cluster = scramCluster(clusterArn);
            validateSecretArns(secretArns);
            if (new HashSet<>(secretArns).size() != secretArns.size()) {
                throw new AwsException("BadRequestException", "secretArnList must not contain duplicate ARNs.", 400,
                        Map.of("invalidParameter", "secretArnList"));
            }
            MskScramAssociations state = associationsFor(clusterArn);
            List<Map<String, String>> unprocessedScramSecrets = new ArrayList<>();
            for (String secretArn : secretArns) {
                boolean newAssociation = !state.getAssociations().containsKey(secretArn);
                try {
                    validateSecretArn(clusterArn, secretArn);
                    if (!newAssociation) {
                        throw new AwsException("InvalidSecretArn", "The provided secret ARN is invalid.", 400);
                    }
                    validateSecretEncryption(secretArn);
                    // Amazon MSK reads only the AWSCURRENT version of an associated secret
                    BatchSecretValue secretValue = readSecret(secretArn);
                    ScramSecretAssociation association = state.getAssociations()
                            .computeIfAbsent(secretArn, ignored -> new ScramSecretAssociation());
                    ScramCredentials credentials = readScramCredentials(secretValue.secretString());
                    association.setUsername(credentials.username());
                    putAssociations(clusterArn, state);
                } catch (AwsException e) {
                    if (newAssociation && state.getAssociations().remove(secretArn) != null) {
                        putAssociations(clusterArn, state);
                    }
                    unprocessedScramSecrets.add(scramFailure(secretArn, e));
                }
            }
            return unprocessedScramSecrets;
        }
    }

    public PaginatedResult<String> listScramSecrets(String clusterArn, Integer maxResults, String nextToken) {
        synchronized (lock) {
            scramCluster(clusterArn);
            return Pagination.paginate(new ArrayList<>(associationsFor(clusterArn).getAssociations().keySet()),
                    arn -> arn, maxResults, nextToken, MAX_PAGE, "BadRequestException");
        }
    }

    public List<Map<String, String>> batchDisassociateScramSecret(String clusterArn, List<String> secretArns) {
        synchronized (lock) {
            MskCluster cluster = scramCluster(clusterArn);
            validateSecretArns(secretArns);
            MskScramAssociations state = associationsFor(clusterArn);
            List<Map<String, String>> unprocessedScramSecrets = new ArrayList<>();
            for (String secretArn : secretArns) {
                try {
                    validateSecretArn(clusterArn, secretArn);
                    if (!state.getAssociations().containsKey(secretArn)) {
                        throw new AwsException("InvalidSecretArn", "The provided secret ARN is invalid.", 400);
                    }
                    state.getAssociations().remove(secretArn);
                    putAssociations(clusterArn, state);
                } catch (AwsException e) {
                    unprocessedScramSecrets.add(scramFailure(secretArn, e));
                }
            }
            return unprocessedScramSecrets;
        }
    }

    private MskCluster scramCluster(String clusterArn) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        if (mskService.isServerless(cluster)) {
            throw new AwsException("BadRequestException",
                    "SCRAM secrets are not supported for serverless clusters.", 400,
                    Map.of("invalidParameter", "clusterArn"));
        }
        return cluster;
    }

    private void validateSecretArns(List<String> secretArns) {
        if (secretArns == null || secretArns.isEmpty() || secretArns.size() > 10
                || secretArns.stream().anyMatch(arn -> arn == null || arn.isBlank())) {
            throw new AwsException("BadRequestException",
                    "secretArnList must contain between 1 and 10 secret ARNs.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
    }

    private void validateSecretArn(String clusterArn, String secretArn) {
        if (!AwsArnUtils.isArn(secretArn)) {
            throw new AwsException("BadRequestException", "Invalid secret ARN.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(secretArn);
        AwsArnUtils.Arn owner = AwsArnUtils.parse(clusterArn);
        if (!"secretsmanager".equals(arn.service()) || !arn.resource().startsWith("secret:")
                || !owner.partition().equals(arn.partition()) || !owner.region().equals(arn.region())
                || !owner.accountId().equals(arn.accountId())) {
            throw new AwsException("BadRequestException",
                    "Secret must belong to the cluster's account, region and partition.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
        String secretName = arn.resource().substring("secret:".length());
        if (!secretName.startsWith("AmazonMSK_")) {
            throw new AwsException("BadRequestException",
                    "The name of secrets associated with an Amazon MSK cluster must have the prefix AmazonMSK_.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
    }

    private void validateSecretEncryption(String secretArn) {
        String region = AwsArnUtils.parse(secretArn).region();
        Secret secret = secretsManagerService.describeSecret(secretArn, region);
        String keyId = secret.getKmsKeyId();
        if (keyId != null && keyId.startsWith("alias/")) {
            throw new AwsException("BadRequestException",
                    "SCRAM secrets must reference a KMS key ID instead of an alias name.", 400,
                    Map.of("invalidParameter", "secretArnList"));
        }
        if (keyId != null && !keyId.isBlank()) {
            KmsKey key = kmsService.describeKey(keyId, region);
            if ("CUSTOMER".equals(key.getKeyManager())
                    && key.getKeySpec() == KmsKeySpec.SYMMETRIC_DEFAULT
                    && key.getKeyUsage() == KmsKeyUsage.ENCRYPT_DECRYPT) {
                return;
            }
        }
        throw new AwsException("BadRequestException",
                "SCRAM secrets must be encrypted with a customer-managed symmetric KMS key.", 400,
                Map.of("invalidParameter", "secretArnList"));
    }

    private Map<String, String> scramFailure(String secretArn, AwsException error) {
        return Map.of("secretArn", secretArn, "errorCode", error.getErrorCode(), "errorMessage", error.getMessage());
    }

    private MskScramAssociations associationsFor(String clusterArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(clusterArn);
        return storage.getForAccount(arn.accountId(), clusterArn).orElseGet(MskScramAssociations::new);
    }

    private void putAssociations(String clusterArn, MskScramAssociations associations) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(clusterArn);
        if (associations.getAssociations().isEmpty()) {
            storage.deleteForAccount(arn.accountId(), clusterArn);
        } else {
            storage.putForAccount(arn.accountId(), clusterArn, associations);
        }
    }

    private BatchSecretValue readSecret(String secretArn) {
        // Exact-ARN batch reads select AWSCURRENT without updating access timestamps.
        BatchGetSecretValueResult result = secretsManagerService.batchGetSecretValue(
                List.of(secretArn), AwsArnUtils.parse(secretArn).region());
        if (result != null && result.values() != null && result.errors() != null) {
            if (result.values().isEmpty() && result.errors().size() == 1) {
                BatchGetSecretValueError error = result.errors().getFirst();
                if (error != null && secretArn.equals(error.secretId())
                        && error.errorCode() != null && error.message() != null) {
                    int status = "AccessDeniedException".equals(error.errorCode()) ? 403 : 400;
                    throw new AwsException(error.errorCode(), error.message(), status);
                }
            } else if (result.values().size() == 1 && result.errors().isEmpty()) {
                BatchSecretValue value = result.values().getFirst();
                if (value != null && secretArn.equals(value.arn()) && value.versionId() != null) {
                    return value;
                }
            }
        }
        throw new AwsException("InternalServerError", "Unexpected Secrets Manager batch response.", 500);
    }

    record ScramCredentials(String username, String password) {
    }

    ScramCredentials readScramCredentials(String secretString) {
        if (secretString == null) {
            throw new AwsException("InternalServerError",
                    "The request could not be processed because of an internal error. Try again.", 500);
        }
        JsonNode credentials;
        try {
            credentials = objectMapper.readTree(secretString);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidSecretArn", "The provided secret has an invalid schema.", 400);
        }
        if (credentials != null && credentials.isNull()) {
            throw new AwsException("InternalServerError",
                "The request could not be processed because of an internal error. Try again.", 500);
        }
        if (credentials == null || !credentials.isObject()) {
            throw new AwsException("InvalidSecretArn", "The provided secret has an invalid schema.", 400);
        }
        JsonNode username = credentials.path("username");
        if ((!username.isTextual() && !username.isNumber()) || username.asText().isBlank()) {
            throw new AwsException("InvalidSecretArn", "The provided secret has an invalid schema.", 400);
        }
        JsonNode password = credentials.path("password");
        if ((!password.isTextual() && !password.isBoolean()) || password.asText().isBlank()) {
            throw new AwsException("InvalidSecretArn", "The provided secret has an invalid schema.", 400);
        }
        return new ScramCredentials(username.asText(), password.asText());
    }
}
