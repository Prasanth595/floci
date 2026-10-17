package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4AuthorizationHeader;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.IamService.OwnedSecretKey;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService.WebFunctionTarget;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The checks an {@code IamAuth} endpoint makes before a request reaches the function: a SigV4
 * signature for the service {@code lambda} in the endpoint's region, then
 * {@code lambda:InvokeWebFunctionEndpoint} on the endpoint ARN. The signature is always checked.
 * The policies are evaluated when IAM enforcement is on, as everywhere else in Floci.
 */
@ApplicationScoped
public class LambdaWebIamAuth {

    private static final String ACTION = "lambda:InvokeWebFunctionEndpoint";
    private static final Logger LOG = Logger.getLogger(LambdaWebIamAuth.class);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "lambda";
    private static final String SECURITY_TOKEN = "X-Amz-Security-Token";
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final long MAX_PRESIGNED_EXPIRY_SECONDS = 604800;
    private static final String TEST_ACCESS_KEY = "test";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public LambdaWebIamAuth(EmulatorConfig config, IamService iamService, IamPolicyEvaluator evaluator,
                            Instance<ScpProvider> scpProvider) {
        this.config = config;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.scpProvider = scpProvider;
    }

    /** What the checks read from a request, copied on the event loop so a worker can check it. */
    public record Inbound(String method, String path, String query, MultiMap headers, MultiMap params) {

        static Inbound of(HttpServerRequest request) {
            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            try {
                params.addAll(request.params());
            } catch (IllegalArgumentException e) {
                LOG.debugv("A request to a Lambda Web endpoint has a query that does not decode: {0}", e.getMessage());
            }
            return new Inbound(request.method().name(), request.path(), request.query(),
                    MultiMap.caseInsensitiveMultiMap().addAll(request.headers()), params);
        }
    }

    private record Signed(String credential, String signedHeaders, String signature, String amzDate,
                          Long expiresSeconds, String sessionToken, boolean presigned) {
    }

    /** Whether the request may invoke the target endpoint. Blocks on IAM storage. */
    public boolean allows(Inbound request, WebFunctionTarget target) {
        try {
            String accessKeyId = authenticate(request, target.region());
            return accessKeyId != null && authorize(accessKeyId, target);
        } catch (Exception e) {
            LOG.debugv(e, "Could not verify the signature of a request to {0}", target.endpoint().endpointArn);
            return false;
        }
    }

    /** The access key that signed the request, or null when the signature does not hold. */
    private String authenticate(Inbound request, String region) throws Exception {
        Signed signed = signed(request);
        // Lambda takes this header as the payload hash and never reads the body.
        String payloadHash = request.headers().get("X-Amz-Content-SHA256");
        if (signed == null || isBlank(payloadHash)) {
            return null;
        }
        CredentialScope scope = CredentialScope.parse(signed.credential());
        if (scope == null || !SERVICE.equals(scope.service()) || !region.equals(scope.region())
                || !signed.amzDate().startsWith(scope.date())
                || !SigV4RequestValidator.containsHeader(signed.signedHeaders(), "host")
                || !fresh(Instant.from(AMZ_DATE.parse(signed.amzDate())), signed)) {
            return null;
        }
        String secretKey = secretKey(scope.accessKeyId(), signed.sessionToken());
        if (secretKey == null) {
            LOG.debugv("A request to a Lambda Web endpoint names an unknown access key {0}",
                    SigV4RequestValidator.sanitizeForLog(scope.accessKeyId()));
            return null;
        }
        String canonicalHeaders = SigV4Canonicalization.canonicalHeaders(signed.signedHeaders(),
                name -> String.join(",", request.headers().getAll(name)));
        String canonicalQuery = SigV4Canonicalization.canonicalQueryString(request.query(), signed.presigned());
        boolean matches = SigV4Canonicalization.signatureMatches(request.method(), signedPath(request.path()),
                canonicalQuery, canonicalHeaders, signed.signedHeaders(), payloadHash, signed.amzDate(), scope,
                secretKey, signed.signature());
        return matches ? scope.accessKeyId() : null;
    }

