package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Control plane of AWS Lambda Web Functions ("Lambda Web", apiVersion 2025-03-07). A Web Function
 * owns immutable revisions and endpoints. Everything converges at once: a revision and an endpoint
 * are stored {@code Active} and an endpoint update is stored {@code Successful}, while the create and
 * update responses report the transitional state AWS reports, so a client that waits on the state
 * sees one poll.
 *
 * <p>Web Functions are delivered as part of the lambda service and share its storage key, mode and
 * flush interval. Invocation through an endpoint's domain is not part of this service.
 */
@ApplicationScoped
public class LambdaWebService implements Resettable {

    private static final Logger LOG = Logger.getLogger(LambdaWebService.class);

    static final String SUPPORTED_RUNTIME = "nodejs24.x";
    static final int MAX_ENDPOINTS_PER_FUNCTION = 10;
    static final int MAX_REVISIONS_PER_FUNCTION = 50;
    static final int ACCOUNT_MAX_ARM_VCPUS = 2000;
    static final int ACCOUNT_MAX_RATE_LIMIT = 100000;
    static final List<Integer> SUPPORTED_RATE_LIMITS = List.of(
            0, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000,
            2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000);
    static final List<String> AUTH_TYPES = List.of("ApplicationManaged", "IamAuth");
    static final List<String> ENDPOINT_TYPES = List.of("HomeRegion", "MultiRegion", "PerRegion");
    static final List<String> AUTO_DEPLOYMENT_MODES = List.of("LatestRevision", "Disabled");
    static final List<String> APPLICATION_LOG_LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");
    static final List<String> SYSTEM_LOG_LEVELS = List.of("DEBUG", "INFO", "WARN");
    static final Set<String> RESERVED_ENVIRONMENT_KEYS = Set.of(
            "AWS_ACCESS_KEY", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SECRET_KEY",
            "AWS_SECURITY_TOKEN", "AWS_SESSION_TOKEN", "AWS_DEFAULT_REGION", "AWS_REGION",
            "AWS_LAMBDA_HTTP_ENDPOINT", "AWS_LAMBDA_RUNTIME_API", "AWS_LAMBDA_INITIALIZATION_TYPE",
            "AWS_LAMBDA_FUNCTION_MEMORY_SIZE", "AWS_LAMBDA_METADATA_API", "AWS_LAMBDA_METADATA_TOKEN");
    /** Condition keys that make a resource policy non-public when constrained to a fixed value. */
    static final Set<String> TRUSTED_CONDITION_KEYS = Set.of(
            "aws:PrincipalAccount", "aws:PrincipalArn", "aws:PrincipalOrgID", "aws:PrincipalOrgPaths",
            "aws:SourceAccount", "aws:SourceArn", "aws:SourceOrgID", "aws:SourceOrgPaths", "aws:SourceVpc",
            "aws:SourceVpcArn", "aws:SourceVpce", "aws:VpceAccount", "aws:VpceOrgID", "aws:VpceOrgPaths",
            "aws:Ec2InstanceSourceVpc", "aws:UserId", "aws:PrincipalServiceName", "aws:PrincipalServiceNamesList",
            "aws:PrincipalIsAWSService", "lambda:SourceFunctionArn");

