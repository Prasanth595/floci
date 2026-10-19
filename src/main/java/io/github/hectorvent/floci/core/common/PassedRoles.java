package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The IAM roles a request passes to a service, which the caller also needs {@code iam:PassRole} on.
 * The IAM user guide: "To pass a role (and its permissions) to an AWS service, a user must have
 * permissions to pass the role to the service." AWS's Service Reference names, for each operation
 * that passes one, the service the role goes to, which the check offers as {@code iam:PassedToService};
 * where the request carries the role differs per operation and is listed here.
 *
 * <p>An EC2 request names an instance profile rather than a role, and passes the roles the profile
 * holds. Launching from a launch template passes nothing here: the EC2 user guide warns that a user
 * who can create launch template versions "can potentially update a launch template to pass an IAM
 * role to an instance even if they don't have the iam:PassRole permission", so the role is checked
 * where the template is created.
 */
final class PassedRoles {

    private static final Logger LOG = Logger.getLogger(PassedRoles.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An IAM role ARN, the only resource {@code iam:PassRole} acts on. */
    private static final Pattern ROLE_ARN = Pattern.compile("^arn:[a-z0-9-]+:iam::\\d{12}:role/.+");

    private static final String LAMBDA = ServicePrincipals.of("lambda");
    private static final String EC2 = ServicePrincipals.of("ec2");
    private static final String VPC_FLOW_LOGS = ServicePrincipals.of("vpc-flow-logs");

    /** A role a request passes, and the service principal it is passed to. */
    record PassedRole(String roleArn, String passedToService) {
    }

    /** The ARNs of the roles an EC2 instance profile holds, looked up by its name or its ARN. */
    @FunctionalInterface
    interface InstanceProfileRoles {
        List<String> roleArns(String profileName, String profileArn);
    }

    private PassedRoles() {
    }

    /** The roles a request for {@code action} passes; empty when the operation passes none or the request names none. */
    static List<PassedRole> of(String action, ContainerRequestContext ctx, InstanceProfileRoles profiles) {
        return switch (action) {
            case "lambda:CreateFunction", "lambda:UpdateFunctionConfiguration" -> fromJson(ctx, LAMBDA, "Role");
            case "lambda:CreateWebFunction" ->
                    fromJson(ctx, LAMBDA, "revisionConfig", "serviceConfig", "executionRoleArn");
            case "lambda:CreateWebFunctionRevision" -> fromJson(ctx, LAMBDA, "serviceConfig", "executionRoleArn");
            case "ec2:RunInstances", "ec2:AssociateIamInstanceProfile", "ec2:ReplaceIamInstanceProfileAssociation" ->
                    fromInstanceProfile(ctx, profiles, "IamInstanceProfile");
            case "ec2:RequestSpotInstances" ->
                    fromInstanceProfile(ctx, profiles, "LaunchSpecification.IamInstanceProfile");
            case "ec2:CreateLaunchTemplate", "ec2:CreateLaunchTemplateVersion" ->
                    fromInstanceProfile(ctx, profiles, "LaunchTemplateData.IamInstanceProfile");
            case "ec2:CreateFlowLogs" -> fromForm(ctx, VPC_FLOW_LOGS, "DeliverLogsPermissionArn");
            default -> List.of();
        };
    }

    private static List<PassedRole> fromJson(ContainerRequestContext ctx, String service, String... path) {
        JsonNode node = jsonBody(ctx);
        for (String member : path) {
            node = node == null ? null : node.get(member);
        }
        return node != null && node.isTextual() ? passed(List.of(node.asText()), service) : List.of();
    }

    private static List<PassedRole> fromForm(ContainerRequestContext ctx, String service, String parameter) {
        String roleArn = IamConditionContextResolver.formParameters(ctx).get(parameter);
        return roleArn == null ? List.of() : passed(List.of(roleArn), service);
    }

    /** The roles of the instance profile named by {@code prefix}.Name or {@code prefix}.Arn. */
    private static List<PassedRole> fromInstanceProfile(ContainerRequestContext ctx, InstanceProfileRoles profiles,
                                                        String prefix) {
        Map<String, String> form = IamConditionContextResolver.formParameters(ctx);
        String name = blankToNull(form.get(prefix + ".Name"));
        String arn = blankToNull(form.get(prefix + ".Arn"));
        if (name == null && arn == null) {
            return List.of();
        }
        return passed(profiles.roleArns(name, arn), EC2);
    }

    /** The roles that are IAM role ARNs; anything else is left for the operation to refuse. */
    private static List<PassedRole> passed(List<String> roleArns, String service) {
        List<PassedRole> passed = new ArrayList<>();
        for (String roleArn : roleArns) {
            if (roleArn != null && ROLE_ARN.matcher(roleArn).matches()) {
                passed.add(new PassedRole(roleArn, service));
            }
        }
        return passed;
    }

    /** The request's JSON body, read once and kept where the other request readers look for it. */
    private static JsonNode jsonBody(ContainerRequestContext ctx) {
        if (ctx.getProperty(IamConditionContextResolver.BUFFERED_JSON_BODY) instanceof JsonNode cached) {
            return cached;
        }
        byte[] body = IamConditionContextResolver.bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            ctx.setProperty(IamConditionContextResolver.BUFFERED_JSON_BODY, node);
            return node;
        } catch (IOException e) {
            // The operation answers a body it cannot parse itself; nothing here names a role.
            LOG.debugv(e, "Request body is not JSON, so it passes no role");
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
