package io.github.hectorvent.floci.core.common;

import java.util.UUID;

/**
 * The CloudFront distribution AWS puts in front of a custom domain, an edge-optimized API Gateway
 * domain or an AppSync custom domain: a distribution domain name on the CloudFront suffix of the
 * region's partition (or the configured one), and the CloudFront hosted zone of the region's partition, which a Route 53 alias record
 * targets. The zone is null in a partition without CloudFront.
 *
 * @param domainName   the distribution domain name, {@code d<13 hex>.<suffix>}
 * @param hostedZoneId the partition's CloudFront hosted zone, or null where there is none
 */
public record CloudFrontEdgeDomain(String domainName, String hostedZoneId) {

    /**
     * @param configuredSuffix {@code floci.services.cloudfront.domain-suffix}, or null when unset
     */
    public static CloudFrontEdgeDomain create(String region, String configuredSuffix) {
        String partition = AwsPartitions.forRegionOrCommercial(region).id();
        String distributionId = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
        String hostedZoneId = AwsRegionFacts.cloudFrontHostedZoneId(partition).orElse(null);
        return new CloudFrontEdgeDomain(distributionId + "." + domainSuffix(configuredSuffix, partition), hostedZoneId);
    }

    /**
     * The suffix of a generated distribution domain name. An explicitly configured suffix wins in
     * every partition; otherwise it is the partition's own ({@code cloudfront.cn} in China), and
     * the commercial one in a partition that has no CloudFront and so no suffix to publish.
     *
     * @param configuredSuffix {@code floci.services.cloudfront.domain-suffix}, or null when unset
     */
    public static String domainSuffix(String configuredSuffix, String partition) {
        if (configuredSuffix != null && !configuredSuffix.isBlank()) {
            return configuredSuffix;
        }
        return AwsRegionFacts.cloudFrontDomainSuffix(partition)
                .or(() -> AwsRegionFacts.cloudFrontDomainSuffix(AwsRegions.DEFAULT_PARTITION))
                .orElseThrow();
    }
}
