package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.IamService.HeldPolicies;
import io.github.hectorvent.floci.services.iam.IamService.HeldPolicy;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code lambda:PassNetworkConnector} permission a revision needs on the connector version it
 * names. Lambda checks it as the caller on the ARN as given, before it looks the connector up. A
 * connector has no resource policy, so the caller's policies decide alone. The policies are
 * evaluated when IAM enforcement is on, as everywhere else in Floci.
 */
@ApplicationScoped
public class LambdaWebConnectorPass {

    private static final Logger LOG = Logger.getLogger(LambdaWebConnectorPass.class);
    private static final String ACTION = "lambda:PassNetworkConnector";
    private static final String TEST_ACCESS_KEY = "test";

    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public LambdaWebConnectorPass(EmulatorConfig config, IamService iamService, IamPolicyEvaluator evaluator,
                                  Instance<ScpProvider> scpProvider) {
        this.config = config;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.scpProvider = scpProvider;
    }

    void require(String region, String accountId, String accessKeyId, String connectorArn) {
        if (!config.services().iam().enforcementEnabled() || TEST_ACCESS_KEY.equals(accessKeyId)) {
            return;
        }
        CallerContext caller = iamService.resolveCallerContext(accessKeyId);
        if (caller == null) {
            // A known credential with no policies to evaluate, as the enforcement filter treats it.
            return;
        }
        Optional<CallerArns> callerArns = iamService.resolveCallerArns(accessKeyId);
        Map<String, List<String>> context = new HashMap<>();
        callerArns.ifPresent(arns -> arns.putPrincipalKeys(context));
        Map<String, List<String>> effective = IamConditionContextResolver.withGlobalContext(
                context, connectorArn, region, accountId, accountId);
        String reason = refusal(caller, accountId, accessKeyId, connectorArn, effective);
        if (reason == null) {
            return;
        }
        LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", accessKeyId, ACTION, connectorArn);
        String user = callerArns.map(CallerArns::callerArn).orElseGet(() -> AwsArnUtils.Arn.global(
                AwsRegions.partitionFor(region), "iam", accountId, "user/" + accessKeyId).toString());
        throw new LambdaWebAccessDeniedException("User: " + user + " is not authorized to perform: " + ACTION
                + " on resource: " + connectorArn + " " + reason);
    }

    /**
     * Why the caller may not pass the connector, or null when it may. AWS names the first policy type
     * that refuses, by its explicit deny or by the allow it lacks: the identity policies, then the
     * permissions boundary, then the session policy. Service control policies come before them, as
     * in the policy evaluation logic.
     */
    private String refusal(CallerContext caller, String accountId, String accessKeyId, String resource,
                           Map<String, List<String>> context) {
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(accountId) : null;
        for (List<String> level : scpLevels == null ? List.<List<String>>of() : scpLevels) {
            SimulationDecision decision = level.isEmpty()
                    ? SimulationDecision.ALLOWED : simulate(level, resource, context);
            if (decision != SimulationDecision.ALLOWED) {
                return reason(decision, "a service control policy", "service control policy", null);
            }
        }
        SimulationDecision identity = simulate(caller.identityPolicies(), resource, context);
        if (identity != SimulationDecision.ALLOWED) {
            String denying = identity == SimulationDecision.EXPLICIT_DENY
                    ? iamService.heldPolicies(accessKeyId)
                            .flatMap(held -> denyingArn(held.identity(), resource, context)).orElse(null)
                    : null;
            return reason(identity, "an identity-based policy", "identity-based policy", denying);
        }
        if (caller.boundaryPolicyDocument() != null) {
            SimulationDecision boundary = simulate(List.of(caller.boundaryPolicyDocument()), resource, context);
            if (boundary != SimulationDecision.ALLOWED) {
                String boundaryArn = iamService.heldPolicies(accessKeyId)
                        .map(HeldPolicies::permissionsBoundaryArn).orElse(null);
                return reason(boundary, "a permissions boundary", "permissions boundary", boundaryArn);
            }
        }
        if (caller.sessionPolicyDocument() != null) {
            SimulationDecision session = simulate(List.of(caller.sessionPolicyDocument()), resource, context);
            if (session != SimulationDecision.ALLOWED) {
                return reason(session, "a session policy", "session policy", null);
            }
        }
        return null;
    }

    /** AWS names one of several denying policies at random. This names the first, which is none when it is inline. */
    private Optional<String> denyingArn(List<HeldPolicy> policies, String resource, Map<String, List<String>> context) {
        return policies.stream()
                .filter(policy -> simulate(List.of(policy.document()), resource, context)
                        == SimulationDecision.EXPLICIT_DENY)
                .findFirst()
                .map(HeldPolicy::arn);
    }

    private SimulationDecision simulate(List<String> documents, String resource, Map<String, List<String>> context) {
        return evaluator.simulatePrincipalPolicy(CallerContext.of(documents), ACTION, resource, context);
    }

    private static String reason(SimulationDecision decision, String deniedIn, String kind, String policyArn) {
        if (decision == SimulationDecision.EXPLICIT_DENY) {
            return "with an explicit deny in " + deniedIn + (policyArn == null ? "" : ": " + policyArn);
        }
        return "because no " + kind + " allows the " + ACTION + " action";
    }
}
