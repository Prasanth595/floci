package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.core.common.PassedRoles.PassedRole;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where each operation carries the roles it passes, and the service each goes to. The enforcement
 * of the check is covered by {@code IamPassRoleEnforcementIntegrationTest}; this covers the table.
 */
class PassedRolesTest {

    private static final String ROLES = "arn:aws:iam::111122223333:role/";
    private static final String ECS = "ecs.amazonaws.com";
    private static final String ECS_TASKS = "ecs-tasks.amazonaws.com";
    private static final String CLOUDFORMATION = "cloudformation.amazonaws.com";
    private static final String DEFAULT_ADMINISTRATION = ROLES + "AWSCloudFormationStackSetAdministrationRole";
    private static final String EC2 = "ec2.amazonaws.com";
    private static final String AUTOSCALING = "autoscaling.amazonaws.com";
    private static final String PROFILE_ARN = "arn:aws:iam::111122223333:instance-profile/arn-profile";

    /**
     * A task definition {@code web:3} holding a task role by ARN and an execution role by name, two
     * StackSets, two instance profiles, and the launch templates {@code lt-web}, whose every version
     * names one profile by name, and {@code arn-template}, whose version 2 names the other by ARN. The
     * instance {@code i-arn} runs with that other profile, and the group {@code g} launches from
     * {@code lt-web} once a refresh names only a version, or from {@code arn-template} at the group's
     * version 2 once a refresh names that template alone.
     */
    private static final PassedRoles.Lookups LOOKUPS = new PassedRoles.Lookups() {
        @Override
        public List<String> instanceProfileRoleArns(String profileName, String profileArn) {
            if ("web-profile".equals(profileName)) {
                return List.of(ROLES + "web-instance");
            }
            return PROFILE_ARN.equals(profileArn) ? List.of(ROLES + "arn-instance") : List.of();
        }

        @Override
        public Optional<String> launchTemplateInstanceProfile(String templateId, String templateName, String version) {
            if ("lt-web".equals(templateId)) {
                return Optional.of("web-profile");
            }
            return "arn-template".equals(templateName) && "2".equals(version)
                    ? Optional.of(PROFILE_ARN) : Optional.empty();
        }

        @Override
        public Optional<String> launchConfigurationInstanceProfile(String instanceId) {
            return "i-arn".equals(instanceId) ? Optional.of(PROFILE_ARN) : Optional.empty();
        }

        @Override
        public Optional<String> instanceRefreshInstanceProfile(String groupName, String templateId,
                                                               String templateName, String version) {
            if (!"g".equals(groupName)) {
                return Optional.empty();
            }
            if (templateId == null && templateName == null && "2".equals(version)) {
                return Optional.of("web-profile");
            }
            return "arn-template".equals(templateName) && version == null ? Optional.of(PROFILE_ARN) : Optional.empty();
        }

        @Override
        public List<String> taskDefinitionRoles(String reference) {
            return "web:3".equals(reference) ? List.of(ROLES + "web-task", "web-execution") : List.of();
        }

        @Override
        public Optional<String> stackSetPermissionModel(String stackSetName) {
            return switch (stackSetName) {
                case "own-set" -> Optional.of("SELF_MANAGED");
                case "org-set" -> Optional.of("SERVICE_MANAGED");
                default -> Optional.empty();
            };
        }

        @Override
        public String roleArn(String roleName) {
            return ROLES + roleName;
        }
    };

    @Test
    void aTaskDefinitionPassesItsTaskAndExecutionRolesToEcsTasksByNameOrArn() {
        assertEquals(List.of(passed("app", ECS_TASKS), passed("exec", ECS_TASKS)),
                PassedRoles.of("ecs:RegisterTaskDefinition",
                        json("{\"taskRoleArn\":\"app\",\"executionRoleArn\":\"" + ROLES + "exec\"}"), LOOKUPS));
    }

    @Test
    void aTaskPassesItsDefinitionsRolesItsOverridesAndItsVolumesInfrastructureRole() {
        String body = """
                {"taskDefinition":"web:3","overrides":{"taskRoleArn":"%1$sover-task","executionRoleArn":"%1$sover-exec"},
                 "volumeConfigurations":[{"name":"a","managedEBSVolume":{"roleArn":"%1$sebs"}}]}"""
                .formatted(ROLES);
        List<PassedRole> expected = List.of(passed("web-task", ECS_TASKS), passed("web-execution", ECS_TASKS),
                passed("over-task", ECS_TASKS), passed("over-exec", ECS_TASKS), passed("ebs", ECS));

        assertEquals(expected, PassedRoles.of("ecs:RunTask", json(body), LOOKUPS));
        assertEquals(expected, PassedRoles.of("ecs:StartTask", json(body), LOOKUPS));
    }

