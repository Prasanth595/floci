package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.EndpointAccess;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Redshift Serverless endpoint access (managed VPC endpoints). An endpoint is recorded and echoed:
 * Floci creates no VPC endpoint, so its {@code address} and {@code port} are the workgroup's own,
 * which is where a client can actually connect.
 *
 * <p>An endpoint belongs to a workgroup but lives in its own store, so deleting the workgroup needs
 * no hook here. Each read checks that the workgroup is still there and drops the endpoint otherwise.
 */
@ApplicationScoped
public class RedshiftServerlessEndpointAccessService implements Resettable {

    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final int PAGE_SIZE = 100;

    private final AccountAwareStorageBackend<EndpointAccess> endpoints;
    private final RegionResolver regionResolver;
    private final RedshiftServerlessService serverless;

    @Inject
    public RedshiftServerlessEndpointAccessService(StorageFactory storageFactory, RegionResolver regionResolver,
                                                   RedshiftServerlessService serverless) {
        this.endpoints = storageFactory.create("redshiftserverless", "redshiftserverless-endpointaccess.json",
                new TypeReference<Map<String, EndpointAccess>>() {});
        this.regionResolver = regionResolver;
        this.serverless = serverless;
    }

    public synchronized EndpointAccess createEndpointAccess(String endpointName, String workgroupName,
                                                            List<String> subnetIds,
                                                            List<String> vpcSecurityGroupIds,
                                                            String ownerAccount, String region) {
        ServerlessSupport.requireText(endpointName, "endpointName");
        ServerlessSupport.requireText(workgroupName, "workgroupName");
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw ServerlessSupport.validation("subnetIds is required.");
        }
        if (vpcSecurityGroupIds == null || vpcSecurityGroupIds.isEmpty()) {
            throw ServerlessSupport.validation("vpcSecurityGroupIds is required.");
        }
        if (ownerAccount != null && !ACCOUNT_ID.matcher(ownerAccount).matches()) {
            throw ServerlessSupport.validation("ownerAccount must be a 12-digit account id.");
        }
        Workgroup workgroup = serverless.findWorkgroup(workgroupName, region)
                .filter(found -> ownerAccount == null || ownerAccount.equals(regionResolver.getAccountId()))
                .orElseThrow(() -> ServerlessSupport.notFound("The workgroup " + workgroupName + " was not found."));
        if (live(region).stream().anyMatch(existing -> existing.getEndpointName().equals(endpointName))) {
            throw ServerlessSupport.conflict("The endpoint " + endpointName + " already exists.");
        }

