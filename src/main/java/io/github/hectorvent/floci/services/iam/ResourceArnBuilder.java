package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsQueryServiceResolver;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.durable.DurableTokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * Constructs the target resource ARN for a request so the policy evaluator
 * can match it against Resource patterns in policy documents.
 *
 * Returns {@code *} when the resource cannot be determined, which matches
 * permissive wildcard policies.
 */
@ApplicationScoped
public class ResourceArnBuilder {

    /** The server-certificate operations, which name their certificate by ServerCertificateName. */
    private static final Set<String> SERVER_CERTIFICATE_ACTIONS = Set.of(
            "UploadServerCertificate", "GetServerCertificate", "UpdateServerCertificate",
            "DeleteServerCertificate", "TagServerCertificate", "UntagServerCertificate",
            "ListServerCertificateTags");

    /**
     * Operations whose Service Authorization Reference resource is the user named by UserName.
     * The MFA enable, deactivate and resync operations are among them: their resource is the user
     * the device is bound to, not the device.
     */
    private static final Set<String> USER_ACTIONS = Set.of(
            "CreateUser", "GetUser", "DeleteUser", "UpdateUser",
            "TagUser", "UntagUser", "ListUserTags",
            "CreateLoginProfile", "GetLoginProfile", "UpdateLoginProfile", "DeleteLoginProfile",
            "CreateAccessKey", "DeleteAccessKey", "ListAccessKeys", "UpdateAccessKey",
            "AttachUserPolicy", "DetachUserPolicy", "ListAttachedUserPolicies",
            "PutUserPolicy", "GetUserPolicy", "DeleteUserPolicy", "ListUserPolicies",
            "PutUserPermissionsBoundary", "DeleteUserPermissionsBoundary",
            "ListGroupsForUser",
            "UploadSSHPublicKey", "GetSSHPublicKey", "ListSSHPublicKeys",
            "UpdateSSHPublicKey", "DeleteSSHPublicKey",
            "UploadSigningCertificate", "ListSigningCertificates",
            "UpdateSigningCertificate", "DeleteSigningCertificate",
            "CreateServiceSpecificCredential", "ListServiceSpecificCredentials",
            "UpdateServiceSpecificCredential", "ResetServiceSpecificCredential",
            "DeleteServiceSpecificCredential",
            "EnableMFADevice", "DeactivateMFADevice", "ResyncMFADevice", "ListMFADevices");

    /**
     * The user operations whose UserName the model marks optional, defaulting to the user that
     * signed the request. Without it, the resource is that caller rather than {@code *}, which is
     * what lets a self-service statement naming the caller's own user ARN grant them.
     */
    private static final Set<String> CALLER_DEFAULT_USER_ACTIONS = Set.of(
            "GetUser",
            "CreateLoginProfile", "GetLoginProfile", "UpdateLoginProfile", "DeleteLoginProfile",
            "CreateAccessKey", "DeleteAccessKey", "ListAccessKeys", "UpdateAccessKey",
            "ListSSHPublicKeys",
            "UploadSigningCertificate", "ListSigningCertificates",
            "UpdateSigningCertificate", "DeleteSigningCertificate",
            "ListServiceSpecificCredentials", "UpdateServiceSpecificCredential",
            "ResetServiceSpecificCredential", "DeleteServiceSpecificCredential",
            "DeactivateMFADevice", "ListMFADevices");

    /** Operations whose resource is the group named by GroupName, membership changes included. */
    private static final Set<String> GROUP_ACTIONS = Set.of(
            "CreateGroup", "GetGroup", "UpdateGroup", "DeleteGroup",
            "AddUserToGroup", "RemoveUserFromGroup",
            "AttachGroupPolicy", "DetachGroupPolicy", "ListAttachedGroupPolicies",
            "PutGroupPolicy", "GetGroupPolicy", "DeleteGroupPolicy", "ListGroupPolicies");

    /** Operations whose resource is the role named by RoleName. */
    private static final Set<String> ROLE_ACTIONS = Set.of(
            "CreateRole", "GetRole", "DeleteRole", "UpdateRole", "UpdateAssumeRolePolicy",
            "TagRole", "UntagRole", "ListRoleTags",
            "AttachRolePolicy", "DetachRolePolicy", "ListAttachedRolePolicies",
            "PutRolePolicy", "GetRolePolicy", "DeleteRolePolicy", "ListRolePolicies",
            "PutRolePermissionsBoundary", "DeleteRolePermissionsBoundary",
            "ListInstanceProfilesForRole", "DeleteServiceLinkedRole");

