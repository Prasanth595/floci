package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A policy statement naming one IAM user, group, role, instance profile, managed policy, MFA
 * device or identity provider has to bind to that entity and no other. Until these were mapped
 * every such action was evaluated against {@code *}, so a resource-scoped {@code Deny} was
 * silently inert and a resource-scoped {@code Allow} granted nothing. These drive the whole
 * enforcement path, so a statement that stops binding fails here rather than passing unnoticed.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamResourceScopedEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";

    private static String arn(String resource) {
        return "arn:aws:iam::" + ACCOUNT_ID + ":" + resource;
    }

    private static String allowAllExceptDeny(String action, String resource) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"%s","Resource":"%s"}]}""".formatted(action, resource);
    }

    /** The reproduction from the issue: a Deny naming one user protects that user only. */
    @Test
    void aDenyNamingOneUserLeavesTheOthersAlone() {
        String suffix = suffix();
        String protectedUser = "protected-" + suffix;
        String other = "other-" + suffix;
        admin("CreateUser", Map.of("UserName", protectedUser)).statusCode(200);
        admin("CreateUser", Map.of("UserName", other)).statusCode(200);

        String akid = userWith(allowAllExceptDeny("iam:DeleteUser", arn("user/" + protectedUser)));

        as(akid, "DeleteUser", Map.of("UserName", protectedUser))
                .statusCode(403).body(containsString("AccessDenied"));
        as(akid, "DeleteUser", Map.of("UserName", other)).statusCode(200);
        admin("GetUser", Map.of("UserName", protectedUser)).statusCode(200);
    }

    /** The stored path is part of the ARN: the same name without it is a different resource. */
    @Test
    void theStoredPathIsPartOfTheUserArn() {
        String name = "pathed-" + suffix();
        admin("CreateUser", Map.of("UserName", name, "Path", "/team/")).statusCode(200);

        as(userWith(allowAllExceptDeny("iam:TagUser", arn("user/team/" + name))),
                "TagUser", Map.of("UserName", name, "Tags.member.1.Key", "k", "Tags.member.1.Value", "v"))
                .statusCode(403).body(containsString("AccessDenied"));
        as(userWith(allowAllExceptDeny("iam:TagUser", arn("user/" + name))),
                "TagUser", Map.of("UserName", name, "Tags.member.1.Key", "k", "Tags.member.1.Value", "v"))
                .statusCode(200);
    }

    /** A create is checked against the path it asks for, so a denied path cannot be created into. */
    @Test
    void aCreateIsAuthorizedAgainstThePathItAsksFor() {
        String name = "create-" + suffix();
        String akid = userWith(allowAllExceptDeny("iam:CreateRole", arn("role/restricted/*")));
        String trust = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
                "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

        as(akid, "CreateRole", Map.of("RoleName", name, "Path", "/restricted/",
                "AssumeRolePolicyDocument", trust))
                .statusCode(403).body(containsString("AccessDenied"));
        as(akid, "CreateRole", Map.of("RoleName", name, "AssumeRolePolicyDocument", trust))
                .statusCode(200);
    }

    /** A scoped Allow grants the role it names and no other, instead of granting nothing. */
    @Test
    void aResourceScopedAllowGrantsThatRoleOnly() {
        String suffix = suffix();
        String granted = "granted-" + suffix;
        String other = "other-" + suffix;
        createRole(granted);
        createRole(other);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:GetRole","Resource":"%s"}]}"""
                .formatted(arn("role/" + granted)));

        as(akid, "GetRole", Map.of("RoleName", granted)).statusCode(200);
        as(akid, "GetRole", Map.of("RoleName", other))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /** Group membership changes are authorized against the group, as the reference lists. */
    @Test
    void aDenyNamingAGroupBlocksMembershipChanges() {
        String suffix = suffix();
        String group = "admins-" + suffix;
        String member = "member-" + suffix;
        admin("CreateGroup", Map.of("GroupName", group)).statusCode(200);
        admin("CreateUser", Map.of("UserName", member)).statusCode(200);

        as(userWith(allowAllExceptDeny("iam:AddUserToGroup", arn("group/" + group))),
                "AddUserToGroup", Map.of("GroupName", group, "UserName", member))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void aDenyNamingAnInstanceProfileBindsToIt() {
        String profile = "profile-" + suffix();
        admin("CreateInstanceProfile", Map.of("InstanceProfileName", profile)).statusCode(200);

        as(userWith(allowAllExceptDeny("iam:DeleteInstanceProfile", arn("instance-profile/" + profile))),
                "DeleteInstanceProfile", Map.of("InstanceProfileName", profile))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /** A managed policy is named by the PolicyArn the request carries. */
    @Test
    void aDenyNamingAManagedPolicyBindsToIt() {
        String name = "policy-" + suffix();
        String policyArn = admin("CreatePolicy", Map.of("PolicyName", name, "PolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}"""))
                .statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        as(userWith(allowAllExceptDeny("iam:DeletePolicy", policyArn)),
                "DeletePolicy", Map.of("PolicyArn", policyArn))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void aDenyNamingAVirtualMfaDeviceBindsToIt() {
        String name = "device-" + suffix();
        String serial = admin("CreateVirtualMFADevice", Map.of("VirtualMFADeviceName", name))
                .statusCode(200)
                .extract().path("CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult"
                        + ".VirtualMFADevice.SerialNumber");

        as(userWith(allowAllExceptDeny("iam:DeleteVirtualMFADevice", serial)),
                "DeleteVirtualMFADevice", Map.of("SerialNumber", serial))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void aDenyNamingAnOidcProviderBindsToIt() {
        String host = "oidc-" + suffix() + ".example.com";
        String providerArn = admin("CreateOpenIDConnectProvider", Map.of("Url", "https://" + host,
                "ThumbprintList.member.1", "a".repeat(40)))
                .statusCode(200)
                .extract().path("CreateOpenIDConnectProviderResponse"
                        + ".CreateOpenIDConnectProviderResult.OpenIDConnectProviderArn");

        as(userWith(allowAllExceptDeny("iam:DeleteOpenIDConnectProvider", providerArn)),
                "DeleteOpenIDConnectProvider", Map.of("OpenIDConnectProviderArn", providerArn))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /**
     * A self-service statement: a user may manage their own access keys. Leaving UserName out
     * defaults it to the caller, so the resource has to be the caller too, or a statement naming
     * the caller's own ARN never matches and grants nothing.
     */
    @Test
    void aSelfServiceStatementGrantsTheCallerTheirOwnKeys() {
        String user = "self-" + suffix();
        String other = "neighbour-" + suffix();
        admin("CreateUser", Map.of("UserName", other)).statusCode(200);
        String akid = namedUserWith(user, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["iam:ListAccessKeys","iam:CreateAccessKey"],
                   "Resource":"%s"}]}""".formatted(arn("user/" + user)));

        as(akid, "ListAccessKeys", Map.of()).statusCode(200);
        as(akid, "ListAccessKeys", Map.of("UserName", user)).statusCode(200);
        as(akid, "ListAccessKeys", Map.of("UserName", other))
                .statusCode(403).body(containsString("AccessDenied"));
        as(akid, "CreateAccessKey", Map.of("UserName", other))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /** A rename needs permission on both names, as for certificates. */
    @Test
    void aUserRenameIsDeniedWhenOnlyTheDestinationNameIsDenied() {
        String suffix = suffix();
        String from = "rename-from-" + suffix;
        String reserved = "rename-reserved-" + suffix;
        admin("CreateUser", Map.of("UserName", from)).statusCode(200);

        as(userWith(allowAllExceptDeny("iam:UpdateUser", arn("user/" + reserved))),
                "UpdateUser", Map.of("UserName", from, "NewUserName", reserved))
                .statusCode(403).body(containsString("AccessDenied"));
        admin("GetUser", Map.of("UserName", from)).statusCode(200);
    }

    /** The legacy Operation parameter is resolved to the same resource as Action. */
    @Test
    void theLegacyOperationParameterIsAuthorizedAgainstTheUserToo() {
        String name = "legacy-" + suffix();
        admin("CreateUser", Map.of("UserName", name)).statusCode(200);
        String akid = userWith(allowAllExceptDeny("iam:DeleteUser", arn("user/" + name)));

        given()
                .header("Authorization", auth(akid))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Operation", "DeleteUser")
                .formParam("Version", "2010-05-08")
                .formParam("UserName", name)
        .when().post("/").then()
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /** The reference lists no resource type for ListUsers, so a statement naming a user cannot reach it. */
    @Test
    void theListIsNotScopedToAUser() {
        String name = "listed-" + suffix();
        admin("CreateUser", Map.of("UserName", name)).statusCode(200);

        as(userWith(allowAllExceptDeny("iam:ListUsers", arn("user/" + name))), "ListUsers", Map.of())
                .statusCode(200);
    }

    private static void createRole(String name) {
        admin("CreateRole", Map.of("RoleName", name, "AssumeRolePolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
                "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}"""))
                .statusCode(200);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** An IAM user in {@link #ACCOUNT_ID} carrying {@code policy} inline. */
    private static String userWith(String policy) {
        return namedUserWith("iam-scope-" + suffix(), policy);
    }

    private static String namedUserWith(String user, String policy) {
        admin("CreateUser", Map.of("UserName", user)).statusCode(200);
        admin("PutUserPolicy", Map.of("UserName", user, "PolicyName", "p",
                "PolicyDocument", policy)).statusCode(200);
        return admin("CreateAccessKey", Map.of("UserName", user)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static ValidatableResponse admin(String action, Map<String, String> params) {
        return as(ACCOUNT_ID, action, params);
    }

    private static ValidatableResponse as(String akid, String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", auth(akid))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static String auth(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260919/" + REGION
                + "/iam/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