        EndpointAccess endpoint = new EndpointAccess();
        endpoint.setEndpointName(endpointName);
        endpoint.setEndpointArn(regionResolver.buildArn("redshift-serverless", region,
                "managedvpcendpoint/" + UUID.randomUUID()));
        endpoint.setWorkgroupName(workgroupName);
        endpoint.setOwnerId(workgroup.getWorkgroupId());
        endpoint.setStatus("ACTIVE");
        endpoint.setCreateTime(Instant.now());
        endpoint.setAddress(workgroup.getEndpoint() == null ? null : workgroup.getEndpoint().getAddress());
        endpoint.setPort(workgroup.getEndpoint() == null ? workgroup.getPort() : workgroup.getEndpoint().getPort());
        endpoint.setSubnetIds(subnetIds);
        endpoint.setNetworkInterfaceIds(networkInterfaceIdsFor(subnetIds));
        endpoint.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        endpoint.setVpcEndpointId("vpce-" + hex(17));
        endpoints.put(storageKey(region, endpointName), endpoint);
        return new EndpointAccess(endpoint);
    }

    public synchronized EndpointAccess getEndpointAccess(String endpointName, String region) {
        return new EndpointAccess(require(endpointName, region));
    }

    public synchronized PaginatedResult<EndpointAccess> listEndpointAccess(String workgroupName, String vpcId,
                                                                           String ownerAccount, String region,
                                                                           Integer maxResults, String nextToken) {
        // No VPC is modelled, so an endpoint never belongs to a requested VPC.
        boolean wrongVpc = vpcId != null && !vpcId.isBlank();
        boolean wrongOwner = ownerAccount != null && !ownerAccount.equals(regionResolver.getAccountId());
        List<EndpointAccess> matching = live(region).stream()
                .filter(endpoint -> !wrongVpc && !wrongOwner)
                .filter(endpoint -> workgroupName == null || workgroupName.equals(endpoint.getWorkgroupName()))
                .sorted(Comparator.comparing(EndpointAccess::getEndpointName))
                .map(EndpointAccess::new)
                .toList();
        return Pagination.paginate(matching, EndpointAccess::getEndpointName, maxResults, nextToken,
                PAGE_SIZE, PAGE_SIZE, "InvalidPaginationException");
    }

    /** Replaces the endpoint's security groups, the only member AWS lets an update change. */
    public synchronized EndpointAccess updateEndpointAccess(String endpointName, List<String> vpcSecurityGroupIds,
                                                            String region) {
        EndpointAccess updated = new EndpointAccess(require(endpointName, region));
        if (vpcSecurityGroupIds != null) {
            updated.setVpcSecurityGroupIds(vpcSecurityGroupIds);
            endpoints.put(storageKey(region, endpointName), updated);
        }
        return new EndpointAccess(updated);
    }

    public synchronized EndpointAccess deleteEndpointAccess(String endpointName, String region) {
        EndpointAccess existing = new EndpointAccess(require(endpointName, region));
        endpoints.delete(storageKey(region, endpointName));
        existing.setStatus("DELETING");
        return existing;
    }

    @Override
    public void clear() {
        endpoints.clear();
    }

    private EndpointAccess require(String endpointName, String region) {
        ServerlessSupport.requireText(endpointName, "endpointName");
        EndpointAccess endpoint = endpoints.get(storageKey(region, endpointName)).orElse(null);
        if (endpoint != null && !isOnSameWorkgroup(endpoint, region)) {
            endpoints.delete(storageKey(region, endpointName));
            endpoint = null;
        }
        if (endpoint == null) {
            throw ServerlessSupport.notFound("The endpoint " + endpointName + " was not found.");
        }
        return endpoint;
    }

    /** The region's endpoints whose workgroup is still there; any other is deleted as it is found. */
    private List<EndpointAccess> live(String region) {
        List<EndpointAccess> alive = new ArrayList<>();
        for (EndpointAccess endpoint : endpoints.scan(key -> key.startsWith(region + "::"))) {
            if (isOnSameWorkgroup(endpoint, region)) {
                alive.add(endpoint);
            } else {
                endpoints.delete(storageKey(region, endpoint.getEndpointName()));
            }
        }
        return alive;
    }

    /** A workgroup recreated under the same name has a new id, so it never inherits its predecessor's endpoints. */
    private boolean isOnSameWorkgroup(EndpointAccess endpoint, String region) {
        return serverless.findWorkgroup(endpoint.getWorkgroupName(), region)
                .filter(workgroup -> Objects.equals(workgroup.getWorkgroupId(), endpoint.getOwnerId()))
                .map(workgroup -> {
                    endpoint.setAddress(workgroup.getEndpoint() == null ? null : workgroup.getEndpoint().getAddress());
                    endpoint.setPort(workgroup.getEndpoint() == null ? workgroup.getPort() : workgroup.getEndpoint().getPort());
                    return true;
                })
                .orElse(false);
    }

    private static List<String> networkInterfaceIdsFor(List<String> subnetIds) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < subnetIds.size(); i++) {
            ids.add("eni-" + hex(17));
        }
        return ids;
    }

    private static String hex(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private static String storageKey(String region, String endpointName) {
        return region + "::" + endpointName;
    }
}
