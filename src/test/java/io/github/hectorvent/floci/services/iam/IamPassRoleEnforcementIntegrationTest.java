package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request that passes a role to Lambda or EC2 also needs {@code iam:PassRole} on that role, with
 * {@code iam:PassedToService} naming the service, as the IAM user guide requires: "To pass a role
 * (and its permissions) to an AWS service, a user must have permissions to pass the role to the
 * service." Without it a user allowed only to create functions or launch instances could hand them
 * any role in the account and act with its permissions.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamPassRoleEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";
    private static final String IMAGE = "ami-0abcdef1234567890";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void creatingAFunctionNeedsPassRoleOnItsExecutionRoleForLambda() {
        String role = role("fn-exec");
        User user = user("passrole-create-fn");
        String name = "passrole-fn-" + suffix();
        String body = """
                {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler","Role":"%s"}"""
                .formatted(name, role);

        user.policy(allow("lambda:CreateFunction", "*"));
        denied(user.lambda().body(body).post("/2015-03-31/functions").then());

        // The permission names the service the role is passed to: one for EC2 does not cover Lambda.
        user.policy(allow("lambda:CreateFunction", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        denied(user.lambda().body(body).post("/2015-03-31/functions").then());

        user.policy(allow("lambda:CreateFunction", "*") + "," + passRole(role, "lambda.amazonaws.com"));
        user.lambda().body(body).post("/2015-03-31/functions").then().statusCode(201);
        cleanup.register(() -> admin("lambda").contentType("application/json")
                .delete("/2015-03-31/functions/{name}", name));
    }

    @Test
    void updatingAFunctionNeedsPassRoleOnlyWhenItSetsTheRole() {
        String first = role("fn-first");
        String second = role("fn-second");
        String name = "passrole-update-" + suffix();
        admin("lambda").contentType("application/json")
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler","Role":"%s"}"""
                        .formatted(name, first))
                .post("/2015-03-31/functions").then().statusCode(201);
        cleanup.register(() -> admin("lambda").contentType("application/json")
                .delete("/2015-03-31/functions/{name}", name));
        User user = user("passrole-update-fn");
        String configuration = "/2015-03-31/functions/" + name + "/configuration";

        user.policy(allow("lambda:UpdateFunctionConfiguration", "*"));
        user.lambda().body("{\"Timeout\":10}").put(configuration).then().statusCode(200);
        denied(user.lambda().body("{\"Role\":\"" + second + "\"}").put(configuration).then());

        user.policy(allow("lambda:UpdateFunctionConfiguration", "*") + ","
                + passRole(second, "lambda.amazonaws.com"));
        user.lambda().body("{\"Role\":\"" + second + "\"}").put(configuration).then().statusCode(200);
    }

    @Test
    void aWebFunctionNeedsPassRoleOnItsExecutionRole() {
        String role = role("web-exec");
        User user = user("passrole-web-fn");
        String name = "passrole-web-" + suffix();
        String function = """
                {"functionName":"%s","revisionConfig":{"serviceConfig":{"executionRoleArn":"%s"}}}"""
                .formatted(name, role);
        String revision = "{\"serviceConfig\":{\"executionRoleArn\":\"" + role + "\"}}";

        user.policy(allow("lambda:CreateWebFunction", "*") + "," + allow("lambda:CreateWebFunctionRevision", "*"));
        denied(user.lambda().body(function).put("/2025-03-07/web-functions").then());
        denied(user.lambda().body(revision).post("/2025-03-07/web-functions/" + name + "/revisions").then());

        // With the permission the request reaches Lambda, which answers it on its own terms.
        user.policy(allow("lambda:CreateWebFunction", "*") + "," + allow("lambda:CreateWebFunctionRevision", "*")
                + "," + passRole(role, "lambda.amazonaws.com"));
        user.lambda().body(function).put("/2025-03-07/web-functions").then().statusCode(not(403));
        user.lambda().body(revision).post("/2025-03-07/web-functions/" + name + "/revisions").then()
                .statusCode(not(403));
        cleanup.register(() -> admin("lambda").delete("/2025-03-07/web-functions/{name}", name));
    }

    @Test
    void anInstanceProfileNeedsPassRoleOnItsRoleWhenAnInstanceIsLaunchedOrAssociated() {
        String role = role("ec2-instance");
        String roleName = role.substring(role.lastIndexOf('/') + 1);
        String profile = instanceProfile(roleName);
        String profileArn = "arn:aws:iam::" + ACCOUNT + ":instance-profile/" + profile;
        User user = user("passrole-ec2");

        user.policy(allow("ec2:RunInstances", "*") + "," + allow("ec2:AssociateIamInstanceProfile", "*"));
        denied(user.ec2("RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Name", profile).post("/").then());
        String instanceId = user.ec2("RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1").post("/").then().statusCode(200)
                .extract().xmlPath().getString("RunInstancesResponse.instancesSet.item.instanceId");
        terminateLater(instanceId);
        denied(user.ec2("AssociateIamInstanceProfile").formParam("InstanceId", instanceId)
                .formParam("IamInstanceProfile.Arn", profileArn).post("/").then());
        // A profile that does not exist passes no role; EC2 refuses it as it would without enforcement.
        user.ec2("RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Name", "no-such-profile").post("/").then().statusCode(400)
                .body(containsString("InvalidParameterValue"));

        user.policy(allow("ec2:RunInstances", "*") + "," + allow("ec2:AssociateIamInstanceProfile", "*") + ","
                + passRole(role, "ec2.amazonaws.com"));
        terminateLater(user.ec2("RunInstances").formParam("ImageId", IMAGE).formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Name", profile).post("/").then().statusCode(200)
                .extract().xmlPath().getString("RunInstancesResponse.instancesSet.item.instanceId"));
        String associationId = user.ec2("AssociateIamInstanceProfile").formParam("InstanceId", instanceId)
                .formParam("IamInstanceProfile.Arn", profileArn).post("/").then().statusCode(200)
                .extract().xmlPath().getString(
                        "AssociateIamInstanceProfileResponse.iamInstanceProfileAssociation.associationId");

        // Replacing the association passes the new profile's role.
        String other = role("ec2-replacement");
        String otherProfile = instanceProfile(other.substring(other.lastIndexOf('/') + 1));
        user.policy(allow("ec2:ReplaceIamInstanceProfileAssociation", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        denied(user.ec2("ReplaceIamInstanceProfileAssociation").formParam("AssociationId", associationId)
                .formParam("IamInstanceProfile.Name", otherProfile).post("/").then());
        user.policy(allow("ec2:ReplaceIamInstanceProfileAssociation", "*") + "," + passRole(other, "ec2.amazonaws.com"));
        user.ec2("ReplaceIamInstanceProfileAssociation").formParam("AssociationId", associationId)
                .formParam("IamInstanceProfile.Name", otherProfile).post("/").then().statusCode(200);
    }

    @Test
    void aSpotInstanceRequestNeedsPassRoleOnItsInstanceProfilesRole() {
        String role = role("ec2-spot");
        String profile = instanceProfile(role.substring(role.lastIndexOf('/') + 1));
        String profileArn = "arn:aws:iam::" + ACCOUNT + ":instance-profile/" + profile;
        User user = user("passrole-spot");

        user.policy(allow("ec2:RequestSpotInstances", "*"));
        denied(spotRequest(user, "Arn", profileArn).then());
        denied(spotRequest(user, "Name", profile).then());

        user.policy(allow("ec2:RequestSpotInstances", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        assertEquals(profileArn, spotProfileArn(user, "Arn", profileArn));
        // A profile named rather than given by ARN is the same profile, and the instance gets it.
        assertEquals(profileArn, spotProfileArn(user, "Name", profile));
    }

    @Test
    void aLaunchTemplateWithAnInstanceProfileNeedsPassRoleOnItsRole() {
        String role = role("ec2-template");
        String profile = instanceProfile(role.substring(role.lastIndexOf('/') + 1));
        User user = user("passrole-template");
        String name = "passrole-template-" + suffix();

        user.policy(allow("ec2:CreateLaunchTemplate", "*"));
        denied(user.ec2("CreateLaunchTemplate").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", IMAGE)
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", profile).post("/").then());

        user.policy(allow("ec2:CreateLaunchTemplate", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        user.ec2("CreateLaunchTemplate").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", IMAGE)
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", profile).post("/").then().statusCode(200);
        cleanup.register(() -> admin("ec2").formParam("Action", "DeleteLaunchTemplate")
                .formParam("LaunchTemplateName", name).post("/"));

        // A new version of the template passes its profile's role too.
        user.policy(allow("ec2:CreateLaunchTemplateVersion", "*"));
        denied(user.ec2("CreateLaunchTemplateVersion").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", IMAGE)
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", profile).post("/").then());
        user.policy(allow("ec2:CreateLaunchTemplateVersion", "*") + "," + passRole(role, "ec2.amazonaws.com"));
        user.ec2("CreateLaunchTemplateVersion").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", IMAGE)
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", profile).post("/").then().statusCode(200);
    }

    @Test
    void aFlowLogNeedsPassRoleOnItsDeliveryRoleForFlowLogs() {
        String role = role("flow-logs");
        String vpcId = admin("ec2").formParam("Action", "DescribeVpcs").post("/").then().statusCode(200)
                .extract().xmlPath().getString("DescribeVpcsResponse.vpcSet.item[0].vpcId");
        User user = user("passrole-flow-logs");

        user.policy(allow("ec2:CreateFlowLogs", "*"));
        denied(flowLog(user, vpcId, role).then());

        user.policy(allow("ec2:CreateFlowLogs", "*") + "," + passRole(role, "vpc-flow-logs.amazonaws.com"));
        String flowLogId = flowLog(user, vpcId, role).then().statusCode(200)
                .extract().xmlPath().getString("CreateFlowLogsResponse.flowLogIdSet.item");
        cleanup.register(() -> admin("ec2").formParam("Action", "DeleteFlowLogs")
                .formParam("FlowLogId.1", flowLogId).post("/"));
    }

    /** Requests a Spot Instance, removes it afterwards, and returns the profile ARN it was launched with. */
    private String spotProfileArn(User user, String selector, String profile) {
        String item = "RequestSpotInstancesResponse.spotInstanceRequestSet.item.";
        XmlPath request = spotRequest(user, selector, profile).then().statusCode(200).extract().xmlPath();
        String requestId = request.getString(item + "spotInstanceRequestId");
        cleanup.register(() -> admin("ec2").formParam("Action", "CancelSpotInstanceRequests")
                .formParam("SpotInstanceRequestId.1", requestId).post("/"));
        terminateLater(request.getString(item + "instanceId"));
        return request.getString(item + "launchSpecification.iamInstanceProfile.arn");
    }

    /** A Spot Instance request naming its instance profile by {@code selector}, {@code Arn} or {@code Name}. */
    private static Response spotRequest(User user, String selector, String profile) {
        return user.ec2("RequestSpotInstances").formParam("SpotPrice", "0.05").formParam("InstanceCount", "1")
                .formParam("LaunchSpecification.ImageId", IMAGE).formParam("LaunchSpecification.InstanceType", "t3.micro")
                .formParam("LaunchSpecification.IamInstanceProfile." + selector, profile).post("/");
    }

    private static Response flowLog(User user, String vpcId, String role) {
        return user.ec2("CreateFlowLogs").formParam("ResourceId.1", vpcId).formParam("ResourceType", "VPC")
                .formParam("TrafficType", "ALL").formParam("LogGroupName", "passrole-flow-logs")
                .formParam("DeliverLogsPermissionArn", role).post("/");
    }

    private static void denied(ValidatableResponse response) {
        response.statusCode(403).body(containsString("iam:PassRole"));
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String passRole(String role, String service) {
        return "{\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",\"Resource\":\"" + role + "\","
                + "\"Condition\":{\"StringEquals\":{\"iam:PassedToService\":\"" + service + "\"}}}";
    }

    private String role(String name) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", name + "-" + suffix(),
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:*\","
                        + "\"Resource\":\"*\"}]}");
    }

    private String instanceProfile(String roleName) {
        String name = "passrole-profile-" + suffix();
        cleanup.register(() -> EnforcementFixtures.removeInstanceProfile(baseUri.getPort(), auth(ADMIN, "iam"), name));
        admin("iam").formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200);
        admin("iam").formParam("Action", "AddRoleToInstanceProfile").formParam("InstanceProfileName", name)
                .formParam("RoleName", roleName).post("/").then().statusCode(200);
        return name;
    }

    private void terminateLater(String instanceId) {
        cleanup.register(() -> admin("ec2").formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", instanceId).post("/"));
    }

    private User user(String name) {
        String userName = name + "-" + suffix();
        return new User(userName, EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"),
                userName).accessKeyId());
    }

    private static RequestSpecification admin(String service) {
        return given().header("Authorization", auth(ADMIN, service));
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    /** An IAM user whose one inline policy each test replaces. */
    private record User(String name, String accessKeyId) {

        void policy(String statements) {
            admin("iam").formParam("Action", "PutUserPolicy").formParam("UserName", name)
                    .formParam("PolicyName", "p")
                    .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}")
                    .post("/").then().statusCode(200);
        }

        RequestSpecification lambda() {
            return given().header("Authorization", auth(accessKeyId, "lambda")).contentType("application/json");
        }

        RequestSpecification ec2(String action) {
            return given().header("Authorization", auth(accessKeyId, "ec2")).formParam("Action", action);
        }
    }
}
