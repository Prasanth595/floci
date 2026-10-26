package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudTrailAccountDeliveryIntegrationTest {
    private static final String REGION = "eu-west-1";
    private static final String FIRST_ACCOUNT = "661100000004";
    private static final String SECOND_ACCOUNT = "661100000005";

    @Inject
    CloudTrailService trails;

    @Inject
    CloudTrailLogWriter writer;

    @Inject
    S3Service s3;

    @Inject
    ObjectMapper mapper;

    @Test
    void equalTrailNamesKeepAccountBuffersAndBackgroundDeliverySeparate() throws Exception {
        String name = "account-events-" + UUID.randomUUID();
        Map<String, String> buckets = Map.of(FIRST_ACCOUNT, name + "-first", SECOND_ACCOUNT, name + "-second");
        try {
            buckets.forEach((account, bucket) -> RequestScopes.runAs(account, REGION, () -> {
                s3.createBucket(bucket, REGION);
                trails.createTrail(REGION, name, bucket, null, null, false, false, false, false);
            }));
            RequestScopes.runAs(FIRST_ACCOUNT, REGION, () -> trails.startLogging(REGION, name));
            RequestScopes.runAs(SECOND_ACCOUNT, REGION, () -> {
                assertTrue(trails.emitManagementEvent(event()).isEmpty(), "another account's trail cannot enable events");
                trails.startLogging(REGION, name);
            });
            for (String account : buckets.keySet()) {
                RequestScopes.runAs(account, REGION, () -> {
                    assertTrue(trails.emitManagementEvent(event()).isPresent());
                    assertEquals(1, trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)));
                });
            }
            RequestScopes.runAs(FIRST_ACCOUNT, REGION, () -> trails.deleteTrail(REGION, name));
            RequestScopes.runAs(SECOND_ACCOUNT, REGION, () -> assertEquals(1,
                    trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)),
                    "deleting one account's trail must preserve the other account's pending records"));

            writer.flushNow();

            RequestScopes.runAs(FIRST_ACCOUNT, REGION, () -> assertTrue(
                    s3.listObjects(buckets.get(FIRST_ACCOUNT), null, null, 100).isEmpty()));
            List<S3Object> objects = RequestScopes.callAs(SECOND_ACCOUNT, REGION,
                    () -> s3.listObjects(buckets.get(SECOND_ACCOUNT), null, null, 100));
            assertEquals(1, objects.size());
            assertTrue(objects.getFirst().getKey().startsWith("AWSLogs/" + SECOND_ACCOUNT + "/CloudTrail/" + REGION + "/"));
            byte[] content = RequestScopes.callAs(SECOND_ACCOUNT, REGION,
                    () -> s3.getObject(buckets.get(SECOND_ACCOUNT), objects.getFirst().getKey()).getData());
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
                JsonNode records = mapper.readTree(gzip).path("Records");
                assertEquals(1, records.size());
                assertEquals(SECOND_ACCOUNT, records.get(0).path("recipientAccountId").asText());
            }
        } finally {
            buckets.forEach((account, bucket) -> RequestScopes.runAs(account, REGION, () -> {
                if (trails.getTrail(REGION, name) != null) {
                    trails.deleteTrail(REGION, name);
                }
                for (S3Object object : s3.listObjects(bucket, null, null, 100)) {
                    s3.deleteObject(bucket, object.getKey());
                }
                s3.deleteBucket(bucket);
            }));
        }
    }

    private CloudTrailService.ManagementEventInput event() {
        return new CloudTrailService.ManagementEventInput("secretsmanager.amazonaws.com", "UpdateSecret", REGION, "test",
                mapper.createObjectNode().put("secretId", "example"), mapper.createObjectNode().put("name", "example"));
    }
}
