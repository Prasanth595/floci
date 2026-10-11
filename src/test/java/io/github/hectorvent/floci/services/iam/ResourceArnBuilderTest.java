package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceArnBuilderTest {

    private ResourceArnBuilder builder;
    private ContainerRequestContext ctx;
    private UriInfo uriInfo;
    private MultivaluedMap<String, String> queryParams;
    private Map<String, Object> contextProperties;

    @BeforeEach
    void setUp() {
        builder = new ResourceArnBuilder(new ObjectMapper());
        ctx = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        queryParams = new MultivaluedHashMap<>();
        contextProperties = new HashMap<>();

        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);

        when(ctx.getProperty(any())).thenAnswer(inv -> contextProperties.get(inv.getArgument(0)));
        doAnswer(inv -> {
            contextProperties.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setProperty(any(), any());
    }

    /** Query-protocol services carry their parameters in a form body, as IAM does. */
    private void setFormBody(String form) {
        contextProperties.clear();
        byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        when(ctx.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        when(ctx.getEntityStream()).thenReturn(in);
        doAnswer(inv -> {
            InputStream newIn = inv.getArgument(0);
            when(ctx.getEntityStream()).thenReturn(newIn);
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
    }

    // ── IAM ──────────────────────────────────────────────────────────────────────

    /**
     * A builder whose store holds one certificate under {@code name} with {@code arn}, which is
     * what the check reads for every operation acting on a certificate that already exists.
     */
    private ResourceArnBuilder backedBy(String name, String arn) {
        ServerCertificate stored = new ServerCertificate();
        stored.setServerCertificateName(name);
        stored.setArn(arn);
        IamService service = mock(IamService.class);
        when(service.findServerCertificate(name)).thenReturn(Optional.of(stored));
        @SuppressWarnings("unchecked")
        Instance<IamService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(service);
        return new ResourceArnBuilder(new ObjectMapper(), instance);
    }

    /**
     * The resource a certificate-specific policy statement has to match. Without this the action
     * is evaluated against {@code *}, so a deny naming the certificate never applies.
     */
    @Test
    void iamNamesTheServerCertificateBeingActedOn() {
        String arn = "arn:aws:iam::000000000000:server-certificate/my-cert";
        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=my-cert");
        assertEquals(arn, backedBy("my-cert", arn).build("iam", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void iamNamesTheCertificateForEveryOperationThatTakesOne() {
        String arn = "arn:aws:iam::000000000000:server-certificate/c1";
        ResourceArnBuilder stored = backedBy("c1", arn);
        for (String action : List.of("GetServerCertificate", "UpdateServerCertificate",
                "DeleteServerCertificate", "TagServerCertificate", "UntagServerCertificate",
                "ListServerCertificateTags")) {
            setFormBody("Action=" + action + "&ServerCertificateName=c1");
            assertEquals(arn, stored.build("iam", ctx, "us-east-1", "000000000000"), action);
        }
        // The upload is the one that mints rather than reads: nothing is stored yet.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1");
        assertEquals(arn, builder.build("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * The one invariant behind every way this has been got wrong: for an operation acting on a
     * certificate that exists, the ARN the permission check uses is that certificate's own stored
     * ARN -- whatever spelling named the operation, and whatever partition signed the request.
     *
     * <p>Enumerated rather than sampled, because each defect so far hid in a combination the
     * narrower tests did not reach: the mapping missing entirely, then {@code Operation} skipping
     * it, then the partition coming from the signing region. Nothing here derives an ARN, so a
     * regression has to show up as a mismatch.
     */
    @Test
    void iamChecksEveryRouteAndSigningRegionAgainstTheStoredArn() {
        for (String storedArn : List.of(
                "arn:aws:iam::000000000000:server-certificate/c1",
                "arn:aws-cn:iam::000000000000:server-certificate/team/c1",
                "arn:aws-us-gov:iam::000000000000:server-certificate/deep/nested/c1")) {
            ResourceArnBuilder stored = backedBy("c1", storedArn);
            for (String action : List.of("GetServerCertificate", "UpdateServerCertificate",
                    "DeleteServerCertificate", "TagServerCertificate", "UntagServerCertificate",
                    "ListServerCertificateTags")) {
                for (String spelling : List.of("Action", "Operation")) {
                    for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
                        setFormBody(spelling + "=" + action + "&ServerCertificateName=c1");
                        assertEquals(storedArn,
                                stored.build("iam", ctx, region, "000000000000"),
                                action + " named by " + spelling + ", signed in " + region);
                    }
                }
            }
        }
    }

    /**
     * A rename is authorized against both names. The model requires the principal to be allowed on
     * the old and the new one, and the filter authorizes a request once per resource, so naming
     * both is what stops a rename into a name the caller may not write.
     */
    @Test
    void iamNamesBothTheOldAndTheNewNameOnARename() {
        String stored = "arn:aws:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old"
                + "&NewServerCertificateName=new");
        assertEquals(
                List.of(stored, "arn:aws:iam::000000000000:server-certificate/new"),
                backedBy("old", stored).buildResources("iam", ctx, "us-east-1", "000000000000"));
    }

    /** A move renames the path, so the destination path is named the same way. */
    @Test
    void iamNamesTheDestinationPathOnAMove() {
        String stored = "arn:aws:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old&NewPath=/team/");
        assertEquals(
                List.of(stored, "arn:aws:iam::000000000000:server-certificate/team/old"),
                backedBy("old", stored).buildResources("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * An update that renames and moves nothing acts on one resource, so it names one. Repeating
     * the same ARN would make the request look like it touches two.
     */
    @Test
    void iamNamesOneResourceWhenAnUpdateChangesNeitherNameNorPath() {
        String stored = "arn:aws:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old");
        assertEquals(List.of(stored),
                backedBy("old", stored).buildResources("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * The destination is built beside the stored ARN, so it keeps the certificate's partition. A
     * rename does not move a resource between partitions, and taking the partition from the
     * caller's signing region would name one that does not exist.
     */
    @Test
    void iamBuildsTheRenameDestinationInTheStoredPartition() {
        String stored = "arn:aws-cn:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old"
                + "&NewServerCertificateName=new");
        assertEquals(
                List.of(stored, "arn:aws-cn:iam::000000000000:server-certificate/new"),
                backedBy("old", stored).buildResources("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * Nothing stored under that name means nothing to name: the operation fails as NoSuchEntity
     * regardless, and minting an ARN for an absent resource would hand a policy something to match.
     */
    @Test
    void iamFallsBackToTheWildcardWhenNoCertificateIsStored() {
        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=absent");
        assertEquals("*", backedBy("other", "arn:aws:iam::000000000000:server-certificate/other")
                .build("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * An upload names a certificate that does not exist yet, so its path has to come from the
     * request. Taking it from the store would resolve to the root path and let a policy scoped to
     * one path authorize an upload into another.
     */
    @Test
    void iamTakesTheUploadPathFromTheRequest() {
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1&Path=/team/");
        assertEquals("arn:aws:iam::000000000000:server-certificate/team/c1",
                builder.build("iam", ctx, "us-east-1", "000000000000"));

        // Written without surrounding slashes, it still lands as a path segment.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1&Path=team");
        assertEquals("arn:aws:iam::000000000000:server-certificate/team/c1",
                builder.build("iam", ctx, "us-east-1", "000000000000"));

        // Omitted, it is the root path.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1");
        assertEquals("arn:aws:iam::000000000000:server-certificate/c1",
                builder.build("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * IAM is global, so the ARN carries no region. An upload creates the certificate in the
     * caller's own partition, so that is the partition its ARN is minted in.
     */
    @Test
    void iamMintsAnUploadedCertificateArnInTheRequestPartition() {
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=my-cert");
        assertEquals("arn:aws-cn:iam::000000000000:server-certificate/my-cert",
                builder.build("iam", ctx, "cn-north-1", "000000000000"));

        setFormBody("Action=UploadServerCertificate&ServerCertificateName=my-cert");
        assertEquals("arn:aws-us-gov:iam::000000000000:server-certificate/my-cert",
                builder.build("iam", ctx, "us-gov-west-1", "000000000000"));
    }

    /**
     * An existing certificate stays in the partition it was created in, so the check reads the
     * partition from its stored ARN. Deriving it from the caller's signing region instead would
     * name an ARN no resource has, and a deny on the certificate's real ARN would not match it.
     */
    @Test
    void iamKeepsTheStoredPartitionWhateverRegionSignedTheRequest() {
        String arn = "arn:aws:iam::000000000000:server-certificate/my-cert";
        ResourceArnBuilder stored = backedBy("my-cert", arn);

        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=my-cert");
        assertEquals(arn, stored.build("iam", ctx, "cn-north-1", "000000000000"));

        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=my-cert");
        assertEquals(arn, stored.build("iam", ctx, "us-gov-west-1", "000000000000"));
    }

    /**
     * ListServerCertificates names no certificate, and a user the store does not hold has no ARN
     * to read, so both resolve to the wildcard rather than to a fabricated ARN.
     */
    @Test
    void iamFallsBackToTheWildcardWhenNoCertificateIsNamed() {
        setFormBody("Action=ListServerCertificates&PathPrefix=/team/");
        assertEquals("*", builder.build("iam", ctx, "us-east-1", "000000000000"));

        setFormBody("Action=DeleteUser&UserName=bob");
        assertEquals("*", builder.build("iam", ctx, "us-east-1", "000000000000"));

        setFormBody("Action=DeleteServerCertificate");
        assertEquals("*", builder.build("iam", ctx, "us-east-1", "000000000000"));
    }

    // ── IAM: users, groups, roles, instance profiles, policies, MFA, providers ──

    private static final String ACCOUNT = "000000000000";

    /** A store holding one user, group, role and instance profile, each under /team/. */
    private IamService entityStore() {
        IamService service = mock(IamService.class);
        IamUser user = new IamUser();
        user.setUserName("bob");
        user.setPath("/team/");
        user.setArn("arn:aws:iam::000000000000:user/team/bob");
        when(service.findUser(ACCOUNT, "bob")).thenReturn(Optional.of(user));
        IamGroup group = new IamGroup();
        group.setGroupName("admins");
        group.setPath("/team/");
        group.setArn("arn:aws:iam::000000000000:group/team/admins");
        when(service.findGroup(ACCOUNT, "admins")).thenReturn(Optional.of(group));
        IamRole role = new IamRole();
        role.setRoleName("deployer");
        role.setPath("/team/");
        role.setArn("arn:aws:iam::000000000000:role/team/deployer");
        when(service.findRole(ACCOUNT, "deployer")).thenReturn(Optional.of(role));
        InstanceProfile profile = new InstanceProfile();
        profile.setInstanceProfileName("web");
        profile.setPath("/team/");
        profile.setArn("arn:aws:iam::000000000000:instance-profile/team/web");
        when(service.findInstanceProfile(ACCOUNT, "web")).thenReturn(Optional.of(profile));
        when(service.findUserNameByAccessKeyId("AKIABOB")).thenReturn(Optional.of("bob"));
        return service;
    }

    private ResourceArnBuilder storeBuilder(IamService service) {
        @SuppressWarnings("unchecked")
        Instance<IamService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(service);
        AccountResolver resolver = mock(AccountResolver.class);
        when(resolver.extractAccessKeyId(any())).thenAnswer(inv -> {
            String header = inv.getArgument(0);
            if (header == null) {
                return null;
            }
            int start = header.indexOf("Credential=") + "Credential=".length();
            return header.substring(start, header.indexOf('/', start));
        });
        return new ResourceArnBuilder(new ObjectMapper(), instance, resolver);
    }

    private void signedBy(String accessKeyId) {
        when(ctx.getHeaderString("Authorization")).thenReturn("AWS4-HMAC-SHA256 Credential="
                + accessKeyId + "/20260919/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=x");
    }

    private List<String> iam(ResourceArnBuilder b, String form) {
        setFormBody(form);
        return b.buildResources("iam", ctx, "us-east-1", ACCOUNT);
    }

    /**
     * Every operation acting on an existing user, group, role or instance profile is checked
     * against that entity's stored ARN, path included. Before this, all of them resolved to
     * {@code *} and a statement naming the entity was inert.
     */
    @Test
    void iamNamesTheStoredEntityForEveryOperationThatTakesOne() {
        ResourceArnBuilder b = storeBuilder(entityStore());
        for (String action : List.of("GetUser", "DeleteUser", "TagUser", "ListUserTags",
                "CreateAccessKey", "ListAccessKeys", "AttachUserPolicy", "PutUserPolicy",
                "PutUserPermissionsBoundary", "ListGroupsForUser", "UploadSSHPublicKey",
                "ListSigningCertificates", "CreateServiceSpecificCredential",
                "EnableMFADevice", "ListMFADevices", "CreateLoginProfile")) {
            assertEquals(List.of("arn:aws:iam::000000000000:user/team/bob"),
                    iam(b, "Action=" + action + "&UserName=bob"), action);
        }
        for (String action : List.of("GetGroup", "DeleteGroup", "AddUserToGroup",
                "RemoveUserFromGroup", "AttachGroupPolicy", "PutGroupPolicy")) {
            assertEquals(List.of("arn:aws:iam::000000000000:group/team/admins"),
                    iam(b, "Action=" + action + "&GroupName=admins&UserName=bob"), action);
        }
        for (String action : List.of("GetRole", "DeleteRole", "UpdateRole", "UpdateAssumeRolePolicy",
                "TagRole", "AttachRolePolicy", "PutRolePolicy", "PutRolePermissionsBoundary",
                "ListInstanceProfilesForRole", "DeleteServiceLinkedRole")) {
            assertEquals(List.of("arn:aws:iam::000000000000:role/team/deployer"),
                    iam(b, "Action=" + action + "&RoleName=deployer"), action);
        }
        for (String action : List.of("GetInstanceProfile", "DeleteInstanceProfile",
                "TagInstanceProfile", "AddRoleToInstanceProfile", "RemoveRoleFromInstanceProfile")) {
            assertEquals(List.of("arn:aws:iam::000000000000:instance-profile/team/web"),
                    iam(b, "Action=" + action + "&InstanceProfileName=web&RoleName=deployer"), action);
        }
    }

    /** The legacy Operation parameter names the resource exactly as Action does. */
    @Test
    void iamResolvesTheLegacyOperationParameterForEntities() {
        assertEquals(List.of("arn:aws:iam::000000000000:user/team/bob"),
                iam(storeBuilder(entityStore()), "Operation=DeleteUser&UserName=bob"));
    }

    /** A create has no stored entity, so its ARN comes from the request's own Path and partition. */
    @Test
    void iamMintsACreatedEntityFromTheRequestedPath() {
        ResourceArnBuilder b = storeBuilder(entityStore());
        assertEquals(List.of("arn:aws:iam::000000000000:user/ops/new-user"),
                iam(b, "Action=CreateUser&UserName=new-user&Path=/ops/"));
        assertEquals(List.of("arn:aws:iam::000000000000:group/new-group"),
                iam(b, "Action=CreateGroup&GroupName=new-group"));
        assertEquals(List.of("arn:aws:iam::000000000000:role/ops/new-role"),
                iam(b, "Action=CreateRole&RoleName=new-role&Path=/ops/"));
        assertEquals(List.of("arn:aws:iam::000000000000:instance-profile/new-profile"),
                iam(b, "Action=CreateInstanceProfile&InstanceProfileName=new-profile"));
        assertEquals(List.of("arn:aws:iam::000000000000:policy/ops/new-policy"),
                iam(b, "Action=CreatePolicy&PolicyName=new-policy&Path=/ops/"));
        assertEquals(List.of("arn:aws:iam::000000000000:mfa/bob-device"),
                iam(b, "Action=CreateVirtualMFADevice&VirtualMFADeviceName=bob-device"));
        assertEquals(List.of("arn:aws:iam::000000000000:saml-provider/corp"),
                iam(b, "Action=CreateSAMLProvider&Name=corp"));
        assertEquals(List.of("arn:aws:iam::000000000000:oidc-provider/token.example.com/path"),
                iam(b, "Action=CreateOpenIDConnectProvider&Url=https://token.example.com/path"));

        setFormBody("Action=CreateUser&UserName=cn-user");
        assertEquals("arn:aws-cn:iam::000000000000:user/cn-user",
                b.build("iam", ctx, "cn-north-1", ACCOUNT));
    }

    /** The service-linked role sits under the principal's path, which is what a scoped Allow names. */
    @Test
    void iamNamesTheServiceLinkedRoleBeingCreated() {
        List<String> arns = iam(storeBuilder(entityStore()),
                "Action=CreateServiceLinkedRole&AWSServiceName=elasticloadbalancing.amazonaws.com");
        assertEquals(1, arns.size());
        assertTrue(arns.getFirst().startsWith(
                "arn:aws:iam::000000000000:role/aws-service-role/elasticloadbalancing.amazonaws.com/"),
                arns.getFirst());
    }

    /** Operations that already carry an ARN are checked against that ARN as written. */
    @Test
    void iamPassesThroughTheArnTheRequestCarries() {
        ResourceArnBuilder b = storeBuilder(entityStore());
        String policy = "arn:aws:iam::000000000000:policy/team/p";
        for (String action : List.of("GetPolicy", "DeletePolicy", "CreatePolicyVersion",
                "SetDefaultPolicyVersion", "TagPolicy", "ListEntitiesForPolicy")) {
            assertEquals(List.of(policy), iam(b, "Action=" + action + "&PolicyArn=" + policy), action);
        }
        String mfa = "arn:aws:iam::000000000000:mfa/bob-device";
        assertEquals(List.of(mfa), iam(b, "Action=DeleteVirtualMFADevice&SerialNumber=" + mfa));
        assertEquals(List.of(mfa), iam(b, "Action=TagMFADevice&SerialNumber=" + mfa));
        String saml = "arn:aws:iam::000000000000:saml-provider/corp";
        assertEquals(List.of(saml), iam(b, "Action=DeleteSAMLProvider&SAMLProviderArn=" + saml));
        String oidc = "arn:aws:iam::000000000000:oidc-provider/token.example.com";
        assertEquals(List.of(oidc),
                iam(b, "Action=AddClientIDToOpenIDConnectProvider&OpenIDConnectProviderArn=" + oidc));
        String user = "arn:aws:iam::000000000000:user/team/bob";
        assertEquals(List.of(user), iam(b, "Action=SimulatePrincipalPolicy&PolicySourceArn=" + user));
        assertEquals(List.of(user), iam(b, "Action=GenerateServiceLastAccessedDetails&Arn=" + user));
        // A hardware MFA serial number is not an ARN and names no IAM resource.
        assertEquals(List.of("*"), iam(b, "Action=DeleteVirtualMFADevice&SerialNumber=GAHT12345678"));
    }

    /** A rename needs permission on both names, so both ARNs are checked, as for certificates. */
    @Test
    void iamNamesBothNamesOnAUserOrGroupRename() {
        ResourceArnBuilder b = storeBuilder(entityStore());
        assertEquals(List.of("arn:aws:iam::000000000000:user/team/bob",
                        "arn:aws:iam::000000000000:user/team/robert"),
                iam(b, "Action=UpdateUser&UserName=bob&NewUserName=robert"));
        assertEquals(List.of("arn:aws:iam::000000000000:group/team/admins",
                        "arn:aws:iam::000000000000:group/ops/admins"),
                iam(b, "Action=UpdateGroup&GroupName=admins&NewPath=/ops/"));
        assertEquals(List.of("arn:aws:iam::000000000000:user/team/bob"),
                iam(b, "Action=UpdateUser&UserName=bob"));
    }

    /**
     * Where UserName is optional it defaults to the signing user, and so does the resource: a
     * self-service statement naming the caller's own ARN has to see the caller, not {@code *}.
     */
    @Test
    void iamNamesTheCallerWhenAnOptionalUserNameIsLeftOut() {
        ResourceArnBuilder b = storeBuilder(entityStore());
        signedBy("AKIABOB");
        for (String action : List.of("GetUser", "CreateAccessKey", "ListAccessKeys",
                "ListSSHPublicKeys", "ListSigningCertificates", "ListMFADevices",
                "ListServiceSpecificCredentials")) {
            assertEquals(List.of("arn:aws:iam::000000000000:user/team/bob"),
                    iam(b, "Action=" + action), action);
        }
        // AllUsers lists everyone's credentials, so it is not about the caller.
        assertEquals(List.of("*"), iam(b, "Action=ListServiceSpecificCredentials&AllUsers=true"));
        // A required UserName does not default: DeleteUser without one names nobody.
        assertEquals(List.of("*"), iam(b, "Action=DeleteUser"));
        // A caller that is not a stored user, such as an assumed-role session, has no user ARN.
        signedBy("ASIASESSION");
        assertEquals(List.of("*"), iam(b, "Action=GetUser"));
    }

    @Test
    void iamNamesTheOwnerOfAnAccessKeyForItsLastUsedLookup() {
        assertEquals(List.of("arn:aws:iam::000000000000:user/team/bob"),
                iam(storeBuilder(entityStore()), "Action=GetAccessKeyLastUsed&AccessKeyId=AKIABOB"));
    }

    /** Account-wide operations have no resource type in the reference, so they stay at {@code *}. */
    @Test
    void iamKeepsTheWildcardForAccountWideOperations() {
        ResourceArnBuilder b = storeBuilder(entityStore());
        for (String action : List.of("ListUsers", "ListRoles", "ListGroups", "ListPolicies",
                "GetAccountSummary", "GetAccountAuthorizationDetails", "CreateAccountAlias",
                "UpdateAccountPasswordPolicy", "GenerateCredentialReport", "ListSAMLProviders")) {
            assertEquals(List.of("*"), iam(b, "Action=" + action), action);
        }
    }

    private void setJsonBody(String json) {
        contextProperties.clear();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        when(ctx.getEntityStream()).thenReturn(in);
        doAnswer(inv -> {
            InputStream newIn = inv.getArgument(0);
            when(ctx.getEntityStream()).thenReturn(newIn);
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
    }

    // ── DynamoDB ─────────────────────────────────────────────────────────────────

    @Test
    void dynamoDbBuildsArnFromShortTableName() {
        setJsonBody("{\"TableName\":\"FgacTable\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable", arn);
    }

    /**
     * Pass-through used to be a {@code startsWith("arn:aws:dynamodb:")} probe, so a table ARN from
     * any other partition was not recognised as an ARN at all and got rebuilt as
     * {@code table/arn:aws-cn:dynamodb:...}, a resource name that matches no policy.
     */
    @Test
    void dynamoDbReturnsExactArnForAnyPartition() {
        for (String fullArn : List.of(
                "arn:aws-us-gov:dynamodb:us-gov-west-1:000000000000:table/FgacTable",
                "arn:aws-cn:dynamodb:cn-north-1:000000000000:table/FgacTable")) {
            setJsonBody("{\"TableName\":\"" + fullArn + "\"}");
            assertEquals(fullArn, builder.build("dynamodb", ctx, "us-east-1", "000000000000"));
        }
    }

    /** A bare name that merely looks ARN-ish is still a name, not an ARN. */
    @Test
    void dynamoDbTreatsAnIncompleteArnAsATableName() {
        setJsonBody("{\"TableName\":\"arn:aws:dynamodb:us-east-1\"}");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/arn:aws:dynamodb:us-east-1",
                builder.build("dynamodb", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void dynamoDbReturnsExactArnIfTableNameIsAlreadyArn() {
        String fullArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        setJsonBody("{\"TableName\":\"" + fullArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(fullArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromResourceArn() {
        String tagArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        setJsonBody("{\"ResourceArn\":\"" + tagArn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(tagArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromTableArn() {
        String exportArn = "arn:aws:dynamodb:us-east-1:000000000000:table/ExportTable";
        setJsonBody("{\"TableArn\":\"" + exportArn + "\",\"S3Bucket\":\"my-bucket\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(exportArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromStreamArn() {
        String streamArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable/stream/2026-09-01T00:00:00.000";
        setJsonBody("{\"StreamArn\":\"" + streamArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(streamArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromExportArn() {
        String exportArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable/export/01693526400000-abcd";
        setJsonBody("{\"ExportArn\":\"" + exportArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(exportArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromSingleTableBatchRequest() {
        setJsonBody("{\"RequestItems\":{\"MyTable\":{\"Keys\":[]}}}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/MyTable", arn);
    }

    @Test
    void dynamoDbBuildsArnsFromMultiTableBatchRequest() {
        setJsonBody("{\"RequestItems\":{\"TableA\":{\"Keys\":[]},\"TableB\":{\"Keys\":[]}}}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromSingleTableTransactRequest() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"MyTable\"}},{\"Delete\":{\"TableName\":\"MyTable\"}}]}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/MyTable", arn);
    }

    @Test
    void dynamoDbBuildsArnsFromMultiTableTransactRequest() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"TableA\"}},{\"Delete\":{\"TableName\":\"TableB\"}},{\"Get\":{\"TableName\":\"TableA\"}}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromPartiQLStatement() {
        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"UsersTable\\\" WHERE id = '1'\"}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/UsersTable"), arns);

        setJsonBody("{\"Statement\":\"INSERT INTO OrdersTable VALUE {'id': '1'}\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/OrdersTable"), arns);

        setJsonBody("{\"Statement\":\"UPDATE \\\"ProductsTable\\\" SET price=10 WHERE id='1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/ProductsTable"), arns);

        setJsonBody("{\"Statement\":\"DELETE FROM \\\"LogsTable\\\" WHERE id='1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/LogsTable"), arns);

        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"Table With Spaces & Special:Chars\\\" WHERE id = '1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Table With Spaces & Special:Chars"), arns);

        setJsonBody("{\"Statement\":\"SELECT * FROM 'Table.With.Single.Quotes#1' WHERE id = '1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Table.With.Single.Quotes#1"), arns);

        setJsonBody("{\"Statement\":\"SELECT \\\"FROM TableEvil\\\" FROM \\\"TableA\\\" WHERE pk = 1\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/TableA"), arns);
    }

    @Test
    void dynamoDbBuildsArnsFromPartiQLBatchStatements() {
        setJsonBody("{\"Statements\":[{\"Statement\":\"SELECT * FROM \\\"TableA\\\" WHERE id = '1'\"},{\"Statement\":\"SELECT * FROM TableB WHERE id = '2'\"}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnsFromPartiQLTransactStatements() {
        setJsonBody("{\"TransactStatements\":[{\"Statement\":\"INSERT INTO \\\"TableA\\\" VALUE {'id': '1'}\"},{\"Statement\":\"UPDATE \\\"TableB\\\" SET x=1 WHERE id='2'\"}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromTableCreationParameters() {
        setJsonBody("{\"TableCreationParameters\":{\"TableName\":\"ImportTarget\"},\"InputFormat\":\"DYNAMODB_JSON\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/ImportTarget", arn);
    }

    @Test
    void dynamoDbReturnsWildcardWhenNoTableSpecified() {
        setJsonBody("{}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("*", arn);
    }

    @Test
    void entityStreamRemainsReadableAfterResourceArnBuilding() throws IOException {
        String json = "{\"TableName\":\"FgacTable\",\"Item\":{\"PK\":{\"S\":\"USER_1\"}}}";
        setJsonBody(json);

        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable", arn);

        InputStream entityStream = ctx.getEntityStream();
        assertNotNull(entityStream);
        byte[] readBack = entityStream.readAllBytes();
        assertEquals(json, new String(readBack, StandardCharsets.UTF_8));
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────

    @Test
    void kinesisBuildsArnFromStreamName() {
        setJsonBody("{\"StreamName\":\"order-events\"}");
        String arn = builder.build("kinesis", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:kinesis:us-east-1:000000000000:stream/order-events", arn);
    }

    @Test
    void kinesisBuildsArnFromStreamArn() {
        String fullArn = "arn:aws:kinesis:us-east-1:000000000000:stream/order-events";
        setJsonBody("{\"StreamARN\":\"" + fullArn + "\"}");
        String arn = builder.build("kinesis", ctx, "us-east-1", "000000000000");
        assertEquals(fullArn, arn);
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────

    @Test
    void secretsManagerBuildsArnFromSecretId() {
        setJsonBody("{\"SecretId\":\"app/prod/database\"}");
        String arn = builder.build("secretsmanager", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/prod/database", arn);
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────

    @Test
    void ssmBuildsArnFromNameWithLeadingSlash() {
        setJsonBody("{\"Name\":\"/config/env\"}");
        String arn = builder.build("ssm", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/config/env", arn);
    }

    @Test
    void ssmBuildsArnFromNameWithoutLeadingSlash() {
        setJsonBody("{\"Name\":\"config-key\"}");
        String arn = builder.build("ssm", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/config-key", arn);
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────

    @Test
    void sqsBuildsArnFromJsonQueueName() {
        setJsonBody("{\"QueueName\":\"order-queue\"}");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:order-queue", arn);
    }

    @Test
    void sqsBuildsArnFromQueryParam() {
        queryParams.putSingle("QueueUrl", "http://localhost:4566/000000000000/my-queue");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:my-queue", arn);
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────

    @Test
    void snsBuildsArnFromJsonTopicArn() {
        String topicArn = "arn:aws:sns:us-east-1:000000000000:alerts";
        setJsonBody("{\"TopicArn\":\"" + topicArn + "\"}");
        String arn = builder.build("sns", ctx, "us-east-1", "000000000000");
        assertEquals(topicArn, arn);
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────

    @Test
    void s3BuildsBucketArn() {
        when(uriInfo.getPath()).thenReturn("/my-bucket");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket", arn);
    }

    @Test
    void s3BuildsArnsInTheRequestRegionsPartition() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/key.txt");
        assertEquals("arn:aws-cn:s3:::my-bucket/key.txt",
                builder.build("s3", ctx, "cn-north-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/");
        assertEquals("arn:aws-us-gov:s3:::*", builder.build("s3", ctx, "us-gov-west-1", "000000000000"));
    }

    @Test
    void s3BuildsObjectArn() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/folder/file.json");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket/folder/file.json", arn);
    }

    /**
     * S3VirtualHostFilter rewrites a virtual-hosted bucket-level request (GET /) to
     * /bucket/. The resource is still the bucket, so the ARN must carry no trailing
     * slash or a policy naming arn:aws:s3:::bucket stops matching and an allowed
     * ListBucket is denied for every SDK that defaults to virtual-hosted addressing.
     */
    @Test
    void s3BuildsBucketArnForVirtualHostedRewrittenPath() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket", arn);
    }

    @Test
    void s3KeepsTrailingSlashOfAFolderMarkerKey() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/folder/");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket/folder/", arn);
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────

    @Test
    void lambdaBuildsFunctionArn() {
        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/my-function/invocations");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function", arn);
    }

    @Test
    void lambdaDurableExecutionRoutesNameTheExecution() {
        when(uriInfo.getPath()).thenReturn("/2025-12-01/durable-executions/arn:aws:lambda:us-east-1:000000000000:"
                + "function:my-function:$LATEST/durable-execution/run-1/0b1c/stop");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function:$LATEST/durable-execution/run-1/0b1c",
                arn);
    }

    @Test
    void lambdaDurableCallbackRoutesNameTheExecutionInTheCallbackId() {
        String executionArn = "arn:aws:lambda:us-east-1:000000000000:function:my-function:1/durable-execution/run-1/0b1c";
        String callbackId = Base64.getEncoder().encodeToString((executionArn + "|c1|nonce").getBytes(StandardCharsets.UTF_8));
        when(uriInfo.getPath()).thenReturn("/2025-12-01/durable-execution-callbacks/" + callbackId + "/succeed");
        assertEquals(executionArn, builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn("/2025-12-01/durable-execution-callbacks/not-a-callback/heartbeat");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaDurableExecutionGetNamesTheExecution() {
        when(uriInfo.getPath()).thenReturn("/2025-12-01/durable-executions/arn:aws:lambda:us-east-1:000000000000:"
                + "function:my-function:1/durable-execution/run-1/0b1c");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function:1/durable-execution/run-1/0b1c", arn);
    }
}