    private static final Pattern FUNCTION_NAME = Pattern.compile("(?![-_])[a-zA-Z0-9_-]{2,64}(?<![-_])");
    private static final Pattern REVISION_ID = Pattern.compile("rev-[a-z0-9]{5}");
    private static final Pattern FUNCTION_ARN = Pattern.compile(
            "arn:[^:]+:lambda:[^:]*:[0-9]{12}:web-function/([^/]+)(?:/(revision|endpoint)/([^/]+))?");
    static final Pattern RESOURCE_ARN = Pattern.compile("arn:[^:]+:lambda:[^:]+:\\d{12}:web-function/[^/]+");
    static final Pattern ROLE_ARN = Pattern.compile(
            "arn:(" + AwsArnUtils.PARTITION_REGEX + "):iam::[0-9]{12}:role/[a-zA-Z0-9+=,.@_/-]+");
    static final Pattern KMS_KEY_ARN = Pattern.compile(
            "arn:(" + AwsArnUtils.PARTITION_REGEX + "):kms:[a-z0-9-]+:[0-9]{12}:key/[a-z0-9-]+");
    static final Pattern NETWORK_CONNECTOR_ARN = Pattern.compile(
            "arn:(" + AwsArnUtils.PARTITION_REGEX + "):lambda:[a-z0-9-]+:[0-9]{12}:network-connector:[a-zA-Z0-9_-]+:[0-9]+");
    static final Pattern S3_BUCKET = Pattern.compile("([a-z0-9][a-z0-9\\.\\-]{1,61}[a-z0-9])");
    static final Pattern RUNTIME = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z0-9]+)*");
    static final Pattern ENVIRONMENT_KEY = Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*");
    static final Pattern LOG_GROUP = Pattern.compile("[a-zA-Z0-9\\.\\-_/#]+");
    static final Pattern REGION = Pattern.compile("([a-z]{2,}-){2,}[0-9]");
    static final Pattern TAG_KEY = Pattern.compile("[a-zA-Z0-9 _\\.:/=+\\-@]+");
    static final Pattern TAG_VALUE = Pattern.compile("[a-zA-Z0-9 _\\.:/=+\\-@]*");
    static final Pattern NEXT_TOKEN = Pattern.compile("[a-f0-9-]{36}");
    static final Pattern POLICY_REVISION_ID = Pattern.compile("[0-9a-f-]{36}");
    private static final Pattern FILTER_NAME = Pattern.compile("[a-zA-Z0-9]+");
    private static final Pattern FILTER_VALUE = Pattern.compile("[a-zA-Z0-9_-]+");

    /** Every endpoint of the emulator shares one shard label; AWS assigns one per cell. */
    private static final String DOMAIN_SHARD = "001";
    private static final int DOMAIN_ID_LENGTH = 16;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final RegionResolver regionResolver;
    private final StorageFactory storageFactory;
    private final EmulatorConfig config;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    /**
     * Keyed {@code region/functionName}. The backend prefixes every key with the caller's account and
     * strips it on read, so two accounts in one region never see each other's functions.
     */
    private Map<String, WebFunction> functions = new ConcurrentHashMap<>();

    @Inject
    public LambdaWebService(RegionResolver regionResolver, StorageFactory storageFactory, EmulatorConfig config,
                            S3Service s3Service, ObjectMapper objectMapper) {
        this.regionResolver = regionResolver;
        this.storageFactory = storageFactory;
        this.config = config;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        initializeStorage();
    }

    void initializeStorage() {
        if (storageFactory == null) {
            return;
        }
        this.functions = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-web-functions.json", new TypeReference<Map<String, WebFunction>>() {}));
    }

    @Override
    public void clear() {
        functions.clear();
    }

    /** A page of a listing and the token that continues it, or null when it is the last page. */
    public record Page<T>(List<T> items, String nextToken) {
    }

    /** An endpoint before and after an update; the update response shows both. */
    public record EndpointUpdate(WebFunctionEndpoint before, WebFunctionEndpoint after) {
    }

    public record ResourcePolicy(String policy, String revisionId) {
    }

    public record AccountSettings(int functionCount) {
    }

    // ---------------------------------------------------------------- functions

    /**
     * CreateWebFunction: the function with its first revision and endpoint. The response renders the
     * revision and endpoint as {@code Pending}; the stored ones are already {@code Active}.
     */
    public WebFunction createFunction(String region, String accountId, JsonNode request) {
        LambdaWebValidation validation = new LambdaWebValidation();
        JsonNode nameNode = request.get("functionName");
        validation.required("functionName", nameNode);
        JsonNode revisionConfig = request.get("revisionConfig");
        if (present(revisionConfig)) {
            validateRevisionShape(validation, revisionConfig, "revisionConfig.");
        }
        JsonNode endpointConfig = request.get("endpointConfig");
        if (present(endpointConfig)) {
            validateEndpointShape(validation, endpointConfig, "endpointConfig.", false);
        }
        Map<String, String> tags = present(request.get("tags")) ? stringMap(request.get("tags")) : Map.of();
        validateTagsShape(validation, "tags", tags);
        validation.throwIfAny();

        String functionName = nameNode.asText();
        if (!FUNCTION_NAME.matcher(functionName).matches()) {
            throw invalidFunctionName(functionName);
        }
        if (!present(revisionConfig) || !present(endpointConfig)) {
            throw new AwsException("ValidationException",
                    "The revisionConfig and endpointConfig must be specified.", 400);
        }
        String arn = functionArn(region, accountId, functionName);
        if (functions.containsKey(key(region, functionName))) {
            throw new AwsException("ConflictException", "The function " + arn + " already exists.", 409);
        }
        WebFunction fn = newFunction(region, accountId, functionName, tags);
        WebFunctionRevision revision = buildRevision(fn, revisionConfig);
        WebFunctionEndpoint endpoint = buildEndpoint(region, accountId, fn, endpointConfig, revision, false);
        fn.revisions.put(revision.revisionId, revision);
        fn.endpoints.put(endpoint.endpointName, endpoint);
        functions.put(key(region, functionName), fn);
        LOG.infov("Created web function {0} with revision {1} and endpoint {2}",
                functionName, revision.revisionId, endpoint.endpointName);
        return fn;
    }

    /**
     * A function with no revision and no endpoint, which only CloudFormation can make:
     * {@code AWS::Lambda::WebFunction} creates the container and the child resources add to it.
     */
    public WebFunction createFunctionShell(String region, String accountId, String functionName,
                                           Map<String, String> tags) {
        if (functionName == null || !FUNCTION_NAME.matcher(functionName).matches()) {
            throw invalidFunctionName(functionName);
        }
        LambdaWebValidation validation = new LambdaWebValidation();
        validateTagsShape(validation, "tags", tags);
        validation.throwIfAny();
        if (functions.containsKey(key(region, functionName))) {
            throw new AwsException("ConflictException",
                    "The function " + functionArn(region, accountId, functionName) + " already exists.", 409);
        }
        WebFunction fn = newFunction(region, accountId, functionName, tags);
        functions.put(key(region, functionName), fn);
        return fn;
    }

    private WebFunction newFunction(String region, String accountId, String functionName, Map<String, String> tags) {
        WebFunction fn = new WebFunction();
        fn.functionName = functionName;
        fn.functionArn = functionArn(region, accountId, functionName);
        fn.accountId = accountId;
        fn.region = region;
        fn.createdAt = now();
        fn.updatedAt = fn.createdAt;
        fn.tags = new LinkedHashMap<>(tags);
        return fn;
    }

    public WebFunction getFunction(String region, String functionNameOrArn) {
        String functionName = resolveFunctionName(functionNameOrArn);
        WebFunction fn = functions.get(key(region, functionName));
        if (fn == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The function " + functionArn(region, regionResolver.getAccountId(), functionName)
                            + " does not exist.", 404);
        }
        return fn;
    }

    public Page<WebFunction> listFunctions(String region, JsonNode request) {
        ListRequest list = parseListRequest(request);
        Stream<WebFunction> all = in(functions, region).sorted(Comparator.comparing(f -> f.functionName));
        return page(list.matchesState("Active") ? all.toList() : List.of(), list, f -> f.functionName);
    }

    public void deleteFunction(String region, String functionNameOrArn) {
        WebFunction fn = getFunction(region, functionNameOrArn);
        functions.remove(key(region, fn.functionName));
        LOG.infov("Deleted web function {0}", fn.functionName);
    }

    public AccountSettings accountSettings(String region) {
        return new AccountSettings((int) in(functions, region).count());
    }

    // ---------------------------------------------------------------- revisions

    public WebFunctionRevision createRevision(String region, String functionNameOrArn, JsonNode request) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validateRevisionShape(validation, request, "");
        validation.throwIfAny();
        WebFunction fn = getFunction(region, functionNameOrArn);
        WebFunctionRevision revision = buildRevision(fn, request);
        fn.revisions.put(revision.revisionId, revision);
        for (WebFunctionEndpoint endpoint : fn.endpoints.values()) {
            if ("LatestRevision".equals(endpoint.autoDeploymentMode)) {
                endpoint.revisionWeights = List.of(weight(revision.revisionId, 100));
                endpoint.updatedAt = now();
            }
        }
        fn.updatedAt = now();
        persist(region, fn);
        return revision;
    }

    public WebFunctionRevision getRevision(String region, String functionNameOrArn, String revisionIdOrArn) {
        WebFunction fn = getFunction(region, functionNameOrArn);
        return revision(fn, revisionIdOrArn);
    }

    public Page<WebFunctionRevision> listRevisions(String region, String functionNameOrArn, JsonNode request) {
        ListRequest list = parseListRequest(request);
        WebFunction fn = getFunction(region, functionNameOrArn);
        List<WebFunctionRevision> newestFirst = fn.revisions.values().stream()
                .sorted(Comparator.comparing((WebFunctionRevision r) -> r.createdAt).reversed())
                .toList();
        return page(list.matchesState("Active") ? newestFirst : List.of(), list, r -> r.revisionId);
    }

    public void deleteRevision(String region, String functionNameOrArn, String revisionIdOrArn) {
        WebFunction fn = getFunction(region, functionNameOrArn);
        WebFunctionRevision revision = revision(fn, revisionIdOrArn);
        List<String> using = fn.endpoints.values().stream()
                .filter(e -> e.revisionWeights.stream().anyMatch(w -> w.revisionId.equals(revision.revisionId)))
                .map(e -> e.endpointName)
                .toList();
        if (!using.isEmpty()) {
            throw new AwsException("ConflictException", "The revision " + revision.revisionId
                    + " cannot be deleted because the endpoints " + using
                    + " are using it. Update the endpoints to use other revisions first.", 409);
        }
        fn.revisions.remove(revision.revisionId);
        fn.updatedAt = now();
        persist(region, fn);
    }

    /**
     * Whether an existing revision already carries the configuration a request describes, so a
     * CloudFormation update that changed nothing keeps the revision instead of minting another.
     */
    public boolean revisionMatches(WebFunctionRevision existing, JsonNode request) {
        WebFunctionRevision desired = new WebFunctionRevision();
        desired.description = text(request, "description");
        desired.kmsKeyArn = text(request, "kmsKeyArn");
        desired.buildConfig = buildConfigOf(request.path("buildConfig"));
        desired.serviceConfig = serviceConfigOf(request.path("serviceConfig"), defaultLogGroup(existing.functionArn));
        return objectMapper.valueToTree(configOnly(existing)).equals(objectMapper.valueToTree(configOnly(desired)));
    }

    private static WebFunctionRevision configOnly(WebFunctionRevision revision) {
        WebFunctionRevision copy = new WebFunctionRevision();
        copy.description = revision.description;
        copy.kmsKeyArn = revision.kmsKeyArn;
        copy.buildConfig = revision.buildConfig;
        copy.serviceConfig = revision.serviceConfig;
        return copy;
    }

    private WebFunctionRevision revision(WebFunction fn, String revisionIdOrArn) {
        String revisionId = resolveRevisionId(revisionIdOrArn);
        WebFunctionRevision revision = fn.revisions.get(revisionId);
        if (revision == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The revision " + revisionId + " does not exist.", 404);
        }
        return revision;
    }

    /** Validates the business rules of a revision request and builds the revision, without storing it. */
    private WebFunctionRevision buildRevision(WebFunction fn, JsonNode request) {
        JsonNode build = request.path("buildConfig");
        JsonNode service = request.path("serviceConfig");
        String runtime = build.path("runtimeConfig").path("runtime").asText();
        if (!SUPPORTED_RUNTIME.equals(runtime)) {
            throw new AwsException("ValidationException", "The runtime " + runtime + " is invalid.", 400);
        }
        if (present(service.get("environmentVariables"))) {
            List<String> reserved = new ArrayList<>();
            service.get("environmentVariables").fieldNames().forEachRemaining(name -> {
                if (RESERVED_ENVIRONMENT_KEYS.contains(name)) {
                    reserved.add(name);
                }
            });
            if (!reserved.isEmpty()) {
                throw new AwsException("ValidationException", "The environmentVariables cannot contain reserved keys."
                        + " The request specified the reserved keys " + reserved + ".", 400);
            }
        }
        String logGroup = text(service.path("telemetryConfig").path("loggingConfig"), "logGroup");
        if (logGroup != null && logGroup.toLowerCase(Locale.ROOT).startsWith("aws/")) {
            throw new AwsException("ValidationException", "The logGroup " + logGroup
                    + " is invalid. Log group names starting with aws/ (case-insensitive) are reserved for AWS.", 400);
        }
        WebFunctionRevision revision = new WebFunctionRevision();
        revision.buildConfig = buildConfigOf(build);
        revision.serviceConfig = serviceConfigOf(service, defaultLogGroup(fn.functionArn));
        requireCodeObject(revision.buildConfig);
        revision.description = text(request, "description");
        revision.kmsKeyArn = text(request, "kmsKeyArn");
        String revisionId;
        do {
            revisionId = "rev-" + randomLowercase(5);
        } while (fn.revisions.containsKey(revisionId));
        revision.revisionId = revisionId;
        revision.functionArn = fn.functionArn;
        revision.revisionArn = fn.functionArn + "/revision/" + revisionId;
        revision.createdAt = now();
        return revision;
    }

    private static WebFunctionRevision.BuildConfig buildConfigOf(JsonNode build) {
        WebFunctionRevision.BuildConfig out = new WebFunctionRevision.BuildConfig();
        JsonNode s3Object = build.path("codeConfig").path("s3Object");
        out.bucket = text(s3Object, "bucket");
        out.key = text(s3Object, "key");
        out.versionId = text(s3Object, "versionId");
        out.runtime = text(build.path("runtimeConfig"), "runtime");
        return out;
    }

    private static WebFunctionRevision.ServiceConfig serviceConfigOf(JsonNode service, String defaultLogGroup) {
        WebFunctionRevision.ServiceConfig out = new WebFunctionRevision.ServiceConfig();
        out.executionRoleArn = text(service, "executionRoleArn");
        if (present(service.get("timeoutSeconds"))) {
            out.timeoutSeconds = service.get("timeoutSeconds").asInt();
        }
        if (present(service.get("maxConcurrencyPerEnvironment"))) {
            out.maxConcurrencyPerEnvironment = service.get("maxConcurrencyPerEnvironment").asInt();
        }
        out.egressNetworkConnectorArn = text(service, "egressNetworkConnectorArn");
        if (present(service.get("environmentVariables"))) {
            out.environmentVariables = stringMap(service.get("environmentVariables"));
        }
        JsonNode logging = service.path("telemetryConfig").path("loggingConfig");
        String logGroup = text(logging, "logGroup");
        out.logGroup = logGroup != null ? logGroup : defaultLogGroup;
        String applicationLogLevel = text(logging, "applicationLogLevel");
        if (applicationLogLevel != null) {
            out.applicationLogLevel = applicationLogLevel;
        }
        String systemLogLevel = text(logging, "systemLogLevel");
        if (systemLogLevel != null) {
            out.systemLogLevel = systemLogLevel;
        }
        return out;
    }

    private static String defaultLogGroup(String functionArn) {
        return "/aws/lambda/web/" + functionArn.substring(functionArn.lastIndexOf('/') + 1);
    }

    /** The code object must exist, in the bucket's current version or the version the request pins. */
    private void requireCodeObject(WebFunctionRevision.BuildConfig build) {
        if (s3Service == null) {
            return;
        }
        try {
            s3Service.headObject(build.bucket, build.key, build.versionId);
        } catch (AwsException e) {
            LOG.debugv("Web function code object s3://{0}/{1} is not readable: {2}", build.bucket, build.key,
                    e.getMessage());
            throw new AwsException("ValidationException", "The specified S3 object does not exist: s3://"
                    + build.bucket + "/" + build.key + ". Verify the object exists in the bucket.", 400);
        }
    }

    private static void validateRevisionShape(LambdaWebValidation v, JsonNode request, String prefix) {
        JsonNode build = request.get("buildConfig");
        v.required(prefix + "buildConfig", build);
        if (present(build)) {
            JsonNode codeConfig = build.get("codeConfig");
            v.required(prefix + "buildConfig.codeConfig", codeConfig);
            if (present(codeConfig)) {
                JsonNode s3Object = codeConfig.get("s3Object");
                v.required(prefix + "buildConfig.codeConfig.s3Object", s3Object);
                if (present(s3Object)) {
                    String bucket = text(s3Object, "bucket");
                    v.required(prefix + "buildConfig.codeConfig.s3Object.bucket", s3Object.get("bucket"))
                            .length(prefix + "buildConfig.codeConfig.s3Object.bucket", bucket, 3, 63)
                            .pattern(prefix + "buildConfig.codeConfig.s3Object.bucket", bucket, S3_BUCKET)
                            .required(prefix + "buildConfig.codeConfig.s3Object.key", s3Object.get("key"))
                            .length(prefix + "buildConfig.codeConfig.s3Object.key", text(s3Object, "key"), 1, 1024)
                            .length(prefix + "buildConfig.codeConfig.s3Object.versionId",
                                    text(s3Object, "versionId"), 1, 1024);
                }
            }
            JsonNode runtimeConfig = build.get("runtimeConfig");
            v.required(prefix + "buildConfig.runtimeConfig", runtimeConfig);
            if (present(runtimeConfig)) {
                String runtime = text(runtimeConfig, "runtime");
                v.required(prefix + "buildConfig.runtimeConfig.runtime", runtimeConfig.get("runtime"))
                        .length(prefix + "buildConfig.runtimeConfig.runtime", runtime, 1, 30)
                        .pattern(prefix + "buildConfig.runtimeConfig.runtime", runtime, RUNTIME);
            }
        }
        JsonNode service = request.get("serviceConfig");
        v.required(prefix + "serviceConfig", service);
        if (present(service)) {
            String role = text(service, "executionRoleArn");
            v.required(prefix + "serviceConfig.executionRoleArn", service.get("executionRoleArn"))
                    .length(prefix + "serviceConfig.executionRoleArn", role, 20, 2048)
                    .pattern(prefix + "serviceConfig.executionRoleArn", role, ROLE_ARN)
                    .range(prefix + "serviceConfig.timeoutSeconds", integer(service, "timeoutSeconds"), 3, 900)
                    .range(prefix + "serviceConfig.maxConcurrencyPerEnvironment",
                            integer(service, "maxConcurrencyPerEnvironment"), 1, 128)
                    .pattern(prefix + "serviceConfig.egressNetworkConnectorArn",
                            text(service, "egressNetworkConnectorArn"), NETWORK_CONNECTOR_ARN);
            if (present(service.get("environmentVariables"))) {
                v.mapKeys(prefix + "serviceConfig.environmentVariables", stringMap(service.get("environmentVariables")),
                        1, Integer.MAX_VALUE, ENVIRONMENT_KEY);
            }
            JsonNode logging = service.path("telemetryConfig").path("loggingConfig");
            if (present(logging)) {
                String loggingPath = prefix + "serviceConfig.telemetryConfig.loggingConfig.";
                v.length(loggingPath + "logGroup", text(logging, "logGroup"), 1, 500)
                        .pattern(loggingPath + "logGroup", text(logging, "logGroup"), LOG_GROUP)
                        .enumeration(loggingPath + "applicationLogLevel", text(logging, "applicationLogLevel"),
                                APPLICATION_LOG_LEVELS)
                        .enumeration(loggingPath + "systemLogLevel", text(logging, "systemLogLevel"), SYSTEM_LOG_LEVELS);
            }
        }
        v.length(prefix + "description", text(request, "description"), 0, 256)
                .length(prefix + "kmsKeyArn", text(request, "kmsKeyArn"), 20, 2048)
                .pattern(prefix + "kmsKeyArn", text(request, "kmsKeyArn"), KMS_KEY_ARN);
    }

    // ---------------------------------------------------------------- endpoints

    public WebFunctionEndpoint createEndpoint(String region, String accountId, String functionNameOrArn,
                                              JsonNode request) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validateEndpointShape(validation, request, "", true);
        validation.throwIfAny();
        WebFunction fn = getFunction(region, functionNameOrArn);
        WebFunctionEndpoint endpoint = buildEndpoint(region, accountId, fn, request, null, true);
        fn.endpoints.put(endpoint.endpointName, endpoint);
        fn.updatedAt = now();
        persist(region, fn);
        LOG.infov("Created web function endpoint {0} on {1}: {2}", endpoint.endpointName, fn.functionName,
                endpoint.domainName);
        return endpoint;
    }

    public WebFunctionEndpoint getEndpoint(String region, String functionNameOrArn, String endpointNameOrArn) {
        WebFunction fn = getFunction(region, functionNameOrArn);
        return endpoint(fn, endpointNameOrArn);
    }

    public Page<WebFunctionEndpoint> listEndpoints(String region, String functionNameOrArn, JsonNode request) {
        ListRequest list = parseListRequest(request);
        WebFunction fn = getFunction(region, functionNameOrArn);
        List<WebFunctionEndpoint> byName = fn.endpoints.values().stream()
                .sorted(Comparator.comparing(e -> e.endpointName))
                .toList();
        return page(list.matchesState("Active") ? byName : List.of(), list, e -> e.endpointName);
    }

    public EndpointUpdate updateEndpoint(String region, String functionNameOrArn, String endpointNameOrArn,
                                         JsonNode request) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validateEndpointShape(validation, request, "", false);
        validation.throwIfAny();
        WebFunction fn = getFunction(region, functionNameOrArn);
        WebFunctionEndpoint endpoint = endpoint(fn, endpointNameOrArn);
        WebFunctionEndpoint before = copy(endpoint);

        String mode = text(request, "autoDeploymentMode");
        boolean latest = "LatestRevision".equals(mode != null ? mode : endpoint.autoDeploymentMode);
        JsonNode weights = request.get("revisionWeights");
        if (latest && present(weights)) {
            throw new AwsException("ValidationException",
                    "The revisionWeights cannot be specified when the autoDeploymentMode is LatestRevision.", 400);
        }
        if (mode != null && !mode.equals(endpoint.autoDeploymentMode)) {
            requireModeAllowed(mode, endpoint.endpointType);
            endpoint.autoDeploymentMode = mode;
            if (latest) {
                latestRevision(fn).ifPresent(id -> endpoint.revisionWeights = List.of(weight(id, 100)));
            }
        }
        if (present(weights)) {
            endpoint.revisionWeights = resolveWeights(fn, weights);
        }
        String authType = text(request, "authType");
        if (authType != null) {
            endpoint.authType = authType;
        }
        String description = text(request, "description");
        if (description != null) {
            endpoint.description = description;
        }
        if (present(request.get("scalingConfig"))) {
            endpoint.scalingConfig = scalingConfigOf(request.get("scalingConfig"));
        }
        if (present(request.get("throttleConfig"))) {
            endpoint.throttleConfig = throttleConfigOf(request.get("throttleConfig"));
        }
        endpoint.updateStatus = "Successful";
        endpoint.updatedAt = now();
        fn.updatedAt = endpoint.updatedAt;
        persist(region, fn);
        return new EndpointUpdate(before, endpoint);
    }

    public void deleteEndpoint(String region, String functionNameOrArn, String endpointNameOrArn) {
        WebFunction fn = getFunction(region, functionNameOrArn);
        WebFunctionEndpoint endpoint = endpoint(fn, endpointNameOrArn);
        fn.endpoints.remove(endpoint.endpointName);
        fn.updatedAt = now();
        persist(region, fn);
    }

    private WebFunctionEndpoint endpoint(WebFunction fn, String endpointNameOrArn) {
        String endpointName = resolveEndpointName(endpointNameOrArn);
        WebFunctionEndpoint endpoint = fn.endpoints.get(endpointName);
        if (endpoint == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The endpoint " + endpointName + " does not exist.", 404);
        }
        return endpoint;
    }

    /**
     * Validates the business rules of an endpoint request and builds the endpoint, without storing it.
     * The initial endpoint of CreateWebFunction routes to {@code initialRevision} in either mode;
     * CreateWebFunctionEndpoint ({@code standalone}) needs weights unless it follows the latest revision.
     */
    private WebFunctionEndpoint buildEndpoint(String region, String accountId, WebFunction fn, JsonNode request,
                                              WebFunctionRevision initialRevision, boolean standalone) {
        String endpointName = request.path("endpointName").asText();
        if (!FUNCTION_NAME.matcher(endpointName).matches()) {
            throw new AwsException("ValidationException", "The endpoint ARN " + endpointName
                    + " with the function name " + fn.functionName + ", the region " + region
                    + " and the account id " + accountId + " is invalid.", 400);
        }
        if (fn.endpoints.containsKey(endpointName)) {
            throw new AwsException("ConflictException", "The endpoint " + endpointName + " already exists.", 409);
        }
        if (fn.endpoints.size() >= MAX_ENDPOINTS_PER_FUNCTION) {
            throw new AwsException("ServiceQuotaExceededException", "The function " + fn.functionArn
                    + " has reached the maximum of " + MAX_ENDPOINTS_PER_FUNCTION + " endpoints.", 402);
        }
        String endpointType = request.path("endpointType").asText();
        String mode = text(request, "autoDeploymentMode");
        if (mode == null) {
            mode = "Disabled";
        }
        requireModeAllowed(mode, endpointType);
        JsonNode weights = request.get("revisionWeights");
        if ("LatestRevision".equals(mode) && present(weights)) {
            throw new AwsException("ValidationException",
                    "The revisionWeights cannot be specified when the autoDeploymentMode is LatestRevision.", 400);
        }
        if (standalone && "Disabled".equals(mode) && !present(weights)) {
            throw new AwsException("ValidationException",
                    "The revisionWeights must be specified when the autoDeploymentMode is Disabled.", 400);
        }
        List<String> regions = resolveRegions(region, endpointType, request.get("regions"));

        WebFunctionEndpoint endpoint = new WebFunctionEndpoint();
        endpoint.endpointName = endpointName;
        endpoint.endpointArn = fn.functionArn + "/endpoint/" + endpointName;
        endpoint.functionArn = fn.functionArn;
        endpoint.description = text(request, "description");
        endpoint.endpointType = endpointType;
        endpoint.authType = request.path("authType").asText();
        endpoint.autoDeploymentMode = mode;
        endpoint.regions = regions;
        if (initialRevision != null) {
            endpoint.revisionWeights = List.of(weight(initialRevision.revisionId, 100));
        } else if (present(weights)) {
            endpoint.revisionWeights = resolveWeights(fn, weights);
        } else {
            latestRevision(fn).ifPresent(id -> endpoint.revisionWeights = List.of(weight(id, 100)));
        }
        if (present(request.get("scalingConfig"))) {
            endpoint.scalingConfig = scalingConfigOf(request.get("scalingConfig"));
        }
        if (present(request.get("throttleConfig"))) {
            endpoint.throttleConfig = throttleConfigOf(request.get("throttleConfig"));
        }
        String host = URI.create(config.effectiveBaseUrl()).getAuthority();
        if ("MultiRegion".equals(endpointType)) {
            endpoint.domainName = randomLowercase(DOMAIN_ID_LENGTH) + ".lambda-web.global." + host;
        } else {
            for (String each : regions) {
                endpoint.regionalDomainNames.put(each, regionalDomain(each, host));
            }
            endpoint.domainName = endpoint.regionalDomainNames.get(region);
        }
        endpoint.createdAt = now();
        endpoint.updatedAt = endpoint.createdAt;
        return endpoint;
    }

    private static String regionalDomain(String region, String host) {
        return randomLowercase(DOMAIN_ID_LENGTH) + "." + DOMAIN_SHARD + ".lambda-web." + region + "." + host;
    }

    private static void requireModeAllowed(String mode, String endpointType) {
        if ("LatestRevision".equals(mode) && !"HomeRegion".equals(endpointType)) {
            throw new AwsException("ValidationException", "The autoDeploymentMode can only be Disabled when the"
                    + " endpointType is " + endpointType + ".", 400);
        }
    }

    /** The home region leads the list and is added when absent. */
    private static List<String> resolveRegions(String home, String endpointType, JsonNode requested) {
        LinkedHashSet<String> regions = new LinkedHashSet<>();
        regions.add(home);
        if (present(requested)) {
            requested.forEach(r -> regions.add(r.asText()));
        }
        List<String> resolved = new ArrayList<>(regions);
        if ("HomeRegion".equals(endpointType) && resolved.size() > 1) {
            throw new AwsException("ValidationException", "The endpointType HomeRegion supports only the home region "
                    + home + ". The request results in the regions " + resolved + ".", 400);
        }
        if (!"HomeRegion".equals(endpointType) && resolved.size() < 2) {
            throw new AwsException("ValidationException", "The endpointType " + endpointType
                    + " requires at least 2 distinct regions. The home region " + home
                    + " is added automatically, so specify at least 1 additional region."
                    + " The request results in the regions " + resolved + ".", 400);
        }
        return resolved;
    }

    private static List<WebFunctionEndpoint.RevisionWeight> resolveWeights(WebFunction fn, JsonNode weights) {
        List<WebFunctionEndpoint.RevisionWeight> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int total = 0;
        for (JsonNode node : weights) {
            WebFunctionEndpoint.RevisionWeight weight = weight(node.path("revisionId").asText(), node.path("weight").asInt());
            ids.add(weight.revisionId);
            total += weight.weight;
            out.add(weight);
        }
        if (ids.size() != out.size() || total != 100) {
            throw new AwsException("ValidationException",
                    "The revisionWeights must contain unique revisionIds, and the weights must total exactly 100.", 400);
        }
        for (String id : ids) {
            if (!fn.revisions.containsKey(id)) {
                throw new AwsException("ResourceNotFoundException", "The revision " + id + " does not exist.", 404);
            }
        }
        return out;
    }

    private static Optional<String> latestRevision(WebFunction fn) {
        return fn.revisions.values().stream()
                .max(Comparator.comparing((WebFunctionRevision r) -> r.createdAt))
                .map(r -> r.revisionId);
    }

    private static WebFunctionEndpoint.RevisionWeight weight(String revisionId, int weight) {
        WebFunctionEndpoint.RevisionWeight out = new WebFunctionEndpoint.RevisionWeight();
        out.revisionId = revisionId;
        out.weight = weight;
        return out;
    }

    private static WebFunctionEndpoint.ScalingConfig scalingConfigOf(JsonNode node) {
        WebFunctionEndpoint.ScalingConfig out = new WebFunctionEndpoint.ScalingConfig();
        out.maxEnvironments = integer(node, "maxEnvironments");
        return out;
    }

    private static WebFunctionEndpoint.ThrottleConfig throttleConfigOf(JsonNode node) {
        Integer rateLimit = integer(node, "rateLimit");
        if (rateLimit != null && !SUPPORTED_RATE_LIMITS.contains(rateLimit)) {
            throw new AwsException("ValidationException", "The rateLimit " + rateLimit
                    + " is invalid. Supported rate limits are: " + SUPPORTED_RATE_LIMITS + ".", 400);
        }
        WebFunctionEndpoint.ThrottleConfig out = new WebFunctionEndpoint.ThrottleConfig();
        out.rateLimit = rateLimit;
        return out;
    }

    private static void validateEndpointShape(LambdaWebValidation v, JsonNode request, String prefix, boolean create) {
        if (create) {
            v.required(prefix + "endpointName", request.get("endpointName"))
                    .required(prefix + "endpointType", request.get("endpointType"))
                    .required(prefix + "authType", request.get("authType"));
        }
        v.length(prefix + "endpointName", text(request, "endpointName"), 1, 256)
                .enumeration(prefix + "endpointType", text(request, "endpointType"), ENDPOINT_TYPES)
                .enumeration(prefix + "authType", text(request, "authType"), AUTH_TYPES)
                .enumeration(prefix + "autoDeploymentMode", text(request, "autoDeploymentMode"), AUTO_DEPLOYMENT_MODES)
                .length(prefix + "description", text(request, "description"), 0, 256);
        JsonNode regions = request.get("regions");
        if (present(regions)) {
            v.listSize(prefix + "regions", regions.size(), 1, 100);
            int index = 1;
            for (JsonNode region : regions) {
                v.length(prefix + "regions." + index + ".member", region.asText(), 1, 50)
                        .pattern(prefix + "regions." + index + ".member", region.asText(), REGION);
                index++;
            }
        }
        JsonNode weights = request.get("revisionWeights");
        if (present(weights)) {
            v.listSize(prefix + "revisionWeights", weights.size(), 1, 2);
            int index = 1;
            for (JsonNode weight : weights) {
                String member = prefix + "revisionWeights." + index + ".member.";
                v.required(member + "revisionId", weight.get("revisionId"))
                        .length(member + "revisionId", text(weight, "revisionId"), 1, 256)
                        .required(member + "weight", weight.get("weight"))
                        .range(member + "weight", integer(weight, "weight"), 1, 100);
                index++;
            }
        }
        if (present(request.get("scalingConfig"))) {
            v.range(prefix + "scalingConfig.maxEnvironments",
                    integer(request.get("scalingConfig"), "maxEnvironments"), 2, 10000);
        }
        if (present(request.get("throttleConfig"))) {
            v.range(prefix + "throttleConfig.rateLimit", integer(request.get("throttleConfig"), "rateLimit"),
                    0, Integer.MAX_VALUE);
        }
    }

    // ---------------------------------------------------------------- tags

    public Map<String, String> listTags(String region, String resourceArn) {
        return functionOfResourceArn(region, resourceArn, "resource").tags;
    }

    public void tagResource(String region, String resourceArn, Map<String, String> tags) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.pattern("resource", resourceArn, RESOURCE_ARN);
        validateTagsShape(validation, "tags", tags);
        validation.throwIfAny();
        WebFunction fn = functionOfResourceArn(region, resourceArn, "resource");
        fn.tags.putAll(tags);
        fn.updatedAt = now();
        persist(region, fn);
    }

    public void untagResource(String region, String resourceArn, List<String> tagKeys) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.pattern("resource", resourceArn, RESOURCE_ARN);
        validation.listSize("tagKeys", tagKeys == null ? 0 : tagKeys.size(), 1, 200);
        validation.throwIfAny();
        WebFunction fn = functionOfResourceArn(region, resourceArn, "resource");
        tagKeys.forEach(fn.tags::remove);
        fn.updatedAt = now();
        persist(region, fn);
    }

    /** Reconciles the stored tags to exactly {@code desired}, for CloudFormation. */
    public void replaceTags(String region, String functionName, Map<String, String> desired) {
        WebFunction fn = getFunction(region, functionName);
        fn.tags = new LinkedHashMap<>(desired);
        fn.updatedAt = now();
        persist(region, fn);
    }

    private static void validateTagsShape(LambdaWebValidation v, String path, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        v.mapKeys(path, tags, 1, 128, TAG_KEY).mapValues(path, tags, 0, 256, TAG_VALUE);
    }

    // ---------------------------------------------------------------- resource policy

    public ResourcePolicy putResourcePolicy(String region, String resourceArn, String policy, String revisionId) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.pattern("resourceArn", resourceArn, RESOURCE_ARN)
                .required("policy", policy == null ? null : objectMapper.getNodeFactory().textNode(policy))
                .length("policy", policy, 1, 20480)
                .pattern("revisionId", revisionId, POLICY_REVISION_ID);
        validation.throwIfAny();
        WebFunction fn = functionOfResourceArn(region, resourceArn, "resourceArn");
        requireNonPublicPolicy(policy);
        if (revisionId != null && !revisionId.equals(fn.policyRevisionId)) {
            throw new AwsException("ConflictException", "The policy was updated or deleted by another request."
                    + " Try again with the latest policy revisionId.", 409);
        }
        fn.policy = policy;
        fn.policyRevisionId = UUID.randomUUID().toString();
        fn.updatedAt = now();
        persist(region, fn);
        return new ResourcePolicy(fn.policy, fn.policyRevisionId);
    }

    public ResourcePolicy getResourcePolicy(String region, String resourceArn) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.pattern("resourceArn", resourceArn, RESOURCE_ARN);
        validation.throwIfAny();
        WebFunction fn = functionOfResourceArn(region, resourceArn, "resourceArn");
        if (fn.policy == null) {
            throw noPolicy();
        }
        return new ResourcePolicy(fn.policy, fn.policyRevisionId);
    }

    public void deleteResourcePolicy(String region, String resourceArn, String revisionId) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.pattern("resourceArn", resourceArn, RESOURCE_ARN).pattern("revisionId", revisionId, POLICY_REVISION_ID);
        validation.throwIfAny();
        WebFunction fn = functionOfResourceArn(region, resourceArn, "resourceArn");
        if (fn.policy == null) {
            throw noPolicy();
        }
        if (revisionId != null && !revisionId.equals(fn.policyRevisionId)) {
            throw new AwsException("ConflictException", "The policy was updated by another request. Try again.", 409);
        }
        fn.policy = null;
        fn.policyRevisionId = null;
        fn.updatedAt = now();
        persist(region, fn);
    }

    private static AwsException noPolicy() {
        return new AwsException("ResourceNotFoundException", "The function does not have a policy.", 404);
    }

    /**
     * A statement that allows a wildcard principal is public unless a condition pins one of the
     * trusted keys; the service refuses such a policy outright.
     */
    private void requireNonPublicPolicy(String policy) {
        JsonNode document;
        try {
            document = objectMapper.readTree(policy);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The policy is not valid JSON.", 400);
        }
        JsonNode statements = document.path("Statement");
        Iterable<JsonNode> each = statements.isArray() ? statements : List.of(statements);
        for (JsonNode statement : each) {
            if (!"Allow".equals(statement.path("Effect").asText())) {
                continue;
            }
            JsonNode principal = statement.get("Principal");
            if (principal == null) {
                throw new AwsException("ValidationException",
                        "The provided principal was invalid. Please check the principal and try again.", 400);
            }
            boolean wildcard = "*".equals(principal.asText()) || "*".equals(principal.path("AWS").asText())
                    || (principal.path("AWS").isArray() && principal.path("AWS").toString().contains("\"*\""));
            if (wildcard && !hasTrustedCondition(statement.path("Condition"))) {
                throw new AwsException("ValidationException", "Lambda blocked the creation of this policy"
                        + " because it would grant public access to your function.", 400);
            }
        }
    }

    private static boolean hasTrustedCondition(JsonNode condition) {
        if (!condition.isObject()) {
            return false;
        }
        for (JsonNode operator : condition) {
            Iterator<String> keys = operator.fieldNames();
            while (keys.hasNext()) {
                if (TRUSTED_CONDITION_KEYS.contains(keys.next())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- shared helpers

    private WebFunction functionOfResourceArn(String region, String resourceArn, String member) {
        LambdaWebValidation validation = new LambdaWebValidation();
        validation.pattern(member, resourceArn, RESOURCE_ARN);
        validation.throwIfAny();
        return getFunction(region, resourceArn);
    }

    private static AwsException invalidFunctionName(String functionName) {
        return new AwsException("ValidationException", "Invalid function name or ARN '" + functionName + "'", 400);
    }

    /** The name of a function given as a name or as any of its ARNs. */
    static String resolveFunctionName(String functionNameOrArn) {
        if (functionNameOrArn == null) {
            throw invalidFunctionName(null);
        }
        Matcher arn = FUNCTION_ARN.matcher(functionNameOrArn);
        String name = arn.matches() ? arn.group(1) : functionNameOrArn;
        if (!FUNCTION_NAME.matcher(name).matches()) {
            throw invalidFunctionName(functionNameOrArn);
        }
        return name;
    }

    static String resolveRevisionId(String revisionIdOrArn) {
        Matcher arn = revisionIdOrArn == null ? null : FUNCTION_ARN.matcher(revisionIdOrArn);
        String id = arn != null && arn.matches() && "revision".equals(arn.group(2)) ? arn.group(3) : revisionIdOrArn;
        if (id == null || !REVISION_ID.matcher(id).matches()) {
            throw new AwsException("ValidationException", "Invalid revision id or ARN '" + revisionIdOrArn + "'", 400);
        }
        return id;
    }

    static String resolveEndpointName(String endpointNameOrArn) {
        Matcher arn = endpointNameOrArn == null ? null : FUNCTION_ARN.matcher(endpointNameOrArn);
        return arn != null && arn.matches() && "endpoint".equals(arn.group(2)) ? arn.group(3) : endpointNameOrArn;
    }

    private static String functionArn(String region, String accountId, String functionName) {
        return AwsArnUtils.Arn.of("lambda", region, accountId, "web-function/" + functionName).toString();
    }

    private WebFunctionEndpoint copy(WebFunctionEndpoint endpoint) {
        return objectMapper.convertValue(objectMapper.valueToTree(endpoint), WebFunctionEndpoint.class);
    }

    /** The filters, page size and token of a list request, validated. */
    private record ListRequest(List<String> states, int maxResults, String nextToken) {
        boolean matchesState(String state) {
            return states == null || states.contains(state);
        }
    }

    private ListRequest parseListRequest(JsonNode request) {
        LambdaWebValidation validation = new LambdaWebValidation();
        Integer maxResults = integer(request, "maxResults");
        validation.range("maxResults", maxResults, 1, 50)
                .pattern("nextToken", text(request, "nextToken"), NEXT_TOKEN);
        JsonNode filters = request.get("filters");
        List<String> badNames = new ArrayList<>();
        List<String> states = null;
        if (present(filters)) {
            validation.listSize("filters", filters.size(), 1, 10);
            int index = 1;
            for (JsonNode filter : filters) {
                String member = "filters." + index + ".member.";
                String name = text(filter, "name");
                validation.required(member + "name", filter.get("name"))
                        .length(member + "name", name, 1, 64)
                        .pattern(member + "name", name, FILTER_NAME)
                        .required(member + "values", filter.get("values"));
                if (present(filter.get("values"))) {
                    validation.listSize(member + "values", filter.get("values").size(), 1, 10);
                    int valueIndex = 1;
                    for (JsonNode value : filter.get("values")) {
                        validation.length(member + "values." + valueIndex + ".member", value.asText(), 1, 64)
                                .pattern(member + "values." + valueIndex + ".member", value.asText(), FILTER_VALUE);
                        valueIndex++;
                    }
                }
                if ("state".equals(name)) {
                    List<String> values = new ArrayList<>();
                    filter.path("values").forEach(v -> values.add(v.asText()));
                    states = values;
                } else if (name != null) {
                    badNames.add(name);
                }
                index++;
            }
        }
        validation.throwIfAny();
        if (!badNames.isEmpty()) {
            throw new AwsException("ValidationException", "The filter names " + badNames
                    + " are invalid. Supported filter names are: [state].", 400);
        }
        return new ListRequest(states, maxResults == null ? DEFAULT_PAGE_SIZE : maxResults, text(request, "nextToken"));
    }

    /** Pages a sorted listing; a token names the item a page ended on, so the next page starts after it. */
    private static <T> Page<T> page(List<T> items, ListRequest list, Function<T, String> keyOf) {
        int start = 0;
        if (list.nextToken() != null) {
            start = items.size();
            for (int i = 0; i < items.size(); i++) {
                if (list.nextToken().equals(token(keyOf.apply(items.get(i))))) {
                    start = i + 1;
                    break;
                }
            }
        }
        int end = Math.min(items.size(), start + list.maxResults());
        List<T> pageItems = items.subList(start, end);
        String nextToken = end < items.size() ? token(keyOf.apply(items.get(end - 1))) : null;
        return new Page<>(pageItems, nextToken);
    }

    private static String token(String key) {
        return UUID.nameUUIDFromBytes(("lambda-web:" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return present(value) ? value.asText() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return present(value) ? value.asInt() : null;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
        return out;
    }

    private static final String LOWERCASE_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static String randomLowercase(int length) {
        StringBuilder out = new StringBuilder(length);
        while (out.length() < length) {
            UUID uuid = UUID.randomUUID();
            long bits = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            for (int i = 0; i < 12 && out.length() < length; i++) {
                out.append(LOWERCASE_ALNUM.charAt((int) Math.floorMod(bits, LOWERCASE_ALNUM.length())));
                bits = bits / LOWERCASE_ALNUM.length();
            }
        }
        return out.toString();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** A timestamp as the wire shows it, with microseconds; CloudFormation attributes carry the same form. */
    public static String formatTimestamp(Instant instant) {
        return LambdaWebResponses.timestamp(instant);
    }

    private static String key(String region, String functionName) {
        return region + "/" + functionName;
    }

    private static <V> Stream<V> in(Map<String, V> store, String region) {
        String prefix = region + "/";
        return store.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue);
    }

    /** Writes a function mutated in place back, since the storage map only tracks put and remove. */
    private void persist(String region, WebFunction fn) {
        functions.put(key(region, fn.functionName), fn);
    }
}
