package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Supplies S3 bucket policies to the IAM policy evaluation pipeline.
 */
@ApplicationScoped
public class S3ResourcePolicyProvider implements ResourcePolicyProvider {

    private static final String S3_CONTROL_BUCKET_RESOURCE_PREFIX = "bucket/";

    private final S3Service s3Service;

    @Inject
    public S3ResourcePolicyProvider(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!"s3".equalsIgnoreCase(credentialScope) || resourceArn == null) {
            return List.of();
        }
        String bucketName = extractBucketName(resourceArn);
        if (bucketName == null || bucketName.isEmpty() || "*".equals(bucketName)) {
            return List.of();
        }
        Optional<S3Service.BucketPolicyInfo> policyInfo = s3Service.findBucketPolicyInfo(bucketName);
        return policyInfo.map(info -> List.of(new ResourcePolicy(info.policy(), info.ownerAccountId())))
                .orElseGet(List::of);
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String action, String resourceArn) {
        List<ResourcePolicy> policies = getResourcePolicies(credentialScope, resourceArn);
        if ("s3:CreateBucket".equals(action) && !policies.isEmpty()
                && !s3Service.ownsBucket(extractBucketName(resourceArn))) {
            return List.of();
        }
        return policies;
    }

    @Override
    public String resolveResourceArn(String credentialScope, String resourceArn) {
        if (!"s3".equalsIgnoreCase(credentialScope) || resourceArn == null) {
            return resourceArn;
        }
        if (isS3ControlTaggingArn(resourceArn)) {
            return resourceArn;
        }
        String bucketName = extractBucketName(resourceArn);
        if (bucketName == null || bucketName.isEmpty() || "*".equals(bucketName)) {
            return resourceArn;
        }
        String resource = AwsArnUtils.resourceIfArnFor(resourceArn, "s3").orElseThrow();
        return AwsArnUtils.Arn.global(s3Service.bucketPartition(bucketName), "s3", "", resource).toString();
    }

    /**
     * The bucket of an S3 ARN in any partition. Keyed on a literal {@code arn:aws:} this found
     * no policy for a China or GovCloud bucket, which silently changed the access decision.
     *
     * <p>The regional form an S3 Control tagging route names carries the bucket after its
     * {@code bucket/} prefix, so that is the bucket a policy is looked up from: the data-plane
     * reading would instead find a bucket named {@code bucket} with the real name as its key.
     */
    static String extractBucketName(String resourceArn) {
        String tail = AwsArnUtils.resourceIfArnFor(resourceArn, "s3").orElse(null);
        if (tail == null) {
            return null;
        }
        if (isS3ControlTaggingArn(resourceArn)) {
            return tail.substring(S3_CONTROL_BUCKET_RESOURCE_PREFIX.length());
        }
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash);
    }

    /**
     * Whether {@code resourceArn} is the regional form an S3 Control tagging route names in its
     * path, {@code arn:<partition>:s3:<region>:<account>:bucket/<name>}. No S3 data-plane ARN
     * fills the region field, so this reading of a resource starting with {@code bucket/} can
     * never collide with the data-plane one of a bucket named {@code bucket}. Such an ARN is the
     * caller's own naming of the resource and is kept as written: rewriting it into the global
     * form would deny a policy written against the regional form, which is the form the tagging
     * operations' own request pattern and the AWS CLI examples use.
     */
    static boolean isS3ControlTaggingArn(String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "s3")) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        return !arn.region().isEmpty() && arn.resource().startsWith(S3_CONTROL_BUCKET_RESOURCE_PREFIX);
    }
}
