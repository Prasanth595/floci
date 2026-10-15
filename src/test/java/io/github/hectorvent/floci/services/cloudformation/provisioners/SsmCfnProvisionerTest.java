package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

    @BeforeEach
    void stubPriorParameter() {
        // An in-place update reads the existing parameter back to snapshot it. Default that read to a
        // plain parameter so a test not about the snapshot still provisions; a test asserting the
        // snapshot re-stubs it, and the later stub wins.
        when(ssm.getParameter(NAME, REGION)).thenReturn(new Parameter(NAME, "v", "String"));
    }

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
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), NAME);
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

        verify(ssm).putParameter(NAME, "v", "String", null, true, REGION);
        verify(ssm).addTagsToResource(NAME, Map.of("team", "a", "env", "dev"), REGION);
        verify(ssm, never()).removeTagsFromResource(anyString(), any(), anyString());
        assertEquals("env,team", r.getAttributes().get(TEMPLATE_TAG_KEYS_ATTR));
    }

    @Test
    void parameterWithoutTagsIsNotTagged() {
        StackResource r = parameter(null, null);

        provisioner.provision(r, props(), ctx());

        verify(ssm).putParameter(NAME, "v", "String", null, true, REGION);
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
    void updateSnapshotsThePriorParameterAndRollbackRestoresIt() {
        Parameter prior = new Parameter(NAME, "old", "String");
        prior.setDescription("old-desc");
        when(ssm.getParameter(NAME, REGION)).thenReturn(prior);
        StackResource r = parameter(NAME, null);

        provisioner.provision(r, props(), updateCtx());

        verify(ssm).putParameter(NAME, "v", "String", null, true, REGION);
        assertTrue(r.getAttributes().containsKey(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ssm).putParameter(NAME, "old", "String", "old-desc", true, REGION);
        assertFalse(r.getAttributes().containsKey(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackSnapshotsAParameterWithNoDescriptionAsNull() {
        when(ssm.getParameter(NAME, REGION)).thenReturn(new Parameter(NAME, "old", "String"));
        StackResource r = parameter(NAME, null);
        provisioner.provision(r, props(), updateCtx());

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ssm).putParameter(NAME, "old", "String", null, true, REGION);
    }

    @Test
    void completeUpdateDropsTheSnapshot() {
        when(ssm.getParameter(NAME, REGION)).thenReturn(new Parameter(NAME, "old", "String"));
        StackResource r = parameter(NAME, null);
        provisioner.provision(r, props(), updateCtx());

        assertEquals(UpdateCleanupResult.notApplicable(), provisioner.completeUpdate(r));

        assertFalse(r.getAttributes().containsKey(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackWithoutASnapshotIsANoOp() {
        assertFalse(provisioner.rollbackUpdate(parameter(NAME, null)));
    }

    @Test
    void updateOfAnOutOfBandDeletedParameterKeepsNoSnapshot() {
        when(ssm.getParameter(NAME, REGION))
                .thenThrow(new AwsException("ParameterNotFound", "Parameter not found.", 400));
        StackResource r = parameter(NAME, null);

        provisioner.provision(r, props(), updateCtx());

        assertFalse(r.getAttributes().containsKey(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR));
        assertFalse(provisioner.rollbackUpdate(r));
    }
}
