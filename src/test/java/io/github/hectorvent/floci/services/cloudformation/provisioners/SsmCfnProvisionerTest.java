package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SSM parameter CFN provisioner in isolation, for the {@code Tags} property: the parameter is
 * written without tags, then the template's tags are added, and an update removes only the keys the
 * previous template had, keeping tags added outside the template.
 */
class SsmCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String NAME = "/app/param";
    private static final String TEMPLATE_TAG_KEYS_ATTR = "FlociSsmTemplateTagKeys";

    private final SsmService ssm = mock(SsmService.class);
    private final SsmCfnProvisioner provisioner = new SsmCfnProvisioner(ssm);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Scalar properties and a flat tag map only, so a passthrough engine keeps this isolated.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private ProvisionContext updateCtx() {
        return updateCtx(NAME);
    }

    private ProvisionContext updateCtx(String priorName) {
        ProvisionContext create = ctx();
        Parameter prior = new Parameter(priorName, "old", "String");
        when(ssm.getParameter(priorName, REGION)).thenReturn(prior);
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorName);
    }

    private static StackResource parameter(String physicalId, String templateTagKeys) {
        StackResource r = new StackResource();
        r.setLogicalId("MyParameter");
        r.setResourceType("AWS::SSM::Parameter");
        r.setAttributes(new HashMap<>());
        if (physicalId != null) {
            r.setPhysicalId(physicalId);
        }
        if (templateTagKeys != null) {
            r.getAttributes().put(TEMPLATE_TAG_KEYS_ATTR, templateTagKeys);
        }
        return r;
    }

    private ObjectNode props(String... keyValues) {
        ObjectNode props = mapper.createObjectNode()
                .put("Name", NAME)
                .put("Type", "String")
                .put("Value", "v");
        if (keyValues.length > 0) {
            ObjectNode tags = props.putObject("Tags");
            for (int i = 0; i < keyValues.length; i += 2) {
                tags.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return props;
    }

    @Test
    void parameterTagsFromTheTemplateAreAddedAfterThePut() {
        StackResource r = parameter(null, null);

        provisioner.provision(r, props("team", "a", "env", "dev"), ctx());

        verify(ssm).putParameter(NAME, "v", "String", null, false, REGION);
        verify(ssm).addTagsToResource(NAME, Map.of("team", "a", "env", "dev"), REGION);
        verify(ssm, never()).removeTagsFromResource(anyString(), any(), anyString());
        assertEquals("env,team", r.getAttributes().get(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void parameterWithoutTagsIsNotTagged() {
        StackResource r = parameter(null, null);

        provisioner.provision(r, props(), ctx());

        verify(ssm).putParameter(NAME, "v", "String", null, false, REGION);
        verify(ssm, never()).addTagsToResource(anyString(), anyMap(), anyString());
        verify(ssm, never()).removeTagsFromResource(anyString(), any(), anyString());
        assertFalse(r.getAttributes().containsKey(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void updateRemovesOnlyTheKeyTheTemplateDropped() {
        StackResource r = parameter(NAME, "env,team");

        provisioner.provision(r, props("team", "b"), updateCtx());

        verify(ssm).removeTagsFromResource(NAME, List.of("env"), REGION);
        verify(ssm).addTagsToResource(NAME, Map.of("team", "b"), REGION);
        assertEquals("team", r.getAttributes().get(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void updateWithTheTagsPropertyGoneRemovesOnlyTheTemplateKeys() {
        StackResource r = parameter(NAME, "env,team");

        provisioner.provision(r, props(), updateCtx());

        verify(ssm).removeTagsFromResource(eq(NAME),
                argThat(keys -> keys.size() == 2 && keys.containsAll(List.of("team", "env"))), eq(REGION));
        verify(ssm, never()).addTagsToResource(anyString(), anyMap(), anyString());
        assertFalse(r.getAttributes().containsKey(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void updateWithoutRecordedTemplateKeysRemovesNothing() {
        provisioner.provision(parameter(NAME, null), props("team", "b"), updateCtx());

        verify(ssm, never()).removeTagsFromResource(anyString(), any(), anyString());
        verify(ssm).addTagsToResource(NAME, Map.of("team", "b"), REGION);
    }

    @Test
    void failedReplacementTagsKeepTheCreatedParameterAddressableForRollbackAndDelete() {
        StackResource resource = parameter(NAME, "team");
        ProvisionContext context = updateCtx();
        String replacement = NAME + "-replacement";
        doThrow(new AwsException("AccessDeniedException", "denied", 403))
                .when(ssm).addTagsToResource(replacement, Map.of("team", "new"), REGION);
        ObjectNode desired = props("team", "new").put("Name", replacement);
        assertThrows(AwsException.class, () -> provisioner.provision(resource, desired, context));
        assertEquals(replacement, resource.getPhysicalId());
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        doThrow(new AwsException("AccessDeniedException", "denied", 403))
                .when(ssm).deleteParameter(replacement, REGION);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals(replacement, resource.getPhysicalId());
        assertTrue(provisioner.completeDeleteCleanup(resource).complete());
        doNothing().when(ssm).deleteParameter(replacement, REGION);
        provisioner.delete(resource, REGION);
        verify(ssm).deleteParameter(NAME, REGION);
    }

    /** A rename never writes the prior parameter, so one deleted outside the stack does not fail it. */
    @Test
    void aRenameDoesNotNeedThePriorParameterToStillExist() {
        when(ssm.getParameter(NAME, REGION)).thenThrow(new AwsException("ParameterNotFound", "gone", 400));
        StackResource r = parameter(NAME, null);
        ProvisionContext create = ctx();

        provisioner.provision(r, props().put("Name", NAME + "-new"), new ProvisionContext(create.engine(),
                create.region(), create.accountId(), create.stackName(), NAME));

        assertEquals(NAME + "-new", r.getPhysicalId());
        verify(ssm).putParameter(NAME + "-new", "v", "String", null, false, REGION);
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(NAME, provisioner.updateCleanupPhysicalId(r));
    }

    /** The rollback snapshot carries the prior value, so it lives under a key Fn::GetAtt cannot name. */
    @Test
    void theRollbackSnapshotIsPrivateState() {
        StackResource r = parameter(NAME, null);

        provisioner.provision(r, props(), updateCtx());

        assertTrue(provisioner.retainsFailedUpdateState(r));
        assertEquals(Set.of("Name", "Type", "Value", "Arn"), r.getAttributes().keySet().stream()
                .filter(key -> !key.startsWith("__Floci")).collect(Collectors.toSet()));
    }

    @Test
    void abandonedDisplacedParameterCleanupDoesNotReachTheNextUpdate() {
        StackResource resource = parameter(NAME, null);
        String renamed = NAME + "-new";
        provisioner.provision(resource, props().put("Name", renamed), updateCtx());
        doThrow(new AwsException("AccessDeniedException", "denied", 403)).when(ssm).deleteParameter(NAME, REGION);
        UpdateCleanupResult failed = provisioner.completeUpdate(resource);
        assertFalse(failed.complete());
        assertEquals(NAME, failed.previousPhysicalId());

        provisioner.clearUpdate(resource);

        assertFalse(provisioner.hasReplacementUpdate(resource));
        assertNull(provisioner.updateCleanupPhysicalId(resource));
        provisioner.provision(resource, props().put("Name", renamed).put("Value", "next"), updateCtx(renamed));
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(ssm, never()).deleteParameter(renamed, REGION);
        verify(ssm).putParameter(eq(renamed), eq("old"), eq("String"), any(), eq(true), any(), any(), any(),
                any(), any(), eq(REGION));
        assertEquals(renamed, resource.getPhysicalId());
    }
}
