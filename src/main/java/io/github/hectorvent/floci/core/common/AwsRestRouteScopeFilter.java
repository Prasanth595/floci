package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.s3.S3Controller;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Stops a known service's REST request from being handled by another service's routes: a REST JSON
 * service's by S3's path-style wildcard routes, and any service's by a route another service owns
 * and is not signed as. Two services can share a public path, such as AppSync's {@code /v2/apis}
 * and API Gateway v2's, so a request for an operation Floci does not implement would otherwise be
 * answered by the other service. This runs after route matching so {@link ResourceInfo} identifies
 * the route, complementing the pre-matching unknown-scope guard in {@link AwsProtocolClaimFilter}.
 */
@Provider
@Priority(Priorities.ENTITY_CODER + 100)
public class AwsRestRouteScopeFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AwsRestRouteScopeFilter.class);

    @Context
    ResourceInfo resourceInfo;

    private final ResolvedServiceCatalog catalog;
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    public AwsRestRouteScopeFilter(ResolvedServiceCatalog catalog,
                                   jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.catalog = catalog;
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        Object claimValue = ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
        Class<?> resourceClass = RoutedResource.resourceClass(resourceInfo);
        if (!(claimValue instanceof ProtocolClaim claim) || claim.protocol() != WireProtocol.REST
                || resourceClass == null) {
            return;
        }
        Optional<String> scope = SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"));
        Optional<ServiceDescriptor> signedFor = scope.flatMap(catalog::byCredentialScope);
        if (signedFor.isEmpty()) {
            return;
        }

        if (resourceClass == S3Controller.class) {
            signedFor.filter(descriptor -> descriptor.supportsProtocol(ServiceProtocol.REST_JSON))
                    .filter(descriptor -> !"s3".equals(descriptor.externalKey()))
                    .ifPresent(descriptor -> reject(ctx, descriptor, "an S3 wildcard route"));
            return;
        }
        // Owners are compared by scope, not by descriptor: several services sign as one scope, as
        // API Gateway v2 signs as apigateway, and the catalog resolves a scope to only one of them.
        // An owner that declares no scope, as the Bedrock control plane leaves bedrock to
        // bedrock-runtime, says nothing about what it is signed as, so it is not judged.
        catalog.byResourceClass(resourceClass)
                .filter(owner -> !owner.credentialScopes().isEmpty())
                .filter(owner -> !catalog.isSignedAs(owner, scope.get()))
                .ifPresent(owner -> reject(ctx, signedFor.get(), owner.externalKey() + "'s route"));
    }

    private void reject(ContainerRequestContext ctx, ServiceDescriptor descriptor, String route) {
        if (!configProvider.get().protocols().rejectUnknownServiceScope()) {
            LOG.debugv("Known service {0} matched {1}; rejection disabled by config",
                    descriptor.externalKey(), route);
            return;
        }
        String method = ctx.getMethod();
        String path = ctx.getUriInfo().getPath();
        LOG.infov("Rejecting {0} request that matched {1}: {2} {3}",
                descriptor.externalKey(), route, method, path);
        ctx.abortWith(AwsProtocolClaimFilter.unknownOperationResponse(404,
                "Unknown operation: " + method + " " + path));
    }
}
