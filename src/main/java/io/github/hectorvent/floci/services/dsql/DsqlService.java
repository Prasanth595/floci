package io.github.hectorvent.floci.services.dsql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aurora DSQL's control plane: clusters, their tags and resource policies. Each cluster's
 * database is run by {@link DsqlDataPlane}, which reports when it is ready; until then a cluster
 * is {@code CREATING}, as on AWS. Errors carry the messages measured on AWS (eu-west-1,
 * 2026-10-16).
 */
@ApplicationScoped
public class DsqlService implements Resettable {

    private static final Logger LOG = Logger.getLogger(DsqlService.class);

    static final String CREATING = "CREATING";
    static final String ACTIVE = "ACTIVE";
    static final String UPDATING = "UPDATING";
    static final String DELETING = "DELETING";
    static final String FAILED = "FAILED";

    private static final int MAX_RESULTS = 100;

    private final AccountAwareStorageBackend<DsqlCluster> clusters;
    private final RegionResolver regionResolver;
    private final String endpointSuffix;
    private final DsqlDataPlane dataPlane;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public DsqlService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                       DsqlDataPlane dataPlane) {
        this(storageFactory.create("dsql", "dsql-clusters.json", new TypeReference<>() {}), regionResolver,
                config.services().dsql().endpointSuffix(), dataPlane);
    }

    DsqlService(AccountAwareStorageBackend<DsqlCluster> clusters, RegionResolver regionResolver,
                String endpointSuffix, DsqlDataPlane dataPlane) {
        this.clusters = clusters;
        this.regionResolver = regionResolver;
        this.endpointSuffix = endpointSuffix;
        this.dataPlane = dataPlane;
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    public DsqlCluster createCluster(String region, Boolean deletionProtectionEnabled, String kmsEncryptionKey,
                                     Map<String, String> tags, String clientToken, String witnessRegion,
                                     List<String> linkedClusterArns, String policy) {
        String accountId = regionResolver.getAccountId();
        synchronized (lockFor(accountId + "::" + region)) {
            String request = clientRequest(deletionProtectionEnabled, kmsEncryptionKey, tags, witnessRegion,
                    linkedClusterArns, policy);
            if (clientToken != null) {
                Optional<DsqlCluster> earlier = clusters.scan(key -> key.startsWith(region + "::")).stream()
                        .filter(cluster -> clientToken.equals(cluster.getClientToken()))
                        .findFirst();
                if (earlier.isPresent()) {
                    // Measured: the same token with other parameters is a conflict, not the earlier cluster.
                    if (!request.equals(earlier.get().getClientRequest())) {
                        throw new AwsException("ConflictException",
                                "Requests using a client token should supply identical request parameters", 409);
                    }
                    return earlier.get();
                }
            }
            String identifier = DsqlIdentifiers.newId();
            DsqlCluster cluster = new DsqlCluster();
            cluster.setIdentifier(identifier);
            cluster.setArn(regionResolver.buildArn("dsql", region, "cluster/" + identifier));
            cluster.setAccountId(accountId);
            cluster.setRegion(region);
            cluster.setStatus(CREATING);
            cluster.setCreationTime(Instant.now());
            // Measured: a cluster created without the member has deletion protection.
            cluster.setDeletionProtectionEnabled(deletionProtectionEnabled == null || deletionProtectionEnabled);
            cluster.setKmsEncryptionKey(customerKey(kmsEncryptionKey));
            cluster.setTags(tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags));
            cluster.setEndpoint(endpointFor(identifier, region));
            cluster.setClientToken(clientToken);
            cluster.setClientRequest(clientToken == null ? null : request);
            cluster.setWitnessRegion(blankToNull(witnessRegion));
            cluster.setLinkedClusterArns(linkedClusterArns == null || linkedClusterArns.isEmpty()
                    ? null : List.copyOf(linkedClusterArns));
            if (policy != null) {
                cluster.setPolicy(policy);
                cluster.setPolicyVersion("1");
            }
            clusters.put(key(region, identifier), cluster);
            LOG.infov("Created DSQL cluster {0} in {1}", identifier, region);
            dataPlane.provision(cluster, ready -> markReady(accountId, region, identifier, ready));
            return cluster;
        }
    }

    public DsqlCluster getCluster(String region, String identifier) {
        requireIdentifier(identifier);
        return clusters.get(key(region, identifier))
                .orElseThrow(() -> notFound(clusterArn(region, identifier), identifier));
    }

    public Page listClusters(String region, Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS)) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + maxResults
                    + "' at 'maxResults' failed to satisfy constraint: Member must have value "
                    + (maxResults < 1 ? "greater than or equal to 1" : "less than or equal to 100"), 400);
        }
        List<DsqlCluster> all = new ArrayList<>(clusters.scan(key -> key.startsWith(region + "::")));
        all.sort(Comparator.comparing(DsqlCluster::getCreationTime).thenComparing(DsqlCluster::getIdentifier));
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            // The token holds the sort position of the next page's first cluster, so a page carries
            // on from there even when that cluster is gone, as on AWS (measured).
            String[] position = tokenPosition(nextToken);
            start = all.size();
            for (int i = 0; i < all.size(); i++) {
                DsqlCluster cluster = all.get(i);
                int order = cluster.getCreationTime().compareTo(Instant.parse(position[0]));
                if (order > 0 || (order == 0 && cluster.getIdentifier().compareTo(position[1]) >= 0)) {
                    start = i;
                    break;
                }
            }
        }
        int size = maxResults == null ? MAX_RESULTS : maxResults;
        int end = Math.min(all.size(), start + size);
        String next = end < all.size() ? pageToken(all.get(end)) : null;
        return new Page(all.subList(start, end), next);
    }

    public DsqlCluster updateCluster(String region, String identifier, Boolean deletionProtectionEnabled,
                                     String kmsEncryptionKey, String witnessRegion, List<String> linkedClusterArns) {
        DsqlCluster cluster = getCluster(region, identifier);
        synchronized (lockFor(cluster.getArn())) {
            if (deletionProtectionEnabled != null) {
                cluster.setDeletionProtectionEnabled(deletionProtectionEnabled);
            }
            if (kmsEncryptionKey != null) {
                cluster.setKmsEncryptionKey(customerKey(kmsEncryptionKey));
            }
            if (witnessRegion != null) {
                cluster.setWitnessRegion(blankToNull(witnessRegion));
            }
            if (linkedClusterArns != null) {
                cluster.setLinkedClusterArns(linkedClusterArns.isEmpty() ? null : List.copyOf(linkedClusterArns));
            }
            clusters.put(key(region, identifier), cluster);
        }
        return cluster;
    }

    public DsqlCluster deleteCluster(String region, String identifier) {
        DsqlCluster cluster = getCluster(region, identifier);
        String accountId = regionResolver.getAccountId();
        synchronized (lockFor(cluster.getArn())) {
            if (cluster.isDeletionProtectionEnabled()) {
                throw new AwsException("ValidationException", "Can't delete cluster: Deletion protection is enabled."
                        + " To proceed, disable deletion protection and try again.", 400,
                        Map.of("reason", "deletionProtectionEnabled"));
            }
            if (DELETING.equals(cluster.getStatus())) {
                return cluster;
            }
            cluster.setStatus(DELETING);
            clusters.put(key(region, identifier), cluster);
        }
        dataPlane.deprovision(cluster, () -> {
            clusters.deleteForAccount(accountId, key(region, identifier));
            locks.remove(cluster.getArn());
            LOG.infov("Deleted DSQL cluster {0} in {1}", identifier, region);
        });
        return cluster;
    }

    /** The cluster whose endpoint is {@code host}, in any account: what the data plane serves. */
    public Optional<DsqlCluster> clusterForEndpoint(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String wanted = host.toLowerCase(Locale.ROOT);
        return clusters.scanAllAccounts().stream()
                .filter(cluster -> wanted.equals(cluster.getEndpoint()))
                .findFirst();
    }

    /** Every cluster in every account, for the data plane to bring back after a restart. */
    public List<DsqlCluster> allClusters() {
        return clusters.scanAllAccounts();
    }

    // ── VPC endpoint service ──────────────────────────────────────────────────

    public String vpcEndpointServiceName(String region, String identifier) {
        getCluster(region, identifier);
        return "com.amazonaws." + region + ".dsql-" + regionalServiceSuffix(region);
    }

    public String clusterVpcEndpoint(String region, String identifier) {
        return identifier + ".dsql-" + regionalServiceSuffix(region) + "." + region + "." + endpointSuffix;
    }

    // ── Resource policy ───────────────────────────────────────────────────────

    public String putClusterPolicy(String region, String identifier, String policy, String expectedPolicyVersion) {
        DsqlCluster cluster = getCluster(region, identifier);
        synchronized (lockFor(cluster.getArn())) {
            requireExpectedVersion(cluster, expectedPolicyVersion);
            cluster.setPolicy(policy);
            cluster.setPolicyVersion(String.valueOf(currentVersion(cluster) + 1));
            clusters.put(key(region, identifier), cluster);
            return cluster.getPolicyVersion();
        }
    }

    public DsqlCluster getClusterPolicy(String region, String identifier) {
        DsqlCluster cluster = getCluster(region, identifier);
        if (cluster.getPolicy() == null) {
            throw notFound(cluster.getArn(), identifier);
        }
        return cluster;
    }

    public String deleteClusterPolicy(String region, String identifier, String expectedPolicyVersion) {
        DsqlCluster cluster = getCluster(region, identifier);
        synchronized (lockFor(cluster.getArn())) {
            if (cluster.getPolicy() == null) {
                throw notFound(cluster.getArn(), identifier);
            }
            requireExpectedVersion(cluster, expectedPolicyVersion);
            cluster.setPolicy(null);
            cluster.setPolicyVersion(String.valueOf(currentVersion(cluster) + 1));
            clusters.put(key(region, identifier), cluster);
            return cluster.getPolicyVersion();
        }
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String arn) {
        DsqlCluster cluster = clusterForArn(arn, "ListTagsForResource");
        synchronized (lockFor(cluster.getArn())) {
            return new LinkedHashMap<>(cluster.getTags());
        }
    }

    public void tagResource(String arn, Map<String, String> tags) {
        DsqlCluster cluster = clusterForArn(arn, "TagResource");
        synchronized (lockFor(cluster.getArn())) {
            cluster.getTags().putAll(tags == null ? Map.of() : tags);
            clusters.put(key(cluster.getRegion(), cluster.getIdentifier()), cluster);
        }
    }

    public void untagResource(String arn, List<String> tagKeys) {
        DsqlCluster cluster = clusterForArn(arn, "UntagResource");
        synchronized (lockFor(cluster.getArn())) {
            (tagKeys == null ? List.<String>of() : tagKeys).forEach(cluster.getTags()::remove);
            clusters.put(key(cluster.getRegion(), cluster.getIdentifier()), cluster);
        }
    }

    @Override
    public void clear() {
        for (DsqlCluster cluster : clusters.scanAllAccounts()) {
            dataPlane.deprovision(cluster, () -> { });
        }
        clusters.clear();
        locks.clear();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void markReady(String accountId, String region, String identifier, boolean ready) {
        clusters.getForAccount(accountId, key(region, identifier)).ifPresent(cluster -> {
            if (CREATING.equals(cluster.getStatus())) {
                cluster.setStatus(ready ? ACTIVE : FAILED);
                clusters.putForAccount(accountId, key(region, identifier), cluster);
            }
        });
    }

    private DsqlCluster clusterForArn(String arn, String action) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + arn, 400);
        }
        String resource = parsed.resource();
        if (!"dsql".equals(parsed.service()) || !resource.startsWith("cluster/")) {
            throw notFound(arn, resource);
        }
        if (!regionResolver.getAccountId().equals(parsed.accountId())) {
            // Measured: another account's cluster is refused, not looked up in the caller's.
            throw new AwsException("AccessDeniedException", "User is not authorized to perform: dsql:" + action
                    + " on resource: " + arn + " because no resource-based policy allows the dsql:" + action
                    + " action", 403);
        }
        String identifier = resource.substring("cluster/".length());
        return clusters.get(key(parsed.region(), identifier)).orElseThrow(() -> notFound(arn, identifier));
    }

    private String endpointFor(String identifier, String region) {
        return identifier + ".dsql." + region + "." + endpointSuffix;
    }

    private String clusterArn(String region, String identifier) {
        return regionResolver.buildArn("dsql", region, "cluster/" + identifier);
    }

    /**
     * The four characters after {@code dsql-} in a Region's VPC endpoint service name, the same
     * for every cluster of the Region (eu-west-1 is {@code 2syq} on AWS). Floci derives them from
     * the Region's name, so they are stable.
     */
    private static String regionalServiceSuffix(String region) {
        byte[] digest = Digests.sha256(region.getBytes(StandardCharsets.UTF_8));
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder suffix = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            suffix.append(alphabet.charAt(digest[i] & 31));
        }
        return suffix.toString();
    }

    /**
     * A cluster id must match DSQL's pattern, and then name a UUID: both refusals carry the texts
     * measured on AWS.
     */
    static void requireIdentifier(String identifier) {
        if (identifier == null || !DsqlIdentifiers.CLUSTER_ID.matcher(identifier).matches()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value at 'identifier'"
                    + " failed to satisfy constraint: Member must satisfy regular expression pattern: [a-z0-9]{26}", 400);
        }
        if (DsqlIdentifiers.decode(identifier).isEmpty()) {
            throw new AwsException("ValidationException", "Invalid input 'identifier: " + identifier
                    + "'. The cluster identifier format is invalid. Provide a valid cluster identifier and try again.",
                    400);
        }
    }

    private static AwsException notFound(String arn, String identifier) {
        return new AwsException("ResourceNotFoundException", "The resource with ARN " + arn
                + " doesn't exist. Verify the ARN and try again.", 404,
                Map.of("resourceId", identifier, "resourceType", "cluster"));
    }

    private static void requireExpectedVersion(DsqlCluster cluster, String expected) {
        if (expected != null && !expected.equals(cluster.getPolicyVersion())) {
            throw new AwsException("ConflictException", "The expected policy version " + expected
                    + " does not match the current version " + cluster.getPolicyVersion() + ".", 409,
                    Map.of("resourceId", cluster.getIdentifier(), "resourceType", "cluster"));
        }
    }

    private static int currentVersion(DsqlCluster cluster) {
        try {
            return cluster.getPolicyVersion() == null ? 0 : Integer.parseInt(cluster.getPolicyVersion());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    private static String key(String region, String identifier) {
        return region + "::" + identifier;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A customer-managed key, or null for AWS's own: blank, or the reserved AWS_OWNED_KMS_KEY (measured). */
    private static String customerKey(String kmsEncryptionKey) {
        return "AWS_OWNED_KMS_KEY".equals(kmsEncryptionKey) ? null : blankToNull(kmsEncryptionKey);
    }

    /** What a create asked for, which a later create with the same client token must repeat. */
    private static String clientRequest(Boolean deletionProtectionEnabled, String kmsEncryptionKey,
                                        Map<String, String> tags, String witnessRegion,
                                        List<String> linkedClusterArns, String policy) {
        return String.join("\n", String.valueOf(deletionProtectionEnabled), String.valueOf(kmsEncryptionKey),
                String.valueOf(tags == null ? null : new TreeMap<>(tags)), String.valueOf(witnessRegion),
                String.valueOf(linkedClusterArns), String.valueOf(policy));
    }

    private static String pageToken(DsqlCluster cluster) {
        String position = cluster.getCreationTime() + " " + cluster.getIdentifier();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] tokenPosition(String nextToken) {
        try {
            String[] position = new String(Base64.getUrlDecoder().decode(nextToken), StandardCharsets.UTF_8)
                    .split(" ", 2);
            if (position.length == 2) {
                Instant.parse(position[0]);
                return position;
            }
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            // Refused below.
        }
        throw new AwsException("ValidationException", "Invalid nextToken", 400);
    }

    /** One page of ListClusters. */
    public record Page(List<DsqlCluster> clusters, String nextToken) {
    }
}
