package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three Web Function types in isolation: PascalCase properties reach the service as the camelCase
 * requests the API takes, every physical id is the resource's ARN, an unchanged revision is kept on
 * update while a changed one is replaced, and an endpoint keeps its identity across in-place updates.
 */
class LambdaWebCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String FN_ARN = "arn:aws:lambda:us-east-1:000000000000:web-function/app";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";

    private final LambdaWebService service = mock(LambdaWebService.class);
    private final LambdaWebCfnProvisioner provisioner = new LambdaWebCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, ACCOUNT, "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("Res");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static StackResource updateOf(StackResource prior) {
        StackResource r = resource(prior.getResourceType());
        r.setPhysicalId(prior.getPhysicalId());
        r.setAttributes(new HashMap<>(prior.getAttributes()));
        return r;
    }

    private static WebFunction function() {
        WebFunction fn = new WebFunction();
        fn.functionName = "app";
        fn.functionArn = FN_ARN;
        fn.createdAt = Instant.parse("2026-10-14T20:37:19.533870Z");
        fn.updatedAt = fn.createdAt;
        return fn;
    }

    private static WebFunctionRevision revision(String id) {
        WebFunctionRevision revision = new WebFunctionRevision();
        revision.revisionId = id;
        revision.revisionArn = FN_ARN + "/revision/" + id;
        revision.functionArn = FN_ARN;
        revision.createdAt = Instant.parse("2026-10-14T20:37:19.533870Z");
        return revision;
    }

    private static WebFunctionEndpoint endpoint(String name, String type, List<String> regions) {
        WebFunctionEndpoint endpoint = new WebFunctionEndpoint();
        endpoint.endpointName = name;
        endpoint.endpointArn = FN_ARN + "/endpoint/" + name;
        endpoint.functionArn = FN_ARN;
        endpoint.endpointType = type;
        endpoint.regions = regions;
        endpoint.domainName = "abc.001.lambda-web.us-east-1.localhost:4566";
        endpoint.createdAt = Instant.parse("2026-10-14T20:37:19.533870Z");
        endpoint.updatedAt = endpoint.createdAt;
        return endpoint;
    }

    private ObjectNode revisionProps() {
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app").put("Description", "first");
        ObjectNode build = props.putObject("BuildConfig");
        build.putObject("CodeConfig").putObject("S3Object").put("Bucket", "code").put("Key", "app.zip");
        build.putObject("RuntimeConfig").put("Runtime", "nodejs24.x");
        ObjectNode service = props.putObject("ServiceConfig").put("ExecutionRoleArn", ROLE).put("TimeoutSeconds", 45);
        service.putObject("EnvironmentVariables").put("APP_VERSION", "2");
        return props;
    }

    private ObjectNode endpointProps(String type) {
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app").put("EndpointName", "prod")
                .put("EndpointType", type).put("AuthType", "IamAuth");
        props.putArray("RevisionWeights").addObject().put("RevisionId", "rev-aaaaa").put("Weight", 100);
        if (!"HomeRegion".equals(type)) {
            props.putArray("Regions").add("us-west-2");
        }
        return props;
    }

    @Test
    void functionIsCreatedBareAndIdentifiedByArn() {
        WebFunction fn = function();
        fn.tags.put("env", "prod");
        when(service.createFunctionShell(REGION, ACCOUNT, "app", Map.of("env", "prod"))).thenReturn(fn);
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "prod");
        StackResource r = resource(LambdaWebCfnProvisioner.FUNCTION);

        provisioner.provision(r, props, ctx(null));

        assertEquals(FN_ARN, r.getPhysicalId());
        assertEquals(FN_ARN, r.getAttributes().get("FunctionArn"));
        assertEquals("Active", r.getAttributes().get("State"));
        assertEquals("The function is active.", r.getAttributes().get("StateReason"));
        assertEquals("2026-10-14T20:37:19.533870Z", r.getAttributes().get("CreatedAt"));
        assertEquals("2026-10-14T20:37:19.533870Z", r.getAttributes().get("UpdatedAt"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void functionUpdateWithTheSameNameReconcilesTags() {
        when(service.getFunction(REGION, "app")).thenReturn(function());
        StackResource prior = resource(LambdaWebCfnProvisioner.FUNCTION);
        prior.setPhysicalId(FN_ARN);
        ObjectNode props = mapper.createObjectNode().put("FunctionName", "app");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "web");

        StackResource r = updateOf(prior);
        provisioner.provision(r, props, ctx(FN_ARN));

        verify(service).replaceTags(REGION, "app", Map.of("team", "web"));
        verify(service, never()).createFunctionShell(anyString(), anyString(), anyString(), any());
        assertEquals(FN_ARN, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void revisionPropertiesReachTheServiceInCamelCase() {
        when(service.createRevision(eq(REGION), eq("app"), any())).thenReturn(revision("rev-aaaaa"));
        StackResource r = resource(LambdaWebCfnProvisioner.REVISION);

        provisioner.provision(r, revisionProps(), ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createRevision(eq(REGION), eq("app"), request.capture());
        JsonNode sent = request.getValue();
        assertEquals("code", sent.path("buildConfig").path("codeConfig").path("s3Object").path("bucket").asText());
        assertEquals("nodejs24.x", sent.path("buildConfig").path("runtimeConfig").path("runtime").asText());
        assertEquals(45, sent.path("serviceConfig").path("timeoutSeconds").asInt());
        assertEquals("2", sent.path("serviceConfig").path("environmentVariables").path("APP_VERSION").asText(),
                "environment variable keys are the user's own and are not lowered");
        assertEquals("first", sent.path("description").asText());
        assertFalse(sent.has("functionName"));
        assertEquals(FN_ARN + "/revision/rev-aaaaa", r.getPhysicalId());
        assertEquals("rev-aaaaa", r.getAttributes().get("RevisionId"));
        assertEquals(FN_ARN + "/revision/rev-aaaaa", r.getAttributes().get("RevisionArn"));
        assertEquals(FN_ARN, r.getAttributes().get("FunctionArn"));
        assertEquals("Active", r.getAttributes().get("State"));
        assertEquals("The revision is active.", r.getAttributes().get("StateReason"));
    }

    @Test
    void unchangedRevisionIsKeptOnUpdate() {
        WebFunctionRevision existing = revision("rev-aaaaa");
        when(service.getRevision(REGION, "app", existing.revisionArn)).thenReturn(existing);
        when(service.revisionMatches(eq(existing), any())).thenReturn(true);
        StackResource prior = resource(LambdaWebCfnProvisioner.REVISION);
        prior.setPhysicalId(existing.revisionArn);

        StackResource r = updateOf(prior);
        provisioner.provision(r, revisionProps(), ctx(existing.revisionArn));

        verify(service, never()).createRevision(anyString(), anyString(), any());
        assertEquals(existing.revisionArn, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void changedRevisionIsReplacedAndTheOldOneOwedADelete() {
        WebFunctionRevision existing = revision("rev-aaaaa");
        when(service.getRevision(REGION, "app", existing.revisionArn)).thenReturn(existing);
        when(service.revisionMatches(eq(existing), any())).thenReturn(false);
        when(service.createRevision(eq(REGION), eq("app"), any())).thenReturn(revision("rev-bbbbb"));
        StackResource prior = resource(LambdaWebCfnProvisioner.REVISION);
        prior.setPhysicalId(existing.revisionArn);
        prior.getAttributes().put("RevisionId", "rev-aaaaa");

        StackResource r = updateOf(prior);
        provisioner.provision(r, revisionProps(), ctx(existing.revisionArn));

        assertEquals(FN_ARN + "/revision/rev-bbbbb", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(existing.revisionArn, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void endpointPropertiesReachTheServiceInCamelCase() {
        when(service.createEndpoint(eq(REGION), eq(ACCOUNT), eq("app"), any()))
                .thenReturn(endpoint("prod", "MultiRegion", List.of("us-east-1", "us-west-2")));
        StackResource r = resource(LambdaWebCfnProvisioner.ENDPOINT);

        provisioner.provision(r, endpointProps("MultiRegion"), ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createEndpoint(eq(REGION), eq(ACCOUNT), eq("app"), request.capture());
        JsonNode sent = request.getValue();
        assertEquals("prod", sent.path("endpointName").asText());
        assertEquals("IamAuth", sent.path("authType").asText());
        assertEquals("rev-aaaaa", sent.path("revisionWeights").get(0).path("revisionId").asText());
        assertEquals(100, sent.path("revisionWeights").get(0).path("weight").asInt());
        assertEquals("us-west-2", sent.path("regions").get(0).asText());
        assertEquals(FN_ARN + "/endpoint/prod", r.getPhysicalId());
        assertEquals("abc.001.lambda-web.us-east-1.localhost:4566", r.getAttributes().get("DomainName"));
        assertEquals("The endpoint is active and invokable.", r.getAttributes().get("StateReason"));
        assertFalse(r.getAttributes().containsKey("UpdateStatus"), "a fresh endpoint has no update status, as on AWS");
    }

    @Test
    void endpointWithTheSameIdentityIsUpdatedInPlaceAndDroppedConfigsAreCleared() {
        WebFunctionEndpoint existing = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        when(service.getEndpoint(REGION, "app", existing.endpointArn)).thenReturn(existing);
        WebFunctionEndpoint updated = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        updated.updateStatus = "Successful";
        when(service.updateEndpoint(eq(REGION), eq("app"), eq("prod"), any()))
                .thenReturn(new LambdaWebService.EndpointUpdate(existing, updated));
        StackResource prior = resource(LambdaWebCfnProvisioner.ENDPOINT);
        prior.setPhysicalId(existing.endpointArn);

        StackResource r = updateOf(prior);
        provisioner.provision(r, endpointProps("HomeRegion"), ctx(existing.endpointArn));

        ArgumentCaptor<JsonNode> patch = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updateEndpoint(eq(REGION), eq("app"), eq("prod"), patch.capture());
        assertEquals("IamAuth", patch.getValue().path("authType").asText());
        assertTrue(patch.getValue().path("scalingConfig").isObject() && patch.getValue().path("scalingConfig").isEmpty());
        assertTrue(patch.getValue().path("throttleConfig").isObject() && patch.getValue().path("throttleConfig").isEmpty());
        assertFalse(patch.getValue().has("endpointType"));
        verify(service, never()).createEndpoint(anyString(), anyString(), anyString(), any());
        assertEquals(existing.endpointArn, r.getPhysicalId());
        assertEquals("Successful", r.getAttributes().get("UpdateStatus"));
        assertEquals("The endpoint is updated.", r.getAttributes().get("UpdateStatusReason"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void endpointWithAChangedTypeIsReplaced() {
        WebFunctionEndpoint existing = endpoint("prod", "HomeRegion", List.of("us-east-1"));
        when(service.getEndpoint(REGION, "app", existing.endpointArn)).thenReturn(existing);
        when(service.createEndpoint(eq(REGION), eq(ACCOUNT), eq("app"), any()))
                .thenReturn(endpoint("prod", "PerRegion", List.of("us-east-1", "us-west-2")));
        StackResource prior = resource(LambdaWebCfnProvisioner.ENDPOINT);
        prior.setPhysicalId(existing.endpointArn);

        StackResource r = updateOf(prior);
        provisioner.provision(r, endpointProps("PerRegion"), ctx(existing.endpointArn));

        verify(service, never()).updateEndpoint(anyString(), anyString(), anyString(), any());
        verify(service).createEndpoint(eq(REGION), eq(ACCOUNT), eq("app"), any());
    }

    @Test
    void deletesTolerateAnAbsentResourceButNotARevisionStillInUse() {
        doThrow(new AwsException("ResourceNotFoundException", "The function " + FN_ARN + " does not exist.", 404))
                .when(service).deleteEndpoint(REGION, FN_ARN, FN_ARN + "/endpoint/prod");
        provisioner.delete(LambdaWebCfnProvisioner.ENDPOINT, FN_ARN + "/endpoint/prod", REGION);

        doThrow(new AwsException("ConflictException", "in use", 409))
                .when(service).deleteRevision(REGION, FN_ARN, FN_ARN + "/revision/rev-aaaaa");
        assertThrows(AwsException.class,
                () -> provisioner.delete(LambdaWebCfnProvisioner.REVISION, FN_ARN + "/revision/rev-aaaaa", REGION));

        provisioner.delete(LambdaWebCfnProvisioner.FUNCTION, FN_ARN, REGION);
        verify(service).deleteFunction(REGION, FN_ARN);
    }
}
