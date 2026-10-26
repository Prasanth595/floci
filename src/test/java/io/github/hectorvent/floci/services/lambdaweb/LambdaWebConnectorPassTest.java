package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.IamService.HeldPolicies;
import io.github.hectorvent.floci.services.iam.IamService.HeldPolicy;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaWebConnectorPassTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private static final String KEY = "ASIASESSIONKEY000001";
    private static final String CALLER = "arn:aws:sts::" + ACCOUNT + ":assumed-role/dev/probe";
    private static final String CONNECTOR = "arn:aws:lambda:us-east-1:" + ACCOUNT
            + ":network-connector:nc-00000000-0000-0000-0000-000000000000";
    private static final String MANAGED = "arn:aws:iam::" + ACCOUNT + ":policy/deny";
    private static final String BOUNDARY = "arn:aws:iam::" + ACCOUNT + ":policy/boundary";
    private static final String NO_IDENTITY_ALLOW =
            "because no identity-based policy allows the lambda:PassNetworkConnector action";

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final IamService iamService = mock(IamService.class);
    @SuppressWarnings("unchecked")
    private final Instance<ScpProvider> scpProvider = mock(Instance.class);
    private final LambdaWebConnectorPass pass = new LambdaWebConnectorPass(config, iamService,
            new IamPolicyEvaluator(new ObjectMapper()), scpProvider);

    @BeforeEach
    void setUp() {
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        when(iamService.resolveCallerArns(KEY)).thenReturn(Optional.of(new CallerArns(CALLER,
                "arn:aws:iam::" + ACCOUNT + ":role/dev")));
    }

    @Test
    void theVersionArnAsGivenHasToBeAllowed() {
        holds(List.of(inline(policy(allow(CONNECTOR + ":1")))), null, null);
        passes(CONNECTOR + ":1");
        assertEquals("User: " + CALLER + " is not authorized to perform: lambda:PassNetworkConnector on resource: "
                + CONNECTOR + ":2 " + NO_IDENTITY_ALLOW, refusal(CONNECTOR + ":2"));

        holds(List.of(inline(policy(allow(CONNECTOR)))), null, null);
        assertEquals(NO_IDENTITY_ALLOW, reason(CONNECTOR + ":1"));
        holds(List.of(inline(policy(allow(CONNECTOR + ":*")))), null, null);
        passes(CONNECTOR + ":1");
        holds(List.of(inline(policy(allow("arn:aws:lambda:us-east-1:" + ACCOUNT + ":network-connector:*")))),
                null, null);
        passes(CONNECTOR + ":1");
        passes("arn:aws:lambda:us-east-1:" + ACCOUNT + ":network-connector:my-conn:99999999999999999999");
    }

    @Test
    void anExplicitDenyNamesTheManagedPolicyThatHoldsIt() {
        holds(List.of(inline(policy(allow("*"))), managed(policy(deny("*")))), null, null);
        assertEquals("with an explicit deny in an identity-based policy: " + MANAGED, reason(CONNECTOR + ":1"));

        holds(List.of(inline(policy(allow("*"), deny(CONNECTOR + ":1")))), null, null);
        assertEquals("with an explicit deny in an identity-based policy", reason(CONNECTOR + ":1"));
        passes(CONNECTOR + ":2");

        holds(List.of(inline(policy(allow("*"), deny("*"))), managed(policy(deny("*")))), null, null);
        assertEquals("with an explicit deny in an identity-based policy", reason(CONNECTOR + ":1"));
    }

    @Test
    void theIdentityPoliciesThenTheBoundaryThenTheSessionPolicyNameTheReason() {
        String allowAll = policy(allow("*"));
        String denyAll = policy(allow("*"), deny("*"));
        String allowNothing = policy(allowCreate());

        holds(List.of(inline(allowNothing)), denyAll, denyAll);
        assertEquals(NO_IDENTITY_ALLOW, reason(CONNECTOR + ":1"));
        holds(List.of(inline(allowAll)), allowNothing, denyAll);
        assertEquals("because no permissions boundary allows the lambda:PassNetworkConnector action",
                reason(CONNECTOR + ":1"));
        holds(List.of(inline(allowAll)), denyAll, allowNothing);
        assertEquals("with an explicit deny in a permissions boundary: " + BOUNDARY, reason(CONNECTOR + ":1"));
        holds(List.of(inline(allowAll)), allowAll, allowNothing);
        assertEquals("because no session policy allows the lambda:PassNetworkConnector action",
                reason(CONNECTOR + ":1"));
        holds(List.of(inline(allowAll)), null, denyAll);
        assertEquals("with an explicit deny in a session policy", reason(CONNECTOR + ":1"));
        holds(List.of(inline(allowAll)), allowAll, allowAll);
        passes(CONNECTOR + ":1");
    }

    @Test
    void serviceControlPoliciesComeBeforeTheIdentityPolicies() {
        ScpProvider scps = mock(ScpProvider.class);
        when(scpProvider.isResolvable()).thenReturn(true);
        when(scpProvider.get()).thenReturn(scps);
        when(scps.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(), List.of(policy(allow("*"), deny("*")))));
        holds(List.of(inline(policy(allowCreate()))), null, null);
        assertEquals("with an explicit deny in a service control policy", reason(CONNECTOR + ":1"));

        when(scps.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(policy(allowCreate()))));
        assertEquals("because no service control policy allows the lambda:PassNetworkConnector action",
                reason(CONNECTOR + ":1"));
    }

    @Test
    void nothingIsCheckedWithoutEnforcementOrPoliciesToEvaluate() {
        holds(List.of(), null, null);
        when(iamService.resolveCallerContext("test")).thenReturn(CallerContext.of(List.of()));
        assertEquals(NO_IDENTITY_ALLOW, reason(CONNECTOR + ":1"));
        assertDoesNotThrow(() -> pass.require(REGION, ACCOUNT, "test", CONNECTOR + ":1"));
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        passes(CONNECTOR + ":1");

        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        when(iamService.resolveCallerContext(KEY)).thenReturn(null);
        passes(CONNECTOR + ":1");
    }

    private void holds(List<HeldPolicy> identity, String boundary, String session) {
        when(iamService.resolveCallerContext(KEY)).thenReturn(new CallerContext(
                identity.stream().map(HeldPolicy::document).toList(), session, boundary));
        when(iamService.heldPolicies(KEY)).thenReturn(Optional.of(
                new HeldPolicies(identity, boundary == null ? null : BOUNDARY)));
    }

    private void passes(String connectorArn) {
        assertDoesNotThrow(() -> pass.require(REGION, ACCOUNT, KEY, connectorArn));
    }

    private String refusal(String connectorArn) {
        LambdaWebAccessDeniedException denied = assertThrows(LambdaWebAccessDeniedException.class,
                () -> pass.require(REGION, ACCOUNT, KEY, connectorArn));
        assertEquals(403, denied.getHttpStatus());
        return denied.getMessage();
    }

    private String reason(String connectorArn) {
        String prefix = "User: " + CALLER + " is not authorized to perform: lambda:PassNetworkConnector on resource: "
                + connectorArn + " ";
        String message = refusal(connectorArn);
        assertEquals(prefix, message.substring(0, prefix.length()));
        return message.substring(prefix.length());
    }

    private static HeldPolicy inline(String document) {
        return new HeldPolicy(null, document);
    }

    private static HeldPolicy managed(String document) {
        return new HeldPolicy(MANAGED, document);
    }

    private static String policy(String... statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    private static String allow(String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"lambda:PassNetworkConnector\",\"Resource\":\"" + resource + "\"}";
    }

    private static String deny(String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"lambda:PassNetworkConnector\",\"Resource\":\"" + resource + "\"}";
    }

    private static String allowCreate() {
        return "{\"Effect\":\"Allow\",\"Action\":\"lambda:CreateWebFunctionRevision\",\"Resource\":\"*\"}";
    }
}
