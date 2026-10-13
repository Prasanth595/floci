package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Rewrites GuardDuty Malware Protection plan requests to an internal prefix before JAX-RS route
 * matching. Registered directly, {@code /malware-protection-plan} would outrank S3's
 * {@code /{bucket}} routes, so a path-style request for a bucket of that name would reach GuardDuty.
 * Only a request signed for GuardDuty is rewritten; anything else falls through to S3.
 */
@Provider
@PreMatching
public class GuardDutyRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_guardduty";
    static final String PLAN_PATH = "/malware-protection-plan";
    private static final String GUARDDUTY = "guardduty";

    private final ResolvedServiceCatalog catalog;

    @Inject
    public GuardDutyRouteFilter(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (!normalizedPath.equals(PLAN_PATH)
                && !normalizedPath.startsWith(PLAN_PATH + "/")) {
            return;
        }

        boolean targetsGuardDuty = SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                .flatMap(catalog::byCredentialScope)
                .map(ServiceDescriptor::externalKey)
                .filter(GUARDDUTY::equals)
                .isPresent();
        if (!targetsGuardDuty) {
            return;
        }

        ctx.setRequestUri(ctx.getUriInfo().getRequestUriBuilder()
                .replacePath(INTERNAL_PREFIX + normalizedPath)
                .build());
    }
}
