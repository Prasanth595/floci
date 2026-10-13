package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Provisions {@code AWS::SSM::Parameter}. */
@ApplicationScoped
public class SsmCfnProvisioner implements CfnResourceProvisioner {

    private static final int PARAMETER_NAME_MAX_LENGTH = 2048;
    private static final String SSM_TEMPLATE_TAG_KEYS_ATTR = "FlociSsmTemplateTagKeys";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SsmService ssmService;

    public SsmCfnProvisioner(SsmService ssmService) {
        this.ssmService = ssmService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SSM::Parameter");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"),
                r.getLogicalId(), PARAMETER_NAME_MAX_LENGTH, false);
        String value = ctx.resolveOptional(props, "Value");
        if (value == null) {
            value = "";
        }
        String type = ctx.resolveOptional(props, "Type");
        if (type == null) {
            type = "String";
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        SsmService.validateTagKeys(tags);
        boolean sameName = ctx.reusesPriorEntity(name);
        ParameterUpdate previous = null;
        if (ctx.isUpdate()) {
            // Only a rollback that failed leaves the snapshot behind. Failing here hands it to this
            // update's rollback, which retries restoring the parameter it describes.
            if (r.getAttributes().containsKey(CfnRollback.SSM_UPDATE_SNAPSHOT_ATTR)) {
                throw new AwsException("ResourceConflictException",
                        "The previous update of this parameter has not finished rolling back", 400);
            }
            // A rename never writes the prior parameter, so it is read only for an update in place: a
            // rename rolls back by deleting its replacement, which also leaves one already deleted
            // outside the stack where it was.
            if (sameName) {
                Parameter prior = ssmService.getParameter(ctx.priorPhysicalId(), ctx.region());
                previous = new ParameterUpdate(ctx.region(), prior.getName(), prior.getValue(), prior.getType(),
                        prior.getDescription(), prior.getKeyId(), prior.getAllowedPattern(), prior.getTier(),
                        prior.getPolicies(), new HashMap<>(prior.getTags()), new HashMap<>(r.getAttributes()),
                        List.copyOf(tags.keySet()), true, null);
            } else {
                previous = new ParameterUpdate(ctx.region(), ctx.priorPhysicalId(), null, null, null, null, null,
                        null, null, Map.of(), new HashMap<>(r.getAttributes()), List.copyOf(tags.keySet()), false,
                        null);
            }
            remember(r, previous);
        }
        ssmService.putParameter(name, value, type, null, sameName, ctx.region());
        if (previous != null && !sameName) {
            remember(r, previous.withReplacement(name));
        }
        r.setPhysicalId(name);
        reconcileTags(name, sameName ? r.getAttributes().get(SSM_TEMPLATE_TAG_KEYS_ATTR) : null, tags, ctx.region());
        r.getAttributes().put("Name", name);
        r.getAttributes().put("Type", type);
        r.getAttributes().put("Value", value);
        r.getAttributes().put("Arn", parameterArn(name, ctx));
        // Tag keys cannot contain a comma, so the sorted keys join losslessly.
        if (tags.isEmpty()) {
            r.getAttributes().remove(SSM_TEMPLATE_TAG_KEYS_ATTR);
        } else {
            r.getAttributes().put(SSM_TEMPLATE_TAG_KEYS_ATTR, String.join(",", new TreeSet<>(tags.keySet())));
        }
    }

    /**
     * Applies the template's tags and removes only the keys the previous template set and this one
     * drops, so a tag added outside the template stays, as in AWS. The overwriting put keeps the
     * parameter's existing tags, and a parameter provisioned before the keys were recorded has none
     * to drop.
     */
    private void reconcileTags(String name, String previousTemplateKeys, Map<String, String> desired,
                               String region) {
        List<String> dropped = previousTemplateKeys == null || previousTemplateKeys.isEmpty()
                ? List.of()
                : Arrays.stream(previousTemplateKeys.split(","))
                        .filter(key -> !desired.containsKey(key))
                        .toList();
        if (!dropped.isEmpty()) {
            ssmService.removeTagsFromResource(name, dropped, region);
        }
        if (!desired.isEmpty()) {
            ssmService.addTagsToResource(name, desired, region);
        }
    }

    /** AWS's form is {@code parameter/<name>} whether or not the name starts with a slash. */
    private static String parameterArn(String name, ProvisionContext ctx) {
        String path = name.startsWith("/") ? name : "/" + name;
        return AwsArnUtils.Arn.of("ssm", ctx.region(), ctx.accountId(), "parameter" + path).toString();
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("SSM parameter", physicalId,
                () -> ssmService.deleteParameter(physicalId, region), "ParameterNotFound");
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(CfnRollback.SSM_UPDATE_SNAPSHOT_ATTR);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ParameterUpdate prior = previous(resource);
        if (prior == null) {
            return false;
        }
        if (prior.replacement() != null) {
            delete(resource.getResourceType(), prior.replacement(), prior.region());
        } else if (prior.inPlace()) {
            ssmService.putParameter(prior.name(), prior.value(), prior.type(), prior.description(), true,
                    null, prior.keyId(), prior.allowedPattern(), prior.tier(), prior.policies(), prior.region());
            Set<String> changedKeys = new TreeSet<>(prior.attemptedTagKeys());
            String oldKeys = prior.attributes().get(SSM_TEMPLATE_TAG_KEYS_ATTR);
            if (oldKeys != null && !oldKeys.isEmpty()) {
                changedKeys.addAll(Arrays.asList(oldKeys.split(",")));
            }
            Map<String, String> restoredTags = new HashMap<>(prior.tags());
            restoredTags.keySet().retainAll(changedKeys);
            reconcileTags(prior.name(), String.join(",", prior.attemptedTagKeys()), restoredTags, prior.region());
        }
        resource.setPhysicalId(prior.name());
        resource.getAttributes().clear();
        resource.getAttributes().putAll(prior.attributes());
        return true;
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        ParameterUpdate prior = previous(resource);
        return prior != null && prior.replacement() != null;
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        ParameterUpdate prior = previous(resource);
        return hasReplacementUpdate(resource) && !"Retain".equals(resource.getUpdateReplacePolicy())
                ? prior.name() : null;
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        ParameterUpdate prior = previous(resource);
        if (prior == null) {
            return UpdateCleanupResult.notApplicable();
        }
        String displaced = updateCleanupPhysicalId(resource);
        if (displaced != null) {
            try {
                delete(resource.getResourceType(), displaced, prior.region());
            } catch (RuntimeException failure) {
                int attempts = Integer.parseInt(
                        resource.getAttributes().getOrDefault(CfnRollback.SSM_CLEANUP_ATTEMPTS_ATTR, "0")) + 1;
                resource.getAttributes().put(CfnRollback.SSM_CLEANUP_ATTEMPTS_ATTR, Integer.toString(attempts));
                return new UpdateCleanupResult(true, false, displaced, attempts, failure.getMessage());
            }
        }
        resource.getAttributes().remove(CfnRollback.SSM_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(CfnRollback.SSM_CLEANUP_ATTEMPTS_ATTR);
        return new UpdateCleanupResult(true, true, displaced, 0, null);
    }

    /**
     * Drops the snapshot once the committed update's cleanup completed or used up its attempts. A
     * displaced parameter whose delete kept failing is abandoned, as AWS abandons it: a snapshot
     * kept past the commit would make the next update's rollback delete the live parameter.
     */
    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.SSM_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(CfnRollback.SSM_CLEANUP_ATTEMPTS_ATTR);
    }

    private static void remember(StackResource resource, ParameterUpdate previous) {
        try {
            resource.getAttributes().put(CfnRollback.SSM_UPDATE_SNAPSHOT_ATTR, MAPPER.writeValueAsString(previous));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot record parameter rollback state", failure);
        }
    }

    private static ParameterUpdate previous(StackResource resource) {
        String snapshot = resource.getAttributes().get(CfnRollback.SSM_UPDATE_SNAPSHOT_ATTR);
        if (snapshot == null) {
            return null;
        }
        try {
            return MAPPER.readValue(snapshot, ParameterUpdate.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot read parameter rollback state", failure);
        }
    }

    @RegisterForReflection
    public record ParameterUpdate(String region, String name, String value, String type, String description,
                                  String keyId, String allowedPattern, String tier, List<JsonNode> policies,
                                  Map<String, String> tags, Map<String, String> attributes,
                                  List<String> attemptedTagKeys, boolean inPlace, String replacement) {
        ParameterUpdate withReplacement(String name) {
            return new ParameterUpdate(region, this.name, value, type, description, keyId, allowedPattern,
                    tier, policies, tags, attributes, attemptedTagKeys, inPlace, name);
        }
    }
}