    /** Operations whose resource is the instance profile named by InstanceProfileName. */
    private static final Set<String> INSTANCE_PROFILE_ACTIONS = Set.of(
            "CreateInstanceProfile", "GetInstanceProfile", "DeleteInstanceProfile",
            "TagInstanceProfile", "UntagInstanceProfile", "ListInstanceProfileTags",
            "AddRoleToInstanceProfile", "RemoveRoleFromInstanceProfile");

    /**
     * Operations that already carry their resource as an ARN, keyed to the parameter holding it.
     * The ARN is passed through rather than rebuilt: it is what the operation acts on.
     */
    private static final Map<String, String> ARN_PARAMETER_ACTIONS = arnParameterActions();

    private static Map<String, String> arnParameterActions() {
        Map<String, String> actions = new HashMap<>();
        for (String action : List.of("GetPolicy", "DeletePolicy", "ListEntitiesForPolicy",
                "CreatePolicyVersion", "GetPolicyVersion", "DeletePolicyVersion",
                "ListPolicyVersions", "SetDefaultPolicyVersion",
                "TagPolicy", "UntagPolicy", "ListPolicyTags")) {
            actions.put(action, "PolicyArn");
        }
        for (String action : List.of("DeleteVirtualMFADevice",
                "TagMFADevice", "UntagMFADevice", "ListMFADeviceTags")) {
            actions.put(action, "SerialNumber");
        }
        for (String action : List.of("GetSAMLProvider", "UpdateSAMLProvider", "DeleteSAMLProvider",
                "TagSAMLProvider", "UntagSAMLProvider", "ListSAMLProviderTags")) {
            actions.put(action, "SAMLProviderArn");
        }
        for (String action : List.of("GetOpenIDConnectProvider", "DeleteOpenIDConnectProvider",
                "AddClientIDToOpenIDConnectProvider", "RemoveClientIDFromOpenIDConnectProvider",
                "UpdateOpenIDConnectProviderThumbprint", "TagOpenIDConnectProvider",
                "UntagOpenIDConnectProvider", "ListOpenIDConnectProviderTags")) {
            actions.put(action, "OpenIDConnectProviderArn");
        }
        // The principal whose policies are simulated or whose access is reported.
        actions.put("SimulatePrincipalPolicy", "PolicySourceArn");
        actions.put("GetContextKeysForPrincipalPolicy", "PolicySourceArn");
        actions.put("GenerateServiceLastAccessedDetails", "Arn");
        actions.put("ListPoliciesGrantingServiceAccess", "Arn");
        return Map.copyOf(actions);
    }

