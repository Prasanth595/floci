package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Who is calling, and whether an IAM principal a cluster policy names exists: what DSQL checks a
 * policy against. A credential that names no IAM identity is its account's root, as for STS.
 */
@ApplicationScoped
public class DsqlPrincipals {

    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final Instance<RequestContext> requestContext;

    @Inject
    public DsqlPrincipals(IamService iamService, RegionResolver regionResolver,
                          Instance<RequestContext> requestContext) {
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
    }

    public CallerArns caller() {
        String accountId = regionResolver.getAccountId();
        return iamService.resolveCallerArns(accessKeyId())
                .orElseGet(() -> CallerArns.accountRoot(regionResolver.buildGlobalArn("iam", accountId, "root"),
                        accountId));
    }

    /**
     * Whether the IAM principal {@code arn} names exists. Floci takes every account to exist, so an
     * account's root always does; a role or a user is looked up in its account by its name.
     */
    public boolean exists(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        if (resource.startsWith("role/")) {
            return iamService.findRole(parsed.accountId(), name).isPresent();
        }
        if (resource.startsWith("user/")) {
            return iamService.findUser(parsed.accountId(), name).isPresent();
        }
        return "root".equals(resource);
    }

    private String accessKeyId() {
        try {
            return requestContext.isResolvable() ? requestContext.get().getAccessKeyId() : null;
        } catch (ContextNotActiveException e) {
            // A call outside a request, such as CloudFormation's, is the account's own.
            return null;
        }
    }
}