    @Test
    void aTaskDefinitionThatDoesNotExistPassesNothing() {
        assertEquals(List.of(), PassedRoles.of("ecs:RunTask", json("{\"taskDefinition\":\"missing\"}"), LOOKUPS));
    }

    @Test
    void aServicePassesItsDefinitionsRolesAndEachInfrastructureRole() {
        String body = """
                {"taskDefinition":"web:3","role":"legacy",
                 "loadBalancers":[{"advancedConfiguration":{"roleArn":"%1$slb"}}],
                 "deploymentConfiguration":{"lifecycleHooks":[{"roleArn":"%1$shook"}]},
                 "serviceConnectConfiguration":{"services":[{"tls":{"roleArn":"%1$stls"}}]},
                 "volumeConfigurations":[{"managedEBSVolume":{"roleArn":"%1$sebs"}}],
                 "vpcLatticeConfigurations":[{"roleArn":"%1$slattice"}]}"""
                .formatted(ROLES);
        List<PassedRole> expected = List.of(passed("web-task", ECS_TASKS), passed("web-execution", ECS_TASKS),
                passed("legacy", ECS), passed("lb", ECS), passed("hook", ECS), passed("tls", ECS),
                passed("ebs", ECS), passed("lattice", ECS));

        assertEquals(expected, PassedRoles.of("ecs:CreateService", json(body), LOOKUPS));
        assertEquals(expected, PassedRoles.of("ecs:UpdateService", json(body), LOOKUPS));
        // An update that keeps its task definition passes only the roles it names.
        assertEquals(List.of(passed("lattice", ECS)), PassedRoles.of("ecs:UpdateService",
                json("{\"vpcLatticeConfigurations\":[{\"roleArn\":\"" + ROLES + "lattice\"}]}"), LOOKUPS));
    }

    @Test
    void aTaskSetPassesItsDefinitionsRolesOnly() {
        // The Service Reference gives CreateTaskSet ecs-tasks.amazonaws.com alone.
        String body = "{\"taskDefinition\":\"web:3\",\"loadBalancers\":[{\"advancedConfiguration\":{\"roleArn\":\""
                + ROLES + "lb\"}}]}";
        assertEquals(List.of(passed("web-task", ECS_TASKS), passed("web-execution", ECS_TASKS)),
                PassedRoles.of("ecs:CreateTaskSet", json(body), LOOKUPS));
    }

    @Test
    void aManagedInstancesProviderPassesItsInfrastructureRoleToEcs() {
        String body = "{\"managedInstancesProvider\":{\"infrastructureRoleArn\":\"" + ROLES + "infra\"}}";
        assertEquals(List.of(passed("infra", ECS)), PassedRoles.of("ecs:CreateCapacityProvider", json(body), LOOKUPS));
        assertEquals(List.of(passed("infra", ECS)), PassedRoles.of("ecs:UpdateCapacityProvider", json(body), LOOKUPS));
    }

    @Test
    void stepFunctionsAndEventBridgePassTheRoleTheyName() throws IOException {
        String role = "{\"roleArn\":\"" + ROLES + "sm\"}";
        assertEquals(List.of(passed("sm", "states.amazonaws.com")),
                PassedRoles.of("states:CreateStateMachine", json(role), LOOKUPS));
        assertEquals(List.of(passed("sm", "states.amazonaws.com")),
                PassedRoles.of("states:UpdateStateMachine", cbor(role), LOOKUPS));
        assertEquals(List.of(passed("sm", "states.amazonaws.com")),
                PassedRoles.of("states:CreateStateMachine", gzippedCbor(role), LOOKUPS));
        assertEquals(List.of(passed("rule", "events.amazonaws.com")),
                PassedRoles.of("events:PutRule", json("{\"RoleArn\":\"" + ROLES + "rule\"}"), LOOKUPS));
        assertEquals(List.of(passed("one", "events.amazonaws.com"), passed("two", "events.amazonaws.com")),
                PassedRoles.of("events:PutTargets", json("{\"Targets\":[{\"RoleArn\":\"" + ROLES + "one\"},{},"
                        + "{\"RoleArn\":\"" + ROLES + "two\"}]}"), LOOKUPS));
    }

