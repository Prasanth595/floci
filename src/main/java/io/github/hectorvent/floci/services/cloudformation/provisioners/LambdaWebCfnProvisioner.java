package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for Lambda Web Functions: {@code AWS::Lambda::WebFunction},
 * {@code AWS::Lambda::WebFunctionRevision} and {@code AWS::Lambda::WebFunctionEndpoint}.
 *
 * <p>Each type is identified by its ARN, the registry schemas' primaryIdentifier, so {@code Ref}
 * returns it. A function's only createOnly property is its name, so an update that keeps it
 * reconciles the tags in place. Every property of a revision is createOnly: an update whose
 * configuration differs mints a new revision and the displaced one is deleted once the update
 * commits, through {@link ReplacementCleanup}, while an update that changed nothing keeps the
 * revision. An endpoint keeps its identity while its name, type, regions and function stay the
 * same and is updated through the service's UpdateWebFunctionEndpoint otherwise.</p>
 *
 * <p>Template properties are PascalCase and the service reads camelCase, so the properties are
 * lowered as they are passed through; the keys of {@code EnvironmentVariables} are the user's own
 * and stay as written.</p>
 */
@ApplicationScoped
public class LambdaWebCfnProvisioner implements CfnResourceProvisioner {

    static final String FUNCTION = "AWS::Lambda::WebFunction";
    static final String REVISION = "AWS::Lambda::WebFunctionRevision";
    static final String ENDPOINT = "AWS::Lambda::WebFunctionEndpoint";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ENDPOINT_CREATE_ONLY = Set.of("FunctionName", "EndpointName", "EndpointType", "Regions");

    private final LambdaWebService service;

