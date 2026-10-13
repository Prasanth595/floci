package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Optional;

/** Authenticates query credentials before GetCallerIdentity resolves their stored identity. */
@Provider
@Priority(Priorities.AUTHENTICATION + 1)
@ApplicationScoped
public class StsPresignedIdentitySignatureFilter implements ContainerRequestFilter {
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withResolverStyle(ResolverStyle.STRICT)
                    .withZone(ZoneOffset.UTC);
    private static final long MAX_EXPIRY_SECONDS = 604800;
    private static final long MAX_CLOCK_SKEW_SECONDS = 900;

    private final jakarta.inject.Provider<EmulatorConfig> config;
    private final IamService iamService;
    private final IamActionRegistry actions;
    private final CurrentVertxRequest currentRequest;

    @Context
    ResourceInfo resourceInfo;

    @Inject
    public StsPresignedIdentitySignatureFilter(jakarta.inject.Provider<EmulatorConfig> config,
                                               IamService iamService, IamActionRegistry actions,
                                               CurrentVertxRequest currentRequest) {
        this.config = config;
        this.iamService = iamService;
        this.actions = actions;
        this.currentRequest = currentRequest;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!config.get().auth().validateSignatures()
                || resourceInfo.getResourceClass() != AwsQueryController.class
                || !blank(ctx.getHeaderString("Authorization")) || !IamActionRegistry.isQueryRequest(ctx)
                || !"GetCallerIdentity".equals(actions.queryAction(ctx))) {
            return;
        }
        MultivaluedMap<String, String> query = ctx.getUriInfo().getQueryParameters();
        String credential = query.getFirst("X-Amz-Credential");
        if (blank(credential)) {
            return;
        }
        CredentialScope scope = CredentialScope.parse(credential);
        String date = query.getFirst("X-Amz-Date");
        String signedHeaders = query.getFirst("X-Amz-SignedHeaders");
        String signature = query.getFirst("X-Amz-Signature");
        if (!"AWS4-HMAC-SHA256".equals(query.getFirst("X-Amz-Algorithm")) || scope == null
                || !"sts".equals(scope.service()) || blank(date) || blank(signature) || blank(signedHeaders)
                || !SigV4RequestValidator.containsHeader(signedHeaders, "host")) {
            reject(ctx, 400, "IncompleteSignature", "Presigned STS request requires complete SigV4 parameters.");
            return;
        }
        Instant time;
        long expires;
        try {
            time = Instant.from(AMZ_DATE.parse(date));
            expires = Long.parseLong(query.getFirst("X-Amz-Expires"));
            if (expires < 1 || expires > MAX_EXPIRY_SECONDS || !date.startsWith(scope.date())) {
                throw new IllegalArgumentException("Invalid presigned scope or expiry");
            }
        } catch (RuntimeException error) {
            reject(ctx, 400, "IncompleteSignature", "Invalid presigned STS date, credential scope or expiry.");
            return;
        }
        Instant now = Instant.now();
        if (time.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS)) || !now.isBefore(time.plusSeconds(expires))) {
            reject(ctx, 403, "SignatureDoesNotMatch", "Presigned STS signature has expired or is not yet valid.");
            return;
        }
        // Match the handler's header, form, then query token precedence exactly.
        String token = ctx.getHeaderString("X-Amz-Security-Token");
        if (token == null) {
            token = RequestBodyReader.formField(ctx, "X-Amz-Security-Token");
        }
        if (token == null) {
            token = query.getFirst("X-Amz-Security-Token");
        }
        Optional<String> secret = "test".equals(scope.accessKeyId()) ? Optional.of("test")
                : iamService.findSecretKey(scope.accessKeyId(), token);
        if (secret.isEmpty()) {
            reject(ctx, 403, "InvalidClientTokenId", "The security token included in the request is invalid.");
            return;
        }
        URI uri = ctx.getUriInfo().getRequestUri();
        String path = uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        if (currentRequest.getCurrent() != null) {
            path = currentRequest.getCurrent().request().path();
            rawQuery = currentRequest.getCurrent().request().query();
        }
        boolean matches;
        try {
            matches = SigV4Canonicalization.signatureMatches(ctx.getMethod(), path,
                    SigV4Canonicalization.canonicalQueryString(rawQuery, true),
                    SigV4Canonicalization.canonicalHeaders(signedHeaders, name -> "host".equals(name)
                            ? RequestHost.of(ctx.getHeaderString(name), uri) : ctx.getHeaderString(name)), signedHeaders,
                    Digests.sha256Hex(RequestBodyReader.buffer(ctx)), date, scope, secret.get(), signature);
        } catch (Exception error) {
            matches = false;
        }
        if (!matches) {
            reject(ctx, 403, "SignatureDoesNotMatch", "The request signature we calculated does not match "
                    + "the signature you provided.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void reject(ContainerRequestContext ctx, int status, String code, String message) {
        ctx.abortWith(AwsQueryResponse.error(code, message, AwsNamespaces.STS, status));
    }
}