    /**
     * The path as signers sign it, with dot segments resolved and empty segments dropped. Lambda
     * refuses a signature over the path as sent when the two differ.
     */
    static String signedPath(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
            } else if (!segment.isEmpty() && !segment.equals(".")) {
                segments.add(segment);
            }
        }
        String first = path.startsWith("/") ? "/" : "";
        String last = path.endsWith("/") && !segments.isEmpty() ? "/" : "";
        return first + String.join("/", segments) + last;
    }

    /** The header signature, or the presigned query when there is no {@code Authorization} header. */
    private static Signed signed(Inbound request) {
        String authorization = request.headers().get("Authorization");
        if (isBlank(authorization)) {
            MultiMap params = request.params();
            if (!ALGORITHM.equals(params.get("X-Amz-Algorithm"))) {
                return null;
            }
            String expires = params.get("X-Amz-Expires");
            Signed presigned = new Signed(params.get("X-Amz-Credential"), params.get("X-Amz-SignedHeaders"),
                    params.get("X-Amz-Signature"), params.get("X-Amz-Date"),
                    expires == null || !expires.matches("\\d{1,9}") ? null : Long.valueOf(expires),
                    params.get(SECURITY_TOKEN), true);
            return complete(presigned) && presigned.expiresSeconds() != null ? presigned : null;
        }
        SigV4AuthorizationHeader header = SigV4AuthorizationHeader.parse(authorization);
        if (header == null) {
            return null;
        }
        Signed signed = new Signed(header.credential(), header.signedHeaders(), header.signature(),
                request.headers().get("X-Amz-Date"), null, request.headers().get(SECURITY_TOKEN), false);
        return complete(signed) ? signed : null;
    }

    private static boolean complete(Signed signed) {
        return !isBlank(signed.credential()) && !isBlank(signed.signedHeaders()) && !isBlank(signed.signature())
                && !isBlank(signed.amzDate());
    }

    private static boolean fresh(Instant signedAt, Signed signed) {
        Instant now = Instant.now();
        if (signedAt.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
            return false;
        }
        if (signed.presigned()) {
            long expires = signed.expiresSeconds();
            return 1 <= expires && expires <= MAX_PRESIGNED_EXPIRY_SECONDS && !now.isAfter(signedAt.plusSeconds(expires));
        }
        return !signedAt.isBefore(now.minusSeconds(MAX_CLOCK_SKEW_SECONDS));
    }

    /**
     * The secret of a key Floci issued. A temporary key needs its session token, and a long-term
     * key with a session token is refused, as on AWS. The local {@code test} key signs with
     * {@code test}.
     */
    private String secretKey(String accessKeyId, String sessionToken) {
        if (TEST_ACCESS_KEY.equals(accessKeyId)) {
            return TEST_ACCESS_KEY;
        }
        boolean temporary = IamService.isTemporaryAccessKey(accessKeyId);
        if (temporary && isBlank(sessionToken) || !temporary && !isBlank(sessionToken)) {
            return null;
        }
        return iamService.findSecretKeyInAnyAccount(accessKeyId, sessionToken)
                .map(OwnedSecretKey::secretAccessKey)
                .orElse(null);
    }

    private boolean authorize(String accessKeyId, WebFunctionTarget target) {
        if (!config.services().iam().enforcementEnabled() || TEST_ACCESS_KEY.equals(accessKeyId)) {
            return true;
        }
        Optional<String> callerAccount = iamService.resolveAccountId(accessKeyId);
        if (callerAccount.isEmpty()) {
            return false;
        }
        boolean allowed = RequestScopes.callAs(callerAccount.get(), target.region(),
                () -> evaluate(accessKeyId, callerAccount.get(), target));
        if (!allowed) {
            LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}",
                    SigV4RequestValidator.sanitizeForLog(accessKeyId), ACTION, target.endpoint().endpointArn);
        }
        return allowed;
    }

    /**
     * Within one account the caller's policies or the function's resource policy can allow the
     * call, across accounts both have to, and an explicit deny in either refuses it.
     */
    private boolean evaluate(String accessKeyId, String callerAccount, WebFunctionTarget target) {
        CallerContext caller = iamService.resolveCallerContext(accessKeyId, Instant.now());
        if (caller == null) {
            // A known credential with no policies to evaluate, as the enforcement filter treats it.
            return true;
        }
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(callerAccount) : null;
        if (scpLevels != null) {
            caller = caller.withScpLevels(scpLevels);
        }
        Map<String, List<String>> context = new HashMap<>();
        context.put("lambda:Resource/WebFunctionAuthType", List.of(target.endpoint().authType));
        target.function().tags.forEach((key, value) -> context.put("aws:ResourceTag/" + key, List.of(value)));
        Optional<CallerArns> callerArns = iamService.resolveCallerArns(accessKeyId);
        if (callerArns.isPresent()) {
            caller = caller.withPrincipalArn(callerArns.get().callerArn());
            callerArns.get().putPrincipalKeys(context);
        }
        String endpointArn = target.endpoint().endpointArn;
        Map<String, List<String>> effective = IamConditionContextResolver.withGlobalContext(
                context, endpointArn, target.region(), callerAccount, target.accountId());
        String policy = target.function().policy;
        ResourcePolicyDecision grant = evaluator.evaluateResourcePolicyFor(
                isBlank(policy) ? null : List.of(policy), RequestPrincipal.caller(callerArns.orElse(null)),
                ACTION, endpointArn, effective);
        ResourceAccountRelationship relationship = callerAccount.equals(target.accountId())
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
        return evaluator.evaluateResolvedResourcePolicy(caller, grant, relationship, ACTION, endpointArn, effective)
                != Decision.DENY;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