    @Test
    void schedulerPipesAndLambdaMicrovmsPassTheRoleTheyName() {
        String target = "{\"Target\":{\"RoleArn\":\"" + ROLES + "schedule\"}}";
        assertEquals(List.of(passed("schedule", "scheduler.amazonaws.com")),
                PassedRoles.of("scheduler:CreateSchedule", json(target), LOOKUPS));
        assertEquals(List.of(passed("schedule", "scheduler.amazonaws.com")),
                PassedRoles.of("scheduler:UpdateSchedule", json(target), LOOKUPS));
        String pipe = "{\"RoleArn\":\"" + ROLES + "pipe\"}";
        assertEquals(List.of(passed("pipe", "pipes.amazonaws.com")), PassedRoles.of("pipes:CreatePipe", json(pipe), LOOKUPS));
        assertEquals(List.of(passed("pipe", "pipes.amazonaws.com")), PassedRoles.of("pipes:UpdatePipe", json(pipe), LOOKUPS));
        String build = "{\"buildRoleArn\":\"" + ROLES + "build\"}";
        assertEquals(List.of(passed("build", "lambda.amazonaws.com")),
                PassedRoles.of("lambda:CreateMicrovmImage", json(build), LOOKUPS));
        assertEquals(List.of(passed("build", "lambda.amazonaws.com")),
                PassedRoles.of("lambda:UpdateMicrovmImage", json(build), LOOKUPS));
        assertEquals(List.of(passed("vm", "lambda.amazonaws.com")), PassedRoles.of("lambda:RunMicrovm",
                json("{\"executionRoleArn\":\"" + ROLES + "vm\"}"), LOOKUPS));
        String operator = "{\"OperatorRole\":\"" + ROLES + "operator\"}";
        assertEquals(List.of(passed("operator", "lambda.amazonaws.com")),
                PassedRoles.of("lambda:CreateNetworkConnector", json(operator), LOOKUPS));
        assertEquals(List.of(passed("operator", "lambda.amazonaws.com")),
                PassedRoles.of("lambda:UpdateNetworkConnector", json(operator), LOOKUPS));
    }

    @Test
    void outsideEcsOnlyARoleArnIsPassed() {
        assertEquals(List.of(), PassedRoles.of("states:CreateStateMachine", json("{\"roleArn\":\"sm\"}"), LOOKUPS));
    }

    @Test
    void aStackOperationPassesTheServiceRoleItNames() {
        for (String action : List.of("CreateStack", "UpdateStack", "DeleteStack", "CreateChangeSet",
                "ContinueUpdateRollback")) {
            assertEquals(List.of(passed("stack", CLOUDFORMATION)),
                    PassedRoles.of("cloudformation:" + action, form("RoleARN=" + ROLES + "stack"), LOOKUPS), action);
            assertEquals(List.of(), PassedRoles.of("cloudformation:" + action, form("StackName=s"), LOOKUPS), action);
        }
    }

