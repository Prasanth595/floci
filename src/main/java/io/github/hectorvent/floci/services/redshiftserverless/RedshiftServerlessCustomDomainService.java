package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.CustomDomainAssociation;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redshift Serverless custom domain associations. An association is recorded and echoed: Floci
 * serves no traffic on the domain and does not read the ACM certificate.
 *
 * <p>An association belongs to a workgroup but lives in its own store, so deleting the workgroup
 * needs no hook here. Each read checks that the workgroup is still there and drops the association
 * otherwise.
 */
@ApplicationScoped
public class RedshiftServerlessCustomDomainService implements Resettable {

    private static final int PAGE_SIZE = 100;

    private final AccountAwareStorageBackend<CustomDomainAssociation> associations;
    private final RedshiftServerlessService serverless;

    @Inject
    public RedshiftServerlessCustomDomainService(StorageFactory storageFactory,
                                                 RedshiftServerlessService serverless) {
        this.associations = storageFactory.create("redshiftserverless",
                "redshiftserverless-customdomains.json",
                new TypeReference<Map<String, CustomDomainAssociation>>() {});
        this.serverless = serverless;
    }

    public synchronized CustomDomainAssociation createCustomDomainAssociation(
            String workgroupName, String customDomainName, String certificateArn, String region) {
        if (workgroupName == null || workgroupName.isBlank()) {
            throw ServerlessSupport.validation("workgroupName must not be blank.");
        }
        if (customDomainName == null || customDomainName.isBlank()) {
            throw ServerlessSupport.validation("customDomainName must not be blank.");
        }
        if (certificateArn == null || certificateArn.isBlank()) {
            throw ServerlessSupport.validation("customDomainCertificateArn must not be blank.");
        }
        Workgroup workgroup = serverless.findWorkgroup(workgroupName, region)
                .orElseThrow(() -> ServerlessSupport.notFound("The workgroup " + workgroupName + " was not found."));
        if (find(workgroupName, customDomainName, region) != null) {
            throw ServerlessSupport.conflict("The custom domain " + customDomainName
                    + " is already associated with the workgroup " + workgroupName + ".");
        }
        CustomDomainAssociation association = new CustomDomainAssociation();
        association.setWorkgroupName(workgroupName);
        association.setOwnerId(workgroup.getWorkgroupId());
        association.setCustomDomainName(customDomainName);
        association.setCustomDomainCertificateArn(certificateArn);
        associations.put(storageKey(region, workgroupName, customDomainName), association);
        return new CustomDomainAssociation(association);
    }

    public synchronized CustomDomainAssociation getCustomDomainAssociation(
            String workgroupName, String customDomainName, String region) {
        return new CustomDomainAssociation(require(workgroupName, customDomainName, region));
    }

    public synchronized PaginatedResult<CustomDomainAssociation> listCustomDomainAssociations(
            String customDomainName, String certificateArn, String region, Integer maxResults, String nextToken) {
        List<CustomDomainAssociation> matching = live(region).stream()
                .filter(association -> customDomainName == null
                        || customDomainName.equals(association.getCustomDomainName()))
                .filter(association -> certificateArn == null
                        || certificateArn.equals(association.getCustomDomainCertificateArn()))
                .sorted(Comparator.comparing(CustomDomainAssociation::getWorkgroupName)
                        .thenComparing(CustomDomainAssociation::getCustomDomainName))
                .map(CustomDomainAssociation::new)
                .toList();
        return Pagination.paginateNamespaced(matching,
                association -> association.getWorkgroupName() + "|" + association.getCustomDomainName(),
                maxResults, nextToken, PAGE_SIZE, PAGE_SIZE, "custom-domain", "ValidationException");
    }

    /** Points the association at another certificate, the only member AWS lets an update change. */
    public synchronized CustomDomainAssociation updateCustomDomainAssociation(
            String workgroupName, String customDomainName, String certificateArn, String region) {
        if (certificateArn == null || certificateArn.isBlank()) {
            throw ServerlessSupport.validation("customDomainCertificateArn must not be blank.");
        }
        CustomDomainAssociation updated = new CustomDomainAssociation(require(workgroupName, customDomainName, region));
        updated.setCustomDomainCertificateArn(certificateArn);
        associations.put(storageKey(region, workgroupName, customDomainName), updated);
        return new CustomDomainAssociation(updated);
    }

    public synchronized void deleteCustomDomainAssociation(String workgroupName, String customDomainName,
                                                           String region) {
        require(workgroupName, customDomainName, region);
        associations.delete(storageKey(region, workgroupName, customDomainName));
    }

    @Override
    public void clear() {
        associations.clear();
    }

    private CustomDomainAssociation require(String workgroupName, String customDomainName, String region) {
        CustomDomainAssociation association = find(workgroupName, customDomainName, region);
        if (association == null) {
            throw ServerlessSupport.notFound("The custom domain " + customDomainName
                    + " is not associated with the workgroup " + workgroupName + ".");
        }
        return association;
    }

    /** The association, or null when there is none or its workgroup is gone, in which case it is dropped. */
    private CustomDomainAssociation find(String workgroupName, String customDomainName, String region) {
        String key = storageKey(region, workgroupName, customDomainName);
        CustomDomainAssociation association = associations.get(key).orElse(null);
        if (association != null && !isOnSameWorkgroup(association, region)) {
            associations.delete(key);
            return null;
        }
        return association;
    }

    /** The region's associations whose workgroup is still there; any other is deleted as it is found. */
    private List<CustomDomainAssociation> live(String region) {
        return ServerlessSupport.live(associations, region,
                association -> isOnSameWorkgroup(association, region),
                association -> storageKey(region, association.getWorkgroupName(), association.getCustomDomainName()));
    }

    /** A workgroup recreated under the same name has a new id, so it never inherits its predecessor's domains. */
    private boolean isOnSameWorkgroup(CustomDomainAssociation association, String region) {
        return serverless.findWorkgroup(association.getWorkgroupName(), region)
                .map(workgroup -> Objects.equals(workgroup.getWorkgroupId(), association.getOwnerId()))
                .orElse(false);
    }

    private static String storageKey(String region, String workgroupName, String customDomainName) {
        return region + "::" + workgroupName + "::" + customDomainName;
    }
}
