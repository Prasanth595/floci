package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.ResourcePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Resource policies for Redshift Serverless snapshots. A policy is stored and echoed, not
 * evaluated: no principal named in it gains access.
 *
 * <p>A policy belongs to a snapshot but lives in its own store, so deleting the snapshot needs no
 * hook here. Each read checks that the snapshot is still there and drops the policy otherwise.
 */
@ApplicationScoped
public class RedshiftServerlessResourcePolicyService implements Resettable {

    private final AccountAwareStorageBackend<ResourcePolicy> policies;
    private final RedshiftServerlessService serverless;
    private final ObjectMapper objectMapper;

    @Inject
    public RedshiftServerlessResourcePolicyService(StorageFactory storageFactory,
                                                   RedshiftServerlessService serverless,
                                                   ObjectMapper objectMapper) {
        this.policies = storageFactory.create("redshiftserverless", "redshiftserverless-resourcepolicies.json",
                new TypeReference<Map<String, ResourcePolicy>>() {});
        this.serverless = serverless;
        this.objectMapper = objectMapper;
    }

    /** Replaces the policy on the snapshot {@code resourceArn} names. */
    public synchronized ResourcePolicy putResourcePolicy(String resourceArn, String policy, String region) {
        ServerlessSupport.requireText(resourceArn, "resourceArn");
        ServerlessSupport.requireText(policy, "policy");
        requireJsonObject(policy);
        requireSnapshot(resourceArn, region);
        ResourcePolicy stored = new ResourcePolicy(resourceArn, policy);
        policies.put(storageKey(region, resourceArn), stored);
        return new ResourcePolicy(resourceArn, policy);
    }

    public synchronized ResourcePolicy getResourcePolicy(String resourceArn, String region) {
        ServerlessSupport.requireText(resourceArn, "resourceArn");
        ResourcePolicy stored = require(resourceArn, region);
        return new ResourcePolicy(stored.getResourceArn(), stored.getPolicy());
    }

    public synchronized void deleteResourcePolicy(String resourceArn, String region) {
        ServerlessSupport.requireText(resourceArn, "resourceArn");
        require(resourceArn, region);
        policies.delete(storageKey(region, resourceArn));
    }

    @Override
    public void clear() {
        policies.clear();
    }

    private ResourcePolicy require(String resourceArn, String region) {
        ResourcePolicy stored = policies.get(storageKey(region, resourceArn)).orElse(null);
        if (stored != null && !snapshotExists(resourceArn, region)) {
            policies.delete(storageKey(region, resourceArn));
            stored = null;
        }
        if (stored == null) {
            throw ServerlessSupport.notFound("The resource " + resourceArn + " has no resource policy.");
        }
        return stored;
    }

    private void requireSnapshot(String resourceArn, String region) {
        if (!snapshotExists(resourceArn, region)) {
            throw ServerlessSupport.notFound("The resource " + resourceArn + " was not found.");
        }
    }

    private boolean snapshotExists(String resourceArn, String region) {
        String snapshotName = serverless.snapshotNameFromArn(resourceArn, region);
        return serverless.findSnapshot(snapshotName, region).isPresent();
    }

    private void requireJsonObject(String policy) {
        try {
            JsonNode parsed = objectMapper.readTree(policy);
            if (parsed == null || !parsed.isObject()) {
                throw ServerlessSupport.validation("policy must be a JSON policy document.");
            }
        } catch (JsonProcessingException e) {
            throw ServerlessSupport.validation("policy must be a JSON policy document.");
        }
    }

    private static String storageKey(String region, String resourceArn) {
        return region + "::" + resourceArn;
    }
}