    @Inject
    public LambdaWebCfnProvisioner(LambdaWebService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(FUNCTION, REVISION, ENDPOINT);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case FUNCTION -> provisionFunction(r, props, ctx);
            case REVISION -> provisionRevision(r, props, ctx);
            case ENDPOINT -> provisionEndpoint(r, props, ctx);
            default -> throw new IllegalStateException("LambdaWebCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    /**
     * Every physical id is an ARN that names its function, so the function is read back out of it.
     * A revision or endpoint whose function is already gone counts as deleted; a revision an endpoint
     * still routes to keeps its ConflictException, so the stack reports the failure.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case FUNCTION -> CfnDeletes.safeDelete("web function", physicalId,
                    () -> service.deleteFunction(region, physicalId), "ResourceNotFoundException");
            case REVISION -> CfnDeletes.safeDelete("web function revision", physicalId,
                    () -> service.deleteRevision(region, functionArnOf(physicalId), physicalId), "ResourceNotFoundException");
            case ENDPOINT -> CfnDeletes.safeDelete("web function endpoint", physicalId,
                    () -> service.deleteEndpoint(region, functionArnOf(physicalId), physicalId), "ResourceNotFoundException");
            default -> { }
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    private void provisionFunction(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = require(props, "FunctionName", r, ctx);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        boolean reused = ctx.isUpdate() && ctx.priorPhysicalId() != null
                && name.equals(nameOf(ctx.priorPhysicalId())) && exists(() -> service.getFunction(ctx.region(), name));
        WebFunction fn;
        if (reused) {
            service.replaceTags(ctx.region(), name, tags);
            fn = service.getFunction(ctx.region(), name);
        } else {
            fn = service.createFunctionShell(ctx.region(), ctx.accountId(), name, tags);
        }
        r.setPhysicalId(fn.functionArn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
        r.getAttributes().put("FunctionArn", fn.functionArn);
        r.getAttributes().put("State", "Active");
        r.getAttributes().put("StateReason", "The function is active.");
        r.getAttributes().put("CreatedAt", LambdaWebService.formatTimestamp(fn.createdAt));
        r.getAttributes().put("UpdatedAt", LambdaWebService.formatTimestamp(fn.updatedAt));
    }

    private void provisionRevision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = require(props, "FunctionName", r, ctx);
        JsonNode request = toCamelCase(ctx.engine().resolveNode(props), Set.of("FunctionName"));
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        WebFunctionRevision revision = null;
        if (ctx.isUpdate() && ctx.priorPhysicalId() != null) {
            try {
                WebFunctionRevision existing = service.getRevision(ctx.region(), functionName, ctx.priorPhysicalId());
                if (service.revisionMatches(existing, request)) {
                    revision = existing;
                }
            } catch (AwsException e) {
                // The prior revision is gone or belongs to another function now: a new one is minted below.
            }
        }
        if (revision == null) {
            revision = service.createRevision(ctx.region(), functionName, request);
        }
        r.setPhysicalId(revision.revisionArn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
        r.getAttributes().put("FunctionArn", revision.functionArn);
        r.getAttributes().put("RevisionArn", revision.revisionArn);
        r.getAttributes().put("RevisionId", revision.revisionId);
        r.getAttributes().put("State", "Active");
        r.getAttributes().put("StateReason", "The revision is active.");
        r.getAttributes().put("CreatedAt", LambdaWebService.formatTimestamp(revision.createdAt));
    }

    private void provisionEndpoint(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = require(props, "FunctionName", r, ctx);
        JsonNode resolved = ctx.engine().resolveNode(props);
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        WebFunctionEndpoint endpoint = null;
        if (ctx.isUpdate() && ctx.priorPhysicalId() != null) {
            WebFunctionEndpoint existing = existingEndpoint(ctx, functionName);
            if (existing != null && sameIdentity(existing, resolved, ctx.region())) {
                ObjectNode patch = (ObjectNode) toCamelCase(resolved, ENDPOINT_CREATE_ONLY);
                // A property the template dropped is cleared, as the registry handler does.
                if (!patch.has("scalingConfig")) {
                    patch.putObject("scalingConfig");
                }
                if (!patch.has("throttleConfig")) {
                    patch.putObject("throttleConfig");
                }
                endpoint = service.updateEndpoint(ctx.region(), functionName, existing.endpointName, patch).after();
            }
        }
        if (endpoint == null) {
            endpoint = service.createEndpoint(ctx.region(), ctx.accountId(), functionName,
                    toCamelCase(resolved, Set.of("FunctionName")));
        }
        r.setPhysicalId(endpoint.endpointArn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
        r.getAttributes().put("FunctionArn", endpoint.functionArn);
        r.getAttributes().put("EndpointArn", endpoint.endpointArn);
        r.getAttributes().put("DomainName", endpoint.domainName);
        r.getAttributes().put("State", "Active");
        r.getAttributes().put("StateReason", "The endpoint is active and invokable.");
        if (endpoint.updateStatus != null) {
            r.getAttributes().put("UpdateStatus", endpoint.updateStatus);
            r.getAttributes().put("UpdateStatusReason", "The endpoint is updated.");
        }
        r.getAttributes().put("CreatedAt", LambdaWebService.formatTimestamp(endpoint.createdAt));
        r.getAttributes().put("UpdatedAt", LambdaWebService.formatTimestamp(endpoint.updatedAt));
    }

    private WebFunctionEndpoint existingEndpoint(ProvisionContext ctx, String functionName) {
        try {
            return service.getEndpoint(ctx.region(), functionName, ctx.priorPhysicalId());
        } catch (AwsException e) {
            return null;
        }
    }

    /** The createOnly properties of an endpoint: name, type and the region set, home region included. */
    private static boolean sameIdentity(WebFunctionEndpoint existing, JsonNode props, String homeRegion) {
        if (!existing.endpointName.equals(props.path("EndpointName").asText())
                || !existing.endpointType.equals(props.path("EndpointType").asText())) {
            return false;
        }
        Set<String> regions = new LinkedHashSet<>();
        regions.add(homeRegion);
        props.path("Regions").forEach(region -> regions.add(region.asText()));
        return regions.equals(new LinkedHashSet<>(existing.regions));
    }

    private static boolean exists(Runnable lookup) {
        try {
            lookup.run();
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    private static String require(JsonNode props, String name, StackResource r, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(r.getResourceType() + " requires " + name + " (" + r.getLogicalId() + ")");
        }
        return value;
    }

    /** The function ARN a revision or endpoint ARN belongs to. */
    private static String functionArnOf(String physicalId) {
        int child = physicalId.indexOf("/revision/");
        if (child < 0) {
            child = physicalId.indexOf("/endpoint/");
        }
        return child < 0 ? physicalId : physicalId.substring(0, child);
    }

    private static String nameOf(String functionArn) {
        return functionArn.substring(functionArn.lastIndexOf('/') + 1);
    }

    /** Lowers the first letter of every key except those under EnvironmentVariables, dropping {@code skip}. */
    private static JsonNode toCamelCase(JsonNode node, Set<String> skip) {
        if (node == null || !node.isObject()) {
            return MAPPER.createObjectNode();
        }
        ObjectNode out = MAPPER.createObjectNode();
        node.fields().forEachRemaining(entry -> {
            if (skip.contains(entry.getKey())) {
                return;
            }
            String key = Character.toLowerCase(entry.getKey().charAt(0)) + entry.getKey().substring(1);
            out.set(key, "EnvironmentVariables".equals(entry.getKey()) ? entry.getValue() : lowerKeys(entry.getValue()));
        });
        return out;
    }

    private static JsonNode lowerKeys(JsonNode node) {
        if (node.isObject()) {
            return toCamelCase(node, Set.of());
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(item -> out.add(lowerKeys(item)));
            return out;
        }
        return node;
    }
}