    private final ObjectMapper objectMapper;
    /**
     * Looked up lazily through a provider: this builder is constructed by the enforcement filter,
     * which IamService itself does not depend on, and a direct injection would close that loop.
     */
    private final Instance<IamService> iamService;
    /** Reads the signing access key, to name the caller where UserName defaults to it. */
    private final AccountResolver accountResolver;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService,
                              AccountResolver accountResolver) {
        this.objectMapper = objectMapper;
        this.iamService = iamService;
        this.accountResolver = accountResolver;
    }

    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService) {
        this(objectMapper, iamService, null);
    }

    public ResourceArnBuilder() {
        this(new ObjectMapper(), null, null);
    }

    public ResourceArnBuilder(ObjectMapper objectMapper) {
        this(objectMapper, null, null);
    }

    public String build(String credentialScope, ContainerRequestContext ctx,
                        String region, String accountId) {
        List<String> list = buildResources(credentialScope, ctx, region, accountId);
        return list.isEmpty() ? "*" : list.getFirst();
    }

    public List<String> buildResources(String credentialScope, ContainerRequestContext ctx,
                                       String region, String accountId) {
        if (credentialScope == null) {
            return List.of("*");
        }
        String path = ctx.getUriInfo().getPath();
        return switch (credentialScope) {
            case "s3"             -> List.of(buildS3Arn(path, region));
            case "lambda"         -> List.of(buildLambdaArn(path, region, accountId));
            case "sqs"            -> List.of(buildSqsArn(ctx, region, accountId));
            case "sns"            -> List.of(buildSnsArn(ctx, region, accountId));
            case "dynamodb"       -> buildDynamoDbArns(ctx, region, accountId);
            case "kinesis"        -> List.of(buildKinesisArn(ctx, region, accountId));
            case "secretsmanager" -> List.of(buildSecretsManagerArn(ctx, region, accountId));
            case "ssm"            -> List.of(buildSsmArn(ctx, region, accountId));
            case "kms"            -> List.of(buildKmsArn(path, region, accountId));
            case "iam"            -> buildIamArns(ctx, region, accountId);
            default               -> List.of("*");
        };
    }

    // ── IAM ─────────────────────────────────────────────────────────────────────

    /**
     * The resource of each IAM action, as the Service Authorization Reference lists it: the user,
     * group, role, instance profile, managed policy, MFA device, identity provider or server
     * certificate the request acts on. Actions the reference lists without a resource type (the
     * account-wide reads, aliases, password policy, credential report and the like) resolve to
     * {@code *}, as on AWS.
     *
     * <p>The operation is resolved through {@link AwsQueryServiceResolver#action(String, String)},
     * so the legacy {@code Operation} parameter names the resource exactly as {@code Action} does.
     * Reading {@code Action} alone would let a caller spell the operation the other way and have
     * the request evaluated against {@code *}, skipping a deny that names the resource.
     *
     * <p>An entity that already exists is checked against its own stored ARN, because a policy
     * names the full ARN, path included, and only the store knows the path and the partition the
     * entity was created in. A create mints the ARN from the request instead. An entity that does
     * not exist resolves to {@code *}: the operation fails as NoSuchEntity either way.
     *
     * <p>A rename names two resources rather than one; see {@link #renameTargetArn}.
     */
    private List<String> buildIamArns(ContainerRequestContext ctx, String region,
                                      String accountId) {
        String action = AwsQueryServiceResolver.action(
                RequestBodyReader.formField(ctx, "Action"),
                RequestBodyReader.formField(ctx, "Operation"));
        if (action == null) {
            return List.of("*");
        }
        if (SERVER_CERTIFICATE_ACTIONS.contains(action)) {
            return buildServerCertificateArns(ctx, action, region, accountId);
        }
        if (USER_ACTIONS.contains(action)) {
            return buildUserArns(ctx, action, region, accountId);
        }
        if (GROUP_ACTIONS.contains(action)) {
            return buildNamedEntityArns(ctx, action, "CreateGroup", "UpdateGroup", "group",
                    "GroupName", "NewGroupName", region, accountId,
                    name -> lookup(service -> service.findGroup(accountId, name)
                            .map(g -> new StoredEntity(g.getArn(), g.getPath()))));
        }
        if (ROLE_ACTIONS.contains(action)) {
            return buildNamedEntityArns(ctx, action, "CreateRole", null, "role",
                    "RoleName", null, region, accountId,
                    name -> lookup(service -> service.findRole(accountId, name)
                            .map(r -> new StoredEntity(r.getArn(), r.getPath()))));
        }
        if (INSTANCE_PROFILE_ACTIONS.contains(action)) {
            return buildNamedEntityArns(ctx, action, "CreateInstanceProfile", null, "instance-profile",
                    "InstanceProfileName", null, region, accountId,
                    name -> lookup(service -> service.findInstanceProfile(accountId, name)
                            .map(p -> new StoredEntity(p.getArn(), p.getPath()))));
        }
        String arnParameter = ARN_PARAMETER_ACTIONS.get(action);
        if (arnParameter != null) {
            return List.of(iamArnParameter(ctx, arnParameter));
        }
        return switch (action) {
            case "CreatePolicy" -> List.of(mintFromRequest(ctx, "policy", "PolicyName", region, accountId));
            case "CreateVirtualMFADevice" ->
                    List.of(mintFromRequest(ctx, "mfa", "VirtualMFADeviceName", region, accountId));
            case "CreateSAMLProvider" -> List.of(mintRootPath(
                    "saml-provider", RequestBodyReader.formField(ctx, "Name"), region, accountId));
            case "CreateOpenIDConnectProvider" -> List.of(oidcProviderArn(ctx, region, accountId));
            case "CreateServiceLinkedRole" -> List.of(serviceLinkedRoleArn(ctx, region, accountId));
            case "GetAccessKeyLastUsed" -> List.of(accessKeyOwnerArn(
                    RequestBodyReader.formField(ctx, "AccessKeyId"), accountId));
            default -> List.of("*");
        };
    }

    /** The parts of a stored IAM entity the permission check reads. */
    private record StoredEntity(String arn, String path) {}

    private Optional<StoredEntity> lookup(
            Function<IamService, Optional<StoredEntity>> finder) {
        if (iamService == null) {
            return Optional.empty();
        }
        return finder.apply(iamService.get())
                .filter(entity -> entity.arn() != null && !entity.arn().isBlank());
    }

    /**
     * The user operations. Mostly a named entity like the others, except that several operations
     * default UserName to the caller; see {@link #CALLER_DEFAULT_USER_ACTIONS}.
     */
    private List<String> buildUserArns(ContainerRequestContext ctx, String action,
                                       String region, String accountId) {
        String userName = RequestBodyReader.formField(ctx, "UserName");
        if ((userName == null || userName.isBlank()) && CALLER_DEFAULT_USER_ACTIONS.contains(action)) {
            // AllUsers lists every user's credentials rather than the caller's, so there is no one
            // user it acts on.
            if ("ListServiceSpecificCredentials".equals(action)
                    && "true".equalsIgnoreCase(RequestBodyReader.formField(ctx, "AllUsers"))) {
                return List.of("*");
            }
            return List.of(callerUserArn(ctx, accountId));
        }
        return buildNamedEntityArns(ctx, action, "CreateUser", "UpdateUser", "user",
                "UserName", "NewUserName", region, accountId,
                name -> lookup(service -> service.findUser(accountId, name)
                        .map(u -> new StoredEntity(u.getArn(), u.getPath()))));
    }

    /**
     * A user, group, role or instance profile named by its bare name. The create operation mints
     * the ARN from the request's own Path, every other operation reads the stored entity's ARN,
     * and the rename operation names both the current and the destination ARN.
     */
    private List<String> buildNamedEntityArns(ContainerRequestContext ctx, String action,
                                              String createAction, String renameAction,
                                              String resourceType, String nameParameter,
                                              String newNameParameter, String region,
                                              String accountId,
                                              Function<String, Optional<StoredEntity>> finder) {
        String name = RequestBodyReader.formField(ctx, nameParameter);
        if (name == null || name.isBlank()) {
            return List.of("*");
        }
        if (action.equals(createAction)) {
            // A create names an entity that does not exist yet, so its ARN comes from the request:
            // the path it asks for, in the partition it is being created in. Reading a stored path
            // here would authorize a create under /team/ against the root path.
            return List.of(mintWithPath(resourceType, RequestBodyReader.formField(ctx, "Path"),
                    name, region, accountId));
        }
        Optional<StoredEntity> stored = finder.apply(name);
        if (stored.isEmpty()) {
            return List.of("*");
        }
        String storedArn = stored.get().arn();
        if (!action.equals(renameAction)) {
            return List.of(storedArn);
        }
        String target = renameTargetArn(storedArn, stored.get().path(), resourceType, name,
                RequestBodyReader.formField(ctx, newNameParameter),
                RequestBodyReader.formField(ctx, "NewPath"), region, accountId);
        return storedArn.equals(target) ? List.of(storedArn) : List.of(storedArn, target);
    }

    /**
     * The caller's own user ARN, for an operation whose UserName defaults to it. A caller that is
     * not an IAM user (an assumed-role session, the account root) has no user to name, and the
     * operation fails for it either way.
     */
    private String callerUserArn(ContainerRequestContext ctx, String accountId) {
        if (accountResolver == null) {
            return "*";
        }
        String accessKeyId = accountResolver.extractAccessKeyId(ctx.getHeaderString("Authorization"));
        return accessKeyOwnerArn(accessKeyId, accountId);
    }

    /** The ARN of the user owning an access key, or {@code *} when no stored user owns it. */
    private String accessKeyOwnerArn(String accessKeyId, String accountId) {
        if (accessKeyId == null || accessKeyId.isBlank() || iamService == null) {
            return "*";
        }
        return iamService.get().findUserNameByAccessKeyId(accessKeyId)
                .flatMap(userName -> lookup(service -> service.findUser(accountId, userName)
                        .map(u -> new StoredEntity(u.getArn(), u.getPath()))))
                .map(StoredEntity::arn)
                .orElse("*");
    }

    /**
     * An ARN the request carries. Passed through as written when it is an IAM ARN; anything else
     * names no IAM resource, and the operation fails on it regardless.
     */
    private static String iamArnParameter(ContainerRequestContext ctx, String parameter) {
        String value = RequestBodyReader.formField(ctx, parameter);
        if (value == null || value.isBlank() || !AwsArnUtils.isArnFor(value.trim(), "iam")) {
            return "*";
        }
        return value.trim();
    }

    /** A created resource's ARN, from its name parameter and the request's Path. */
    private static String mintFromRequest(ContainerRequestContext ctx, String resourceType,
                                          String nameParameter, String region, String accountId) {
        String name = RequestBodyReader.formField(ctx, nameParameter);
        if (name == null || name.isBlank()) {
            return "*";
        }
        return mintWithPath(resourceType, RequestBodyReader.formField(ctx, "Path"), name,
                region, accountId);
    }

    /**
     * IAM is global, so the ARN carries no region. Minted in the request's partition rather than
     * through a blank-region Arn.of, which would silently mean the commercial one.
     */
    private static String mintWithPath(String resourceType, String path, String name,
                                       String region, String accountId) {
        return AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", accountId,
                resourceType + normalizeArnPath(path) + name).toString();
    }

    /** A resource type that takes no path, such as a SAML provider. */
    private static String mintRootPath(String resourceType, String name, String region,
                                       String accountId) {
        if (name == null || name.isBlank()) {
            return "*";
        }
        return AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", accountId,
                resourceType + "/" + name).toString();
    }

    /** An OIDC provider is named by its URL without the scheme, as the service mints it. */
    private static String oidcProviderArn(ContainerRequestContext ctx, String region, String accountId) {
        String url = RequestBodyReader.formField(ctx, "Url");
        if (url == null || !url.startsWith("https://")) {
            return "*";
        }
        return mintRootPath("oidc-provider", url.substring("https://".length()), region, accountId);
    }

    /**
     * A service-linked role sits under {@code /aws-service-role/<principal>/}, named from the
     * principal plus any CustomSuffix, which is how a policy scoping
     * {@code iam:CreateServiceLinkedRole} to one service's roles names it.
     */
    private static String serviceLinkedRoleArn(ContainerRequestContext ctx, String region,
                                               String accountId) {
        String awsServiceName = RequestBodyReader.formField(ctx, "AWSServiceName");
        if (awsServiceName == null || awsServiceName.isBlank()) {
            return "*";
        }
        Optional<String> baseName = ServiceLinkedRoles.roleName(awsServiceName);
        if (baseName.isEmpty()) {
            return "*";
        }
        String suffix = RequestBodyReader.formField(ctx, "CustomSuffix");
        String roleName = baseName.get() + (suffix == null || suffix.isEmpty() ? "" : "_" + suffix);
        return mintWithPath("role", "/aws-service-role/"
                + ServicePrincipals.canonical(awsServiceName) + "/", roleName, region, accountId);
    }

    private List<String> buildServerCertificateArns(ContainerRequestContext ctx, String action,
                                                    String region, String accountId) {
        String name = RequestBodyReader.formField(ctx, "ServerCertificateName");
        if (name == null || name.isBlank()) {
            return List.of("*");
        }
        if ("UploadServerCertificate".equals(action)) {
            // An upload names a certificate that does not exist yet, so its ARN is minted from
            // the request: the path it asks for, in the partition it is being created in. Reading
            // a stored path here would authorize an upload to /team/ against the root path.
            // IAM is global, so the ARN carries no region. Minted in the request's partition
            // rather than through a blank-region Arn.of, which would silently mean the
            // commercial one.
            String path = normalizeArnPath(RequestBodyReader.formField(ctx, "Path"));
            return List.of(AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam",
                    accountId, "server-certificate" + path + name).toString());
        }
        // Every other operation acts on a certificate that already exists, so the check uses that
        // certificate's own stored ARN instead of deriving one again. The stored ARN carries the
        // path and, more to the point, the partition the certificate was created in: a resource
        // stays in its partition, so re-minting from the caller's signing region would name an
        // ARN that no resource has and leave a deny on the real one unmatched.
        Optional<ServerCertificate> stored = storedServerCertificate(name);
        if (stored.isEmpty()) {
            return List.of("*");
        }
        String storedArn = stored.get().getArn();
        if (!"UpdateServerCertificate".equals(action)) {
            return List.of(storedArn);
        }
        String target = renameTargetArn(storedArn, stored.get().getPath(), "server-certificate",
                name, RequestBodyReader.formField(ctx, "NewServerCertificateName"),
                RequestBodyReader.formField(ctx, "NewPath"), region, accountId);
        return storedArn.equals(target) ? List.of(storedArn) : List.of(storedArn, target);
    }

    /**
     * The ARN a rename or a move would produce. AWS requires the principal to be allowed on both
     * the old and the new name, so that a rename into a name they cannot write fails: naming both
     * resources is what enforces it, because the filter authorizes a request once per resource.
     *
     * <p>Built beside the stored ARN so it keeps the entity's partition and account. Only the
     * path and the name change, and taking the partition from the caller's signing region instead
     * would name a resource in a partition the entity does not live in. Returns the stored ARN
     * unchanged when the request renames and moves nothing.
     */
    private static String renameTargetArn(String storedArn, String storedPath, String resourceType,
                                          String name, String newName, String newPath,
                                          String region, String accountId) {
        String targetName = newName == null || newName.isBlank() ? name : newName;
        String targetPath = newPath == null || newPath.isBlank()
                ? normalizeArnPath(storedPath)
                : normalizeArnPath(newPath);
        String partition = AwsArnUtils.partitionOrDefault(storedArn, AwsRegions.partitionFor(region));
        String account = AwsArnUtils.accountOrDefault(storedArn, accountId);
        return AwsArnUtils.Arn.global(partition, "iam", account,
                resourceType + targetPath + targetName).toString();
    }

    /** A request's Path as it appears in an ARN: slash-delimited, defaulting to a bare slash. */
    private static String normalizeArnPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    /**
     * The stored certificate of that name in this account, carrying its own ARN. Empty when the
     * account has none, or when the stored record has no ARN to read: the operation fails as
     * NoSuchEntity either way, and minting an ARN for a resource that is not there would only
     * offer a policy something to match.
     */
    private Optional<ServerCertificate> storedServerCertificate(String name) {
        if (iamService == null) {
            return Optional.empty();
        }
        return iamService.get().findServerCertificate(name)
                .filter(certificate -> certificate.getArn() != null
                        && !certificate.getArn().isBlank());
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────
    // Minted in the request's partition; IamEnforcementFilter re-mints an existing bucket's ARN
    // in the bucket's own partition through S3ResourcePolicyProvider before any policy sees it.
    private String buildS3Arn(String path, String region) {
        // path: /bucket or /bucket/key
        String partition = AwsRegions.partitionFor(region);
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return AwsArnUtils.Arn.global(partition, "s3", "", "*").toString();
        }
        // S3VirtualHostFilter rewrites a bucket-level virtual-hosted request (GET /) to
        // /bucket/, and the empty key after that separator is not part of the resource.
        // A policy names the bucket as arn:<partition>:s3:::bucket, so the trailing slash has to
        // go or a bucket-level ARN never matches. A key that itself ends in a slash
        // (a folder marker such as folder/) keeps it, because there the slash is key data.
        int firstSlash = stripped.indexOf('/');
        if (firstSlash == stripped.length() - 1) {
            stripped = stripped.substring(0, firstSlash);
        }
        return AwsArnUtils.Arn.global(partition, "s3", "", stripped).toString();
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────
    private String buildLambdaArn(String path, String region, String accountId) {
        String executionArn = durableExecutionArn(path);
        if (executionArn == null) {
            executionArn = durableCallbackExecutionArn(path);
        }
        if (executionArn != null) {
            return executionArn;
        }
        // path: /2015-03-31/functions/name or similar
        String name = extractSegmentAfter(path, "functions");
        if (name == null) return "*";
        // strip qualifier if present
        int colon = name.indexOf(':');
        if (colon > 0) name = name.substring(0, colon);
        return AwsArnUtils.Arn.of("lambda", region, accountId, "function:" + name).toString();
    }

    /** Durable execution actions are authorized against the execution ARN, which carries the version. */
    private static String durableExecutionArn(String path) {
        String prefix = "/durable-executions/";
        int executions = path.indexOf(prefix);
        if (executions < 0) {
            return null;
        }
        Matcher matcher = LambdaArnUtils.DURABLE_EXECUTION_ARN.matcher(path.substring(executions + prefix.length()));
        return matcher.lookingAt() ? matcher.group() : null;
    }

    /** A callback id carries its execution ARN, so the callback actions are authorized against that execution. */
    private static String durableCallbackExecutionArn(String path) {
        String prefix = "/durable-execution-callbacks/";
        int callbacks = path.indexOf(prefix);
        int action = path.lastIndexOf('/');
        if (callbacks < 0 || action <= callbacks + prefix.length()) {
            return null;
        }
        return DurableTokens.callbackExecutionArn(path.substring(callbacks + prefix.length(), action))
                .filter(arn -> LambdaArnUtils.DURABLE_EXECUTION_ARN.matcher(arn).matches())
                .orElse(null);
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────
    private String buildSqsArn(ContainerRequestContext ctx, String region, String accountId) {
        String queueUrl = ctx.getUriInfo().getQueryParameters().getFirst("QueueUrl");
        if (queueUrl == null) {
            // Try form param for Query-protocol
            queueUrl = firstFormParam(ctx, "QueueUrl");
        }
        if (queueUrl == null) {
            JsonNode json = readJsonBody(ctx);
            if (json != null && json.isObject()) {
                if (json.hasNonNull("QueueUrl")) {
                    queueUrl = json.get("QueueUrl").asText().trim();
                } else if (json.hasNonNull("QueueName")) {
                    String queueName = json.get("QueueName").asText().trim();
                    if (!queueName.isEmpty()) {
                        return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
                    }
                }
            }
        }
        if (queueUrl != null && !queueUrl.isEmpty()) {
            String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
            return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
        }
        return AwsArnUtils.Arn.of("sqs", region, accountId, "*").toString();
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────
    private String buildSnsArn(ContainerRequestContext ctx, String region, String accountId) {
        String topicArn = firstFormParam(ctx, "TopicArn");
        if (topicArn == null) {
            JsonNode json = readJsonBody(ctx);
            if (json != null && json.isObject() && json.hasNonNull("TopicArn")) {
                topicArn = json.get("TopicArn").asText().trim();
            }
        }
        return (topicArn != null && !topicArn.isEmpty())
                ? topicArn
                : AwsArnUtils.Arn.of("sns", region, accountId, "*").toString();
    }

    // ── DynamoDB ─────────────────────────────────────────────────────────────────
    private String buildDynamoDbArn(ContainerRequestContext ctx, String region, String accountId) {
        List<String> arns = buildDynamoDbArns(ctx, region, accountId);
        return arns.isEmpty() ? "*" : arns.getFirst();
    }

    private List<String> buildDynamoDbArns(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("TableName")) {
                String tableName = json.get("TableName").asText().trim();
                if (!tableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(tableName, region, accountId));
                }
            }
            if (json.path("TableCreationParameters").hasNonNull("TableName")) {
                String tableName = json.path("TableCreationParameters").get("TableName").asText().trim();
                if (!tableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(tableName, region, accountId));
                }
            }
            if (json.hasNonNull("ResourceArn")) {
                String resourceArn = json.get("ResourceArn").asText().trim();
                if (!resourceArn.isEmpty()) {
                    return List.of(resourceArn);
                }
            }
            if (json.hasNonNull("TableArn")) {
                String tableArn = json.get("TableArn").asText().trim();
                if (!tableArn.isEmpty()) {
                    return List.of(tableArn);
                }
            }
            if (json.hasNonNull("StreamArn")) {
                String streamArn = json.get("StreamArn").asText().trim();
                if (!streamArn.isEmpty()) {
                    return List.of(streamArn);
                }
            }
            if (json.hasNonNull("ExportArn")) {
                String exportArn = json.get("ExportArn").asText().trim();
                if (!exportArn.isEmpty()) {
                    return List.of(exportArn);
                }
            }
            if (json.hasNonNull("ImportArn")) {
                String importArn = json.get("ImportArn").asText().trim();
                if (!importArn.isEmpty()) {
                    return List.of(importArn);
                }
            }
            if (json.hasNonNull("RequestItems") && json.get("RequestItems").isObject()) {
                Set<String> arns = new LinkedHashSet<>();
                Iterator<String> fieldNames = json.get("RequestItems").fieldNames();
                while (fieldNames.hasNext()) {
                    String table = fieldNames.next().trim();
                    if (!table.isEmpty()) {
                        arns.add(toDynamoDbTableArn(table, region, accountId));
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("TransactItems") && json.get("TransactItems").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                JsonNode items = json.get("TransactItems");
                for (JsonNode item : items) {
                    String t = null;
                    if (item.hasNonNull("Put") && item.get("Put").hasNonNull("TableName")) {
                        t = item.get("Put").get("TableName").asText();
                    } else if (item.hasNonNull("Delete") && item.get("Delete").hasNonNull("TableName")) {
                        t = item.get("Delete").get("TableName").asText();
                    } else if (item.hasNonNull("Update") && item.get("Update").hasNonNull("TableName")) {
                        t = item.get("Update").get("TableName").asText();
                    } else if (item.hasNonNull("ConditionCheck") && item.get("ConditionCheck").hasNonNull("TableName")) {
                        t = item.get("ConditionCheck").get("TableName").asText();
                    } else if (item.hasNonNull("Get") && item.get("Get").hasNonNull("TableName")) {
                        t = item.get("Get").get("TableName").asText();
                    }
                    if (t != null) {
                        t = t.trim();
                        if (!t.isEmpty()) {
                            arns.add(toDynamoDbTableArn(t, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("Statement")) {
                String stmt = json.get("Statement").asText().trim();
                String table = extractDynamoDbTableFromPartiQL(stmt);
                if (table != null && !table.isEmpty()) {
                    return List.of(toDynamoDbTableArn(table, region, accountId));
                }
            }
            if (json.hasNonNull("Statements") && json.get("Statements").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                for (JsonNode s : json.get("Statements")) {
                    if (s.hasNonNull("Statement")) {
                        String table = extractDynamoDbTableFromPartiQL(s.get("Statement").asText());
                        if (table != null && !table.isEmpty()) {
                            arns.add(toDynamoDbTableArn(table, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("TransactStatements") && json.get("TransactStatements").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                for (JsonNode s : json.get("TransactStatements")) {
                    if (s.hasNonNull("Statement")) {
                        String table = extractDynamoDbTableFromPartiQL(s.get("Statement").asText());
                        if (table != null && !table.isEmpty()) {
                            arns.add(toDynamoDbTableArn(table, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
        }
        return List.of("*");
    }

    private static String extractDynamoDbTableFromPartiQL(String statement) {
        return io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.extractTable(statement);
    }

    private String toDynamoDbTableArn(String tableName, String region, String accountId) {
        if (AwsArnUtils.isArnFor(tableName, "dynamodb")) {
            return tableName;
        }
        return AwsArnUtils.Arn.of("dynamodb", region, accountId, "table/" + tableName).toString();
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────
    private String buildKinesisArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("StreamARN")) {
                String streamArn = json.get("StreamARN").asText().trim();
                if (!streamArn.isEmpty()) {
                    return streamArn;
                }
            }
            if (json.hasNonNull("StreamName")) {
                String streamName = json.get("StreamName").asText().trim();
                if (!streamName.isEmpty()) {
                    if (AwsArnUtils.isArnFor(streamName, "kinesis")) {
                        return streamName;
                    }
                    return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/" + streamName).toString();
                }
            }
            if (json.hasNonNull("ResourceARN")) {
                String resourceArn = json.get("ResourceARN").asText().trim();
                if (!resourceArn.isEmpty()) {
                    return resourceArn;
                }
            }
        }
        return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/*").toString();
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────
    private String buildSecretsManagerArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("SecretId")) {
                String secretId = json.get("SecretId").asText().trim();
                if (!secretId.isEmpty()) {
                    if (AwsArnUtils.isArnFor(secretId, "secretsmanager")) {
                        return secretId;
                    }
                    return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:" + secretId).toString();
                }
            }
        }
        return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:*").toString();
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────
    private String buildSsmArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("Name")) {
                String name = json.get("Name").asText().trim();
                if (!name.isEmpty()) {
                    if (AwsArnUtils.isArnFor(name, "ssm")) {
                        return name;
                    }
                    String paramResource = name.startsWith("/") ? "parameter" + name : "parameter/" + name;
                    return AwsArnUtils.Arn.of("ssm", region, accountId, paramResource).toString();
                }
            }
        }
        return AwsArnUtils.Arn.of("ssm", region, accountId, "parameter/*").toString();
    }

    // ── KMS ──────────────────────────────────────────────────────────────────────
    private String buildKmsArn(String path, String region, String accountId) {
        String keyId = extractSegmentAfter(path, "keys");
        if (keyId == null) return AwsArnUtils.Arn.of("kms", region, accountId, "key/*").toString();
        return AwsArnUtils.Arn.of("kms", region, accountId, "key/" + keyId).toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private JsonNode readJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty("floci.bufferedJsonBody");
        if (cached instanceof JsonNode node) {
            return node;
        }
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            ctx.setEntityStream(new ByteArrayInputStream(new byte[0]));
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            ctx.setProperty("floci.bufferedJsonBody", node);
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSegmentAfter(String path, String segment) {
        String marker = "/" + segment + "/";
        int idx = path.indexOf(marker);
        if (idx < 0) return null;
        String after = path.substring(idx + marker.length());
        // take only the first segment (stop at next /)
        int slash = after.indexOf('/');
        return slash > 0 ? after.substring(0, slash) : after;
    }

    private String firstFormParam(ContainerRequestContext ctx, String name) {
        // Form params are typically available as query params in REST-Assured / JAX-RS
        String v = ctx.getUriInfo().getQueryParameters().getFirst(name);
        return v;
    }
}
