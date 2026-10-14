package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The service without CDI: input-shape error wording, paging, revision comparison for
 * CloudFormation, and state that survives a restart.
 */
class LambdaWebServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path storageDir;

    private LambdaWebService service() {
        return service(null);
    }

    /** A fresh process over the storage directory; null storage keeps everything in memory. */
    private LambdaWebService service(StorageFactory storage) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        LambdaWebService service = new LambdaWebService(regionResolver, storage, config, null, mapper);
        service.initializeStorage();
        return service;
    }

    private ObjectNode revisionConfig(int timeoutSeconds) {
        ObjectNode config = mapper.createObjectNode();
        ObjectNode build = config.putObject("buildConfig");
        build.putObject("codeConfig").putObject("s3Object").put("bucket", "code").put("key", "app.zip");
        build.putObject("runtimeConfig").put("runtime", "nodejs24.x");
        config.putObject("serviceConfig").put("executionRoleArn", ROLE).put("timeoutSeconds", timeoutSeconds);
        return config;
    }

    private ObjectNode createRequest(String name) {
        ObjectNode request = mapper.createObjectNode().put("functionName", name);
        request.set("revisionConfig", revisionConfig(30));
        request.putObject("endpointConfig").put("endpointName", "dev").put("endpointType", "HomeRegion")
                .put("authType", "ApplicationManaged").put("autoDeploymentMode", "LatestRevision");
        return request;
    }

    @Test
    void shapeErrorsAreCountedAndJoinedBeforeAnyBusinessRule() {
        ObjectNode request = createRequest("-bad-name-");
        ((ObjectNode) request.path("revisionConfig").path("buildConfig").path("codeConfig").path("s3Object"))
                .put("bucket", "b");
        AwsException e = assertThrows(AwsException.class, () -> service().createFunction(REGION, ACCOUNT_ID, request));
        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("2 validation errors detected: Value 'b' at 'revisionConfig.buildConfig.codeConfig.s3Object.bucket'"
                + " failed to satisfy constraint: Member must have length greater than or equal to 3; Value 'b' at"
                + " 'revisionConfig.buildConfig.codeConfig.s3Object.bucket' failed to satisfy constraint: Member must"
                + " satisfy regular expression pattern: ([a-z0-9][a-z0-9\\.\\-]{1,61}[a-z0-9])", e.getMessage());
    }

    @Test
    void tagValuesAreValidatedAsAMap() {
        ObjectNode request = createRequest("app");
        request.putObject("tags").put("team", "has;semicolon");
        AwsException e = assertThrows(AwsException.class, () -> service().createFunction(REGION, ACCOUNT_ID, request));
        assertEquals("1 validation error detected: Value '{team=has;semicolon}' at 'tags' failed to satisfy constraint:"
                + " Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must"
                + " have length greater than or equal to 0, Member must satisfy regular expression pattern:"
                + " [a-zA-Z0-9 _\\.:/=+\\-@]*]", e.getMessage());
    }

    @Test
    void listsPageByNameWithUuidTokens() {
        LambdaWebService service = service();
        for (String name : List.of("charlie", "alpha", "bravo")) {
            service.createFunction(REGION, ACCOUNT_ID, createRequest(name));
        }
        ObjectNode request = mapper.createObjectNode().put("maxResults", 2);
        LambdaWebService.Page<WebFunction> first = service.listFunctions(REGION, request);
        assertEquals(List.of("alpha", "bravo"), first.items().stream().map(f -> f.functionName).toList());
        assertNotNull(first.nextToken());
        assertTrue(first.nextToken().matches("[a-f0-9-]{36}"), first.nextToken());

        request.put("nextToken", first.nextToken());
        LambdaWebService.Page<WebFunction> second = service.listFunctions(REGION, request);
        assertEquals(List.of("charlie"), second.items().stream().map(f -> f.functionName).toList());
        assertNull(second.nextToken());

        assertEquals(3, service.accountSettings(REGION).functionCount());
        assertEquals(0, service.accountSettings("eu-west-1").functionCount());
    }

    @Test
    void stateFilterMatchesOnlyActive() {
        LambdaWebService service = service();
        service.createFunction(REGION, ACCOUNT_ID, createRequest("app"));
        ObjectNode filtered = mapper.createObjectNode();
        filtered.putArray("filters").addObject().put("name", "state").putArray("values").add("Failed");
        assertTrue(service.listFunctions(REGION, filtered).items().isEmpty());
        ObjectNode unknown = mapper.createObjectNode();
        unknown.putArray("filters").addObject().put("name", "nope").putArray("values").add("x");
        AwsException e = assertThrows(AwsException.class, () -> service.listFunctions(REGION, unknown));
        assertEquals("The filter names [nope] are invalid. Supported filter names are: [state].", e.getMessage());
    }

    @Test
    void revisionMatchesComparesTheConfigurationAfterDefaults() {
        LambdaWebService service = service();
        WebFunction fn = service.createFunction(REGION, ACCOUNT_ID, createRequest("app"));
        WebFunctionRevision revision = fn.revisions.values().iterator().next();
        assertTrue(service.revisionMatches(revision, revisionConfig(30)));
        assertFalse(service.revisionMatches(revision, revisionConfig(31)));
        ObjectNode described = revisionConfig(30).put("description", "d");
        assertFalse(service.revisionMatches(revision, described));
    }

    @Test
    void endpointIdentifiersAcceptArns() {
        LambdaWebService service = service();
        WebFunction fn = service.createFunction(REGION, ACCOUNT_ID, createRequest("app"));
        WebFunctionEndpoint endpoint = fn.endpoints.get("dev");
        assertEquals("dev", service.getEndpoint(REGION, fn.functionArn, endpoint.endpointArn).endpointName);
        WebFunctionRevision revision = fn.revisions.values().iterator().next();
        assertEquals(revision.revisionId, service.getRevision(REGION, "app", revision.revisionArn).revisionId);
        assertEquals("Invalid revision id or ARN 'nope'",
                assertThrows(AwsException.class, () -> service.getRevision(REGION, "app", "nope")).getMessage());
        assertEquals("Invalid function name or ARN 'a'",
                assertThrows(AwsException.class, () -> service.getFunction(REGION, "a")).getMessage());
    }

    @Test
    void functionsSurviveARestart() {
        LambdaWebService first = service(new FileStorageFactory(storageDir));
        WebFunction created = first.createFunction(REGION, ACCOUNT_ID, createRequest("durable"));
        first.createRevision(REGION, "durable", revisionConfig(60));
        ObjectNode update = mapper.createObjectNode().put("description", "updated");
        first.updateEndpoint(REGION, "durable", "dev", update);

        LambdaWebService second = service(new FileStorageFactory(storageDir));
        WebFunction restored = second.getFunction(REGION, "durable");
        assertEquals(created.functionArn, restored.functionArn);
        assertEquals(2, restored.revisions.size());
        WebFunctionEndpoint endpoint = second.getEndpoint(REGION, "durable", "dev");
        assertEquals("updated", endpoint.description);
        assertEquals("Successful", endpoint.updateStatus);
        assertEquals(60, second.listRevisions(REGION, "durable", mapper.createObjectNode())
                .items().get(0).serviceConfig.timeoutSeconds);
        assertEquals(endpoint.revisionWeights.get(0).revisionId,
                second.listRevisions(REGION, "durable", mapper.createObjectNode()).items().get(0).revisionId,
                "the LatestRevision endpoint moved to the newer revision and that move was written back");
    }

    /** Real files rather than memory, so only what reached the backend comes back. */
    private static final class FileStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();
        private final Path directory;

        private FileStorageFactory(Path directory) {
            super(null, null);
            this.directory = directory;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> {
                PersistentStorage<String, V> inner = new PersistentStorage<>(directory.resolve(fileName), typeReference);
                inner.load();
                return new AccountAwareStorageBackend<V>(inner, null, ACCOUNT_ID);
            });
        }
    }

    @Test
    void jsonTreeOfARevisionRoundTrips() {
        LambdaWebService service = service();
        WebFunction fn = service.createFunction(REGION, ACCOUNT_ID, createRequest("app"));
        JsonNode tree = mapper.valueToTree(fn);
        WebFunction back = mapper.convertValue(tree, WebFunction.class);
        assertEquals(fn.revisions.keySet(), back.revisions.keySet());
        assertEquals(fn.endpoints.get("dev").domainName, back.endpoints.get("dev").domainName);
    }
}