    @Test
    void aStackSetPassesTheAdministrationRoleItNamesOrTheDefaultOneWhenSelfManaged() {
        PassedRole defaultRole = new PassedRole(DEFAULT_ADMINISTRATION, CLOUDFORMATION);
        assertEquals(List.of(defaultRole), PassedRoles.of("cloudformation:CreateStackSet", form("StackSetName=new"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("cloudformation:CreateStackSet",
                form("StackSetName=new&PermissionModel=SERVICE_MANAGED"), LOOKUPS));
        assertEquals(List.of(passed("admin", CLOUDFORMATION)), PassedRoles.of("cloudformation:CreateStackSet",
                form("StackSetName=new&AdministrationRoleARN=" + ROLES + "admin"), LOOKUPS));

        // An update goes by the StackSet's own permission model, which a PermissionModel it names doesn't change.
        assertEquals(List.of(defaultRole), PassedRoles.of("cloudformation:UpdateStackSet", form("StackSetName=own-set"), LOOKUPS));
        assertEquals(List.of(defaultRole), PassedRoles.of("cloudformation:UpdateStackSet",
                form("StackSetName=own-set&PermissionModel=SERVICE_MANAGED"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("cloudformation:UpdateStackSet", form("StackSetName=org-set"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("cloudformation:UpdateStackSet",
                form("StackSetName=org-set&PermissionModel=SELF_MANAGED"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("cloudformation:UpdateStackSet", form("StackSetName=missing"), LOOKUPS));
        assertEquals(List.of(passed("admin", CLOUDFORMATION)), PassedRoles.of("cloudformation:UpdateStackSet",
                form("StackSetName=org-set&AdministrationRoleARN=" + ROLES + "admin"), LOOKUPS));
    }

    @Test
    void aLaunchConfigurationPassesItsInstanceProfilesRolesToEc2ByNameOrArn() {
        assertEquals(List.of(passed("web-instance", EC2)), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&IamInstanceProfile=web-profile"), LOOKUPS));
        assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&IamInstanceProfile=" + PROFILE_ARN), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&IamInstanceProfile=missing"), LOOKUPS));
    }

    @Test
    void aLaunchConfigurationMadeFromAnInstancePassesTheInstancesProfileUnlessItNamesOne() {
        assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&InstanceId=i-arn"), LOOKUPS));
        assertEquals(List.of(passed("web-instance", EC2)), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&InstanceId=i-arn&IamInstanceProfile=web-profile"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("autoscaling:CreateLaunchConfiguration",
                form("LaunchConfigurationName=c&InstanceId=i-none"), LOOKUPS));
    }

    @Test
    void aGroupPassesTheRolesOfEachLaunchTemplatesInstanceProfileToEc2AtTheVersionItNames() {
        for (String action : List.of("CreateAutoScalingGroup", "UpdateAutoScalingGroup")) {
            assertEquals(List.of(passed("web-instance", EC2)), PassedRoles.of("autoscaling:" + action,
                    form("LaunchTemplate.LaunchTemplateId=lt-web"), LOOKUPS), action);
            assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:" + action,
                    form("LaunchTemplate.LaunchTemplateName=arn-template&LaunchTemplate.Version=2"), LOOKUPS), action);
            // A version whose data names no profile passes nothing, nor does a launch configuration.
            assertEquals(List.of(), PassedRoles.of("autoscaling:" + action,
                    form("LaunchTemplate.LaunchTemplateName=arn-template&LaunchTemplate.Version=1"), LOOKUPS), action);
            assertEquals(List.of(), PassedRoles.of("autoscaling:" + action,
                    form("LaunchConfigurationName=c"), LOOKUPS), action);

            // A mixed instances policy names its template, and each override may name another.
            String mixed = "MixedInstancesPolicy.LaunchTemplate.";
            String policy = mixed + "LaunchTemplateSpecification.LaunchTemplateName=arn-template&"
                    + mixed + "LaunchTemplateSpecification.Version=2&"
                    + mixed + "Overrides.member.1.InstanceType=t3.micro&"
                    + mixed + "Overrides.member.2.LaunchTemplateSpecification.LaunchTemplateId=lt-web";
            assertEquals(List.of(passed("arn-instance", EC2), passed("web-instance", EC2)),
                    PassedRoles.of("autoscaling:" + action, form(policy), LOOKUPS), action);
        }
    }

    @Test
    void aGroupPassesAServiceLinkedRoleOtherThanTheDefaultAndItsHooksRolesToAutoScaling() {
        String serviceLinked = "aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling";
        assertEquals(List.of(passed(serviceLinked + "_custom", AUTOSCALING)),
                PassedRoles.of("autoscaling:UpdateAutoScalingGroup",
                        form("ServiceLinkedRoleARN=" + ROLES + serviceLinked + "_custom"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("autoscaling:UpdateAutoScalingGroup",
                form("ServiceLinkedRoleARN=" + ROLES + serviceLinked), LOOKUPS));

        String hooks = "LifecycleHookSpecificationList.member.";
        assertEquals(List.of(passed("web-instance", EC2), passed(serviceLinked + "_custom", AUTOSCALING),
                        passed("hook", AUTOSCALING), passed("other-hook", AUTOSCALING)),
                PassedRoles.of("autoscaling:CreateAutoScalingGroup", form("LaunchTemplate.LaunchTemplateId=lt-web"
                        + "&ServiceLinkedRoleARN=" + ROLES + serviceLinked + "_custom"
                        + "&" + hooks + "1.RoleARN=" + ROLES + "hook&" + hooks + "2.LifecycleHookName=no-role"
                        + "&" + hooks + "3.RoleARN=" + ROLES + "other-hook"
                        + "&" + hooks + "5.RoleARN=" + ROLES + "after-a-gap"), LOOKUPS));
    }

    @Test
    void anInstanceRefreshPassesTheRolesOfTheTemplateItsGroupLaunchesFromOnceTheDesiredConfigurationApplies() {
        // The desired configuration goes to the group's own lookup as given, a version alone or a template alone.
        assertEquals(List.of(passed("web-instance", EC2)), PassedRoles.of("autoscaling:StartInstanceRefresh",
                form("AutoScalingGroupName=g&DesiredConfiguration.LaunchTemplate.Version=2"), LOOKUPS));
        assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:StartInstanceRefresh",
                form("AutoScalingGroupName=g&DesiredConfiguration.LaunchTemplate.LaunchTemplateName=arn-template"),
                LOOKUPS));
        String desired = "DesiredConfiguration.MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.";
        assertEquals(List.of(passed("arn-instance", EC2)), PassedRoles.of("autoscaling:StartInstanceRefresh",
                form("AutoScalingGroupName=g&" + desired + "LaunchTemplateName=arn-template&" + desired + "Version=2"),
                LOOKUPS));
        // Without a desired configuration the refresh uses the group's own template, checked when the group named it.
        assertEquals(List.of(), PassedRoles.of("autoscaling:StartInstanceRefresh",
                form("AutoScalingGroupName=g"), LOOKUPS));
    }

    @Test
    void aLifecycleHookAndAScalableTargetPassTheRoleTheyName() {
        assertEquals(List.of(passed("hook", AUTOSCALING)), PassedRoles.of("autoscaling:PutLifecycleHook",
                form("AutoScalingGroupName=g&LifecycleHookName=h&RoleARN=" + ROLES + "hook"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("autoscaling:PutLifecycleHook",
                form("AutoScalingGroupName=g&LifecycleHookName=h"), LOOKUPS));
        assertEquals(List.of(passed("scaling", "application-autoscaling.amazonaws.com")),
                PassedRoles.of("application-autoscaling:RegisterScalableTarget",
                        json("{\"ServiceNamespace\":\"ecs\",\"RoleARN\":\"" + ROLES + "scaling\"}"), LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("application-autoscaling:RegisterScalableTarget",
                json("{\"ServiceNamespace\":\"ecs\"}"), LOOKUPS));
    }

    @Test
    void aGlueJobCrawlerAndCatalogEncryptionPassTheirRoleToGlueByNameOrArn() {
        String glue = "glue.amazonaws.com";
        for (String action : new String[] {"glue:CreateJob", "glue:CreateCrawler", "glue:UpdateCrawler"}) {
            assertEquals(List.of(passed("etl", glue)), PassedRoles.of(action,
                    json("{\"Name\":\"j\",\"Role\":\"" + ROLES + "etl\"}"), LOOKUPS), action);
            assertEquals(List.of(passed("service-role/etl", glue)), PassedRoles.of(action,
                    json("{\"Name\":\"j\",\"Role\":\"service-role/etl\"}"), LOOKUPS), action);
            assertEquals(List.of(), PassedRoles.of(action, json("{\"Name\":\"j\"}"), LOOKUPS), action);
        }
        // UpdateJob carries the role inside JobUpdate; a top-level Role is not one it reads.
        assertEquals(List.of(passed("etl", glue)), PassedRoles.of("glue:UpdateJob",
                json("{\"JobName\":\"j\",\"Role\":\"" + ROLES + "other\",\"JobUpdate\":{\"Role\":\"etl\"}}"),
                LOOKUPS));
        assertEquals(List.of(), PassedRoles.of("glue:UpdateJob",
                json("{\"JobName\":\"j\",\"JobUpdate\":{\"Timeout\":5}}"), LOOKUPS));
        assertEquals(List.of(passed("catalog", glue)), PassedRoles.of("glue:PutDataCatalogEncryptionSettings",
                json("{\"DataCatalogEncryptionSettings\":{\"EncryptionAtRest\":{\"CatalogEncryptionMode\":"
                        + "\"SSE-KMS-WITH-SERVICE-ROLE\",\"CatalogEncryptionServiceRole\":\"" + ROLES + "catalog\"}}}"),
                LOOKUPS));
    }

    private static PassedRole passed(String roleName, String service) {
        return new PassedRole(ROLES + roleName, service);
    }

    private static ContainerRequestContext json(String body) {
        return request("application/x-amz-json-1.1", body.getBytes(StandardCharsets.UTF_8));
    }

    private static ContainerRequestContext cbor(String body) throws IOException {
        return request("application/cbor",
                new ObjectMapper(new CBORFactory()).writeValueAsBytes(new ObjectMapper().readTree(body)));
    }

    private static ContainerRequestContext gzippedCbor(String body) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(compressed)) {
            out.write(new ObjectMapper(new CBORFactory()).writeValueAsBytes(new ObjectMapper().readTree(body)));
        }
        ContainerRequestContext request = request("application/cbor", compressed.toByteArray());
        when(request.getHeaderString("Content-Encoding")).thenReturn("gzip");
        return request;
    }

    private static ContainerRequestContext form(String body) {
        return request("application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8));
    }

    private static ContainerRequestContext request(String mediaType, byte[] body) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMediaType()).thenReturn(MediaType.valueOf(mediaType));
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(body));
        return request;
    }
}
