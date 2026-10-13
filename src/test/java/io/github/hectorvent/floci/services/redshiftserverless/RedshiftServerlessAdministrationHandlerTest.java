package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.RedshiftIamDbUserResolver;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the metadata-only Redshift Serverless actions through the handler, so both the stored
 * behavior and the response shape the SDKs deserialize are checked.
 */
class RedshiftServerlessAdministrationHandlerTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:123456789012:certificate/11111111-2222-3333-4444-555555555555";

    private final ObjectMapper mapper = new ObjectMapper();
    private RedshiftServerlessJsonHandler handler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        for (String file : List.of("namespaces", "workgroups", "snapshots", "usagelimits", "resourcepolicies",
                "endpointaccess", "snapshotcopyconfigurations", "scheduledactions", "customdomains")) {
            when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-" + file + ".json"),
                    any(TypeReference.class))).thenReturn((AccountAwareStorageBackend)
                    AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        }
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(eq("redshift-serverless"), any(String.class), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:"
                        + invocation.getArgument(1, String.class) + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        RedshiftServerlessEndpoints endpoints = mock(RedshiftServerlessEndpoints.class);
        when(endpoints.allocate()).thenReturn(new Endpoint("localhost", 7100));
        RedshiftServerlessRuntime runtime = mock(RedshiftServerlessRuntime.class);
        when(runtime.generatePassword()).thenReturn("Generated123");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(System.getProperty("java.io.tmpdir"));
        RedshiftServerlessService service =
                new RedshiftServerlessService(storageFactory, regionResolver, endpoints, runtime, config);
        handler = new RedshiftServerlessJsonHandler(service, mapper, mock(RedshiftIamDbUserResolver.class),
                new RedshiftServerlessUsageLimitService(storageFactory, regionResolver, service),
                new RedshiftServerlessAdminJsonHandler(mapper,
                        new RedshiftServerlessResourcePolicyService(storageFactory, service, mapper),
                        new RedshiftServerlessEndpointAccessService(storageFactory, regionResolver, service),
                        new RedshiftServerlessSnapshotCopyService(storageFactory, regionResolver, service),
                        new RedshiftServerlessScheduledActionService(storageFactory, service),
                        new RedshiftServerlessCustomDomainService(storageFactory, service)));
    }

    @Test
    void resourcePolicyIsStoredPerSnapshotAndDroppedWithIt() {
        String snapshotArn = snapshot("policy-ns", "policy-snap");
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

        JsonNode put = ok("PutResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":"
                + mapper.valueToTree(policy) + "}").get("resourcePolicy");
        assertEquals(snapshotArn, put.get("resourceArn").textValue());
        assertEquals(policy, put.get("policy").textValue());
        assertEquals(policy, ok("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}")
                .get("resourcePolicy").get("policy").textValue());

        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":\"not json\"}"));
        assertEquals("ResourceNotFoundException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID
                        + ":snapshot/missing\",\"policy\":" + mapper.valueToTree(policy) + "}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\",\"policy\":"
                        + mapper.valueToTree(policy) + "}"));

        assertEquals(0, ok("DeleteResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}").size());
        assertEquals("ResourceNotFoundException",
                errorType("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}"));

        ok("PutResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":"
                + mapper.valueToTree(policy) + "}");
        ok("DeleteSnapshot", "{\"snapshotName\":\"policy-snap\"}");
        assertEquals("ResourceNotFoundException",
                errorType("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}"));
    }

    @Test
    void endpointAccessFollowsTheApiModelAndTheWorkgroupLifecycle() {
        workgroup("ep-ns", "ep-wg");

        JsonNode created = ok("CreateEndpointAccess", "{\"endpointName\":\"ep-one\",\"workgroupName\":\"ep-wg\","
                + "\"subnetIds\":[\"subnet-a\",\"subnet-b\"],\"vpcSecurityGroupIds\":[\"sg-1\"]}").get("endpoint");
        assertEquals("ep-one", created.get("endpointName").textValue());
        assertEquals("ep-wg", created.get("workgroupName").textValue());
        assertEquals("ACTIVE", created.get("endpointStatus").textValue());
        assertEquals("localhost", created.get("address").textValue());
        assertEquals(7100, created.get("port").intValue());
        assertTrue(created.get("endpointCreateTime").textValue().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"));
        assertTrue(created.get("endpointArn").textValue().contains(":managedvpcendpoint/"));
        assertEquals(2, created.get("subnetIds").size());
        assertEquals(2, created.get("vpcEndpoint").get("networkInterfaces").size());
        assertEquals("subnet-a", created.get("vpcEndpoint").get("networkInterfaces").get(0).get("subnetId").textValue());
        assertEquals("sg-1", created.get("vpcSecurityGroups").get(0).get("vpcSecurityGroupId").textValue());
        assertEquals("active", created.get("vpcSecurityGroups").get(0).get("status").textValue());

        assertEquals("ConflictException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-one\",\"workgroupName\":\"ep-wg\",\"subnetIds\":[\"subnet-a\"]}"));
        assertEquals("ValidationException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-two\",\"workgroupName\":\"ep-wg\"}"));
        assertEquals("ResourceNotFoundException", errorType("CreateEndpointAccess",
                "{\"endpointName\":\"ep-two\",\"workgroupName\":\"nope\",\"subnetIds\":[\"subnet-a\"]}"));

        JsonNode updated = ok("UpdateEndpointAccess",
                "{\"endpointName\":\"ep-one\",\"vpcSecurityGroupIds\":[\"sg-2\",\"sg-3\"]}").get("endpoint");
        assertEquals(2, updated.get("vpcSecurityGroups").size());
        assertEquals(1, ok("ListEndpointAccess", "{\"workgroupName\":\"ep-wg\"}").get("endpoints").size());
        assertEquals(0, ok("ListEndpointAccess", "{\"workgroupName\":\"other\"}").get("endpoints").size());
        assertEquals(0, ok("ListEndpointAccess", "{\"vpcId\":\"vpc-1\"}").get("endpoints").size());

        assertEquals("DELETING", ok("DeleteEndpointAccess", "{\"endpointName\":\"ep-one\"}")
                .get("endpoint").get("endpointStatus").textValue());
        assertEquals("ResourceNotFoundException", errorType("GetEndpointAccess", "{\"endpointName\":\"ep-one\"}"));

        ok("CreateEndpointAccess", "{\"endpointName\":\"ep-gone\",\"workgroupName\":\"ep-wg\","
                + "\"subnetIds\":[\"subnet-a\"]}");
        ok("DeleteWorkgroup", "{\"workgroupName\":\"ep-wg\"}");
        assertEquals("ResourceNotFoundException", errorType("GetEndpointAccess", "{\"endpointName\":\"ep-gone\"}"));
    }

    @Test
    void snapshotCopyConfigurationIsPerNamespaceAndDestination() {
        namespace("copy-ns");

        JsonNode created = ok("CreateSnapshotCopyConfiguration", "{\"namespaceName\":\"copy-ns\","
                + "\"destinationRegion\":\"us-west-2\",\"snapshotRetentionPeriod\":14,"
                + "\"destinationKmsKeyId\":\"key-1\"}").get("snapshotCopyConfiguration");
        String id = created.get("snapshotCopyConfigurationId").textValue();
        assertEquals("copy-ns", created.get("namespaceName").textValue());
        assertEquals("us-west-2", created.get("destinationRegion").textValue());
        assertEquals("key-1", created.get("destinationKmsKeyId").textValue());
        assertEquals(14, created.get("snapshotRetentionPeriod").intValue());
        assertTrue(created.get("snapshotCopyConfigurationArn").textValue().contains(":snapshotcopyconfiguration/"));

        assertEquals("ConflictException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"us-west-2\"}"));
        assertEquals("ValidationException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"copy-ns\",\"destinationRegion\":\"us-east-1\"}"));
        assertEquals("ResourceNotFoundException", errorType("CreateSnapshotCopyConfiguration",
                "{\"namespaceName\":\"nope-ns\",\"destinationRegion\":\"us-west-2\"}"));

        assertEquals(30, ok("UpdateSnapshotCopyConfiguration", "{\"snapshotCopyConfigurationId\":\"" + id
                + "\",\"snapshotRetentionPeriod\":30}").get("snapshotCopyConfiguration")
                .get("snapshotRetentionPeriod").intValue());
        assertEquals("ValidationException", errorType("UpdateSnapshotCopyConfiguration",
                "{\"snapshotCopyConfigurationId\":\"" + id + "\",\"snapshotRetentionPeriod\":0}"));
        assertEquals(1, ok("ListSnapshotCopyConfigurations", "{\"namespaceName\":\"copy-ns\"}")
                .get("snapshotCopyConfigurations").size());

        assertEquals(id, ok("DeleteSnapshotCopyConfiguration", "{\"snapshotCopyConfigurationId\":\"" + id + "\"}")
                .get("snapshotCopyConfiguration").get("snapshotCopyConfigurationId").textValue());
        assertEquals(0, ok("ListSnapshotCopyConfigurations", "{}").get("snapshotCopyConfigurations").size());
    }

    @Test
    void scheduledActionIsStoredAndNeverRun() {
        namespace("sched-ns");
        long future = Instant.now().plusSeconds(3600).getEpochSecond();
        String target = "\"targetAction\":{\"createSnapshot\":{\"namespaceName\":\"sched-ns\","
                + "\"snapshotNamePrefix\":\"nightly\",\"retentionPeriod\":7,"
                + "\"tags\":[{\"key\":\"env\",\"value\":\"dev\"}]}}";

        JsonNode created = ok("CreateScheduledAction", "{\"scheduledActionName\":\"nightly-snap\","
                + "\"namespaceName\":\"sched-ns\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT_ID + ":role/r\","
                + "\"schedule\":{\"cron\":\"cron(0 3 * * ? *)\"}," + target + ","
                + "\"scheduledActionDescription\":\"nightly\"}").get("scheduledAction");
        assertEquals("ACTIVE", created.get("state").textValue());
        assertEquals("cron(0 3 * * ? *)", created.get("schedule").get("cron").textValue());
        assertEquals("nightly", created.get("targetAction").get("createSnapshot").get("snapshotNamePrefix").textValue());
        assertEquals(7, created.get("targetAction").get("createSnapshot").get("retentionPeriod").intValue());
        assertEquals("env", created.get("targetAction").get("createSnapshot").get("tags").get(0).get("key").textValue());
        assertEquals("nightly", created.get("scheduledActionDescription").textValue());
        assertEquals(0, created.get("nextInvocations").size());
        assertTrue(created.get("scheduledActionUuid").textValue().length() > 8);

        assertEquals("ConflictException", errorType("CreateScheduledAction", "{\"scheduledActionName\":\"nightly-snap\","
                + "\"namespaceName\":\"sched-ns\",\"roleArn\":\"r\",\"schedule\":{\"cron\":\"cron(0 3 * * ? *)\"},"
                + target + "}"));
        assertEquals("ValidationException", errorType("CreateScheduledAction", "{\"scheduledActionName\":\"both-sched\","
                + "\"namespaceName\":\"sched-ns\",\"roleArn\":\"r\",\"schedule\":{\"cron\":\"x\",\"at\":" + future
                + "}," + target + "}"));
        assertEquals("ValidationException", errorType("CreateScheduledAction", "{\"scheduledActionName\":\"Bad_Name\","
                + "\"namespaceName\":\"sched-ns\",\"roleArn\":\"r\",\"schedule\":{\"cron\":\"x\"}," + target + "}"));
        assertEquals("ResourceNotFoundException", errorType("CreateScheduledAction",
                "{\"scheduledActionName\":\"no-ns-sched\",\"namespaceName\":\"nope-ns\",\"roleArn\":\"r\","
                        + "\"schedule\":{\"cron\":\"x\"}," + target + "}"));

        JsonNode oneOff = ok("UpdateScheduledAction", "{\"scheduledActionName\":\"nightly-snap\","
                + "\"enabled\":false,\"schedule\":{\"at\":" + future + "}}").get("scheduledAction");
        assertEquals("DISABLED", oneOff.get("state").textValue());
        assertEquals(1, oneOff.get("nextInvocations").size());
        assertEquals(future, oneOff.get("schedule").get("at").longValue());
        assertEquals("sched-ns", oneOff.get("namespaceName").textValue());

        JsonNode listed = ok("ListScheduledActions", "{\"namespaceName\":\"sched-ns\"}").get("scheduledActions");
        assertEquals(1, listed.size());
        assertEquals("nightly-snap", listed.get(0).get("scheduledActionName").textValue());
        assertEquals("nightly-snap", ok("DeleteScheduledAction", "{\"scheduledActionName\":\"nightly-snap\"}")
                .get("scheduledAction").get("scheduledActionName").textValue());
        assertEquals("ResourceNotFoundException",
                errorType("GetScheduledAction", "{\"scheduledActionName\":\"nightly-snap\"}"));
    }

    @Test
    void customDomainAssociationIsKeyedByWorkgroupAndDomain() {
        workgroup("domain-ns", "domain-wg");
        String request = "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\","
                + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}";

        JsonNode created = ok("CreateCustomDomainAssociation", request);
        assertEquals("db.example.com", created.get("customDomainName").textValue());
        assertEquals("domain-wg", created.get("workgroupName").textValue());
        assertEquals(CERTIFICATE_ARN, created.get("customDomainCertificateArn").textValue());

        assertEquals("ConflictException", errorType("CreateCustomDomainAssociation", request));
        assertEquals("ValidationException", errorType("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"-bad\","
                        + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}"));
        assertEquals("ValidationException", errorType("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"other.example.com\","
                        + "\"customDomainCertificateArn\":\"not-an-arn\"}"));
        assertEquals("ResourceNotFoundException", errorType("CreateCustomDomainAssociation",
                "{\"workgroupName\":\"nope\",\"customDomainName\":\"x.example.com\","
                        + "\"customDomainCertificateArn\":\"" + CERTIFICATE_ARN + "\"}"));

        String replacement = CERTIFICATE_ARN.replace("11111111", "99999999");
        assertEquals(replacement, ok("UpdateCustomDomainAssociation", "{\"workgroupName\":\"domain-wg\","
                + "\"customDomainName\":\"db.example.com\",\"customDomainCertificateArn\":\"" + replacement + "\"}")
                .get("customDomainCertificateArn").textValue());
        assertEquals(replacement, ok("GetCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}")
                .get("customDomainCertificateArn").textValue());
        assertEquals(1, ok("ListCustomDomainAssociations", "{\"customDomainName\":\"db.example.com\"}")
                .get("associations").size());
        assertEquals(0, ok("ListCustomDomainAssociations", "{\"customDomainName\":\"x.example.com\"}")
                .get("associations").size());

        assertEquals(0, ok("DeleteCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}").size());
        assertEquals("ResourceNotFoundException", errorType("GetCustomDomainAssociation",
                "{\"workgroupName\":\"domain-wg\",\"customDomainName\":\"db.example.com\"}"));
    }

    @Test
    void tracksAreTheThreeAwsReleaseTracks() {
        JsonNode track = ok("GetTrack", "{\"trackName\":\"trailing\"}").get("track");
        assertEquals("trailing", track.get("trackName").textValue());
        assertEquals(0, track.get("updateTargets").size());
        assertEquals("ResourceNotFoundException", errorType("GetTrack", "{\"trackName\":\"nightly\"}"));
        assertEquals("ValidationException", errorType("GetTrack", "{\"trackName\":\"not valid!\"}"));

        JsonNode page = ok("ListTracks", "{\"maxResults\":2}");
        assertEquals(2, page.get("tracks").size());
        String next = page.get("nextToken").textValue();
        JsonNode rest = ok("ListTracks", "{\"nextToken\":\"" + next + "\"}");
        assertEquals(1, rest.get("tracks").size());
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":\"x\"}"));
    }

    @Test
    void managedWorkgroupsAndReservationsAreEmptyBecauseFlociModelsNone() {
        assertEquals(0, ok("ListManagedWorkgroups", "{}").get("managedWorkgroups").size());
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"sourceArn\":\"nope\"}"));
        assertEquals(0, ok("ListManagedWorkgroups", "{\"sourceArn\":\"arn:aws:glue:us-east-1:123456789012:"
                + "database/db\"}").get("managedWorkgroups").size());

        assertEquals(0, ok("ListReservations", "{}").get("reservationsList").size());
        assertEquals(0, ok("ListReservationOfferings", "{}").get("reservationOfferingsList").size());
        assertEquals("ResourceNotFoundException", errorType("GetReservation", "{\"reservationId\":\"r-1\"}"));
        assertEquals("ResourceNotFoundException", errorType("GetReservationOffering", "{\"offeringId\":\"o-1\"}"));
        assertEquals("ResourceNotFoundException",
                errorType("CreateReservation", "{\"capacity\":128,\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException", errorType("CreateReservation", "{\"offeringId\":\"o-1\"}"));
    }

    @Test
    void anUnknownActionIsStillRejected() {
        assertEquals("UnknownOperationException", errorType("NoSuchOperation", "{}"));
    }

    private void namespace(String name) {
        ok("CreateNamespace", "{\"namespaceName\":\"" + name + "\",\"adminUsername\":\"admin\"}");
    }

    private void workgroup(String namespaceName, String workgroupName) {
        namespace(namespaceName);
        ok("CreateWorkgroup", "{\"workgroupName\":\"" + workgroupName + "\",\"namespaceName\":\""
                + namespaceName + "\"}");
    }

    private String snapshot(String namespaceName, String snapshotName) {
        namespace(namespaceName);
        return ok("CreateSnapshot", "{\"snapshotName\":\"" + snapshotName + "\",\"namespaceName\":\""
                + namespaceName + "\"}").get("snapshot").get("snapshotArn").textValue();
    }

    private JsonNode ok(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(200, response.getStatus(), action + " failed: " + response.getEntity());
        return mapper.valueToTree(response.getEntity());
    }

    private String errorType(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(400, response.getStatus(), action + " should be rejected: " + response.getEntity());
        return mapper.valueToTree(response.getEntity()).path("__type").asText(null);
    }

    private Response invoke(String action, String json) {
        try {
            return handler.handle(action, mapper.readTree(json), REGION);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
