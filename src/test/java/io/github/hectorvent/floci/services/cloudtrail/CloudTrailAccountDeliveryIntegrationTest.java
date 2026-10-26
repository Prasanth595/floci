package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void equalTrailNamesKeepAccountBuffersAndBackgroundDeliverySeparate(boolean deleteFirstTrail) throws Exception {
        String name = "account-events-" + UUID.randomUUID();
        Map<String, String> buckets = Map.of(FIRST_ACCOUNT, name + "-first", SECOND_ACCOUNT, name + "-second");
        try {
            buckets.forEach((account, bucket) -> RequestScopes.runAs(account, REGION, () -> {
                s3.createBucket(bucket, REGION);
                trails.createTrail(REGION, name, bucket, null, null, false, false, false, false);
                trails.putEventSelectors(REGION, name, List.of(new EventSelector("All", false,
                        List.of(new DataResource("AWS::S3::Object", List.of("arn:aws:s3:::source/"))), List.of())));
            }));
            RequestScopes.runAs(FIRST_ACCOUNT, REGION, () -> trails.startLogging(REGION, name));
            RequestScopes.runAs(SECOND_ACCOUNT, REGION, () -> {
                trails.emitS3DataEvent(event());
                assertEquals(0, trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)),
                        "another account's trail cannot enable events");
                trails.startLogging(REGION, name);
            });
            for (String account : buckets.keySet()) {
                RequestScopes.runAs(account, REGION, () -> {
                    trails.emitS3DataEvent(event());
                    assertEquals(1, trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)));
                });
            }
            if (deleteFirstTrail) {
                RequestScopes.runAs(FIRST_ACCOUNT, REGION, () -> trails.deleteTrail(REGION, name));
            }
            RequestScopes.runAs(SECOND_ACCOUNT, REGION, () -> assertEquals(1,
                    trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION)),
                    "deleting one account's trail must preserve the other account's pending records"));

            try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
                worker.submit(writer::flushNow).get(10, TimeUnit.SECONDS);
            }

            for (String account : buckets.keySet()) {
                List<S3Object> objects = RequestScopes.callAs(account, REGION,
                        () -> s3.listObjects(buckets.get(account), null, null, 100));
                if (deleteFirstTrail && FIRST_ACCOUNT.equals(account)) {
                    assertTrue(objects.isEmpty());
                    continue;
                }
                assertEquals(1, objects.size(), "each account must receive its own log file");
                assertTrue(objects.getFirst().getKey().startsWith("AWSLogs/" + account + "/CloudTrail/" + REGION + "/"));
                byte[] content = RequestScopes.callAs(account, REGION,
                        () -> s3.getObject(buckets.get(account), objects.getFirst().getKey()).getData());
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
                    JsonNode records = mapper.readTree(gzip).path("Records");
                    assertEquals(1, records.size());
                    assertEquals("Data", records.get(0).path("eventCategory").asText());
                    assertEquals(account, records.get(0).path("recipientAccountId").asText());
                    assertEquals(account, records.get(0).path("userIdentity").path("accountId").asText());
                }
                RequestScopes.runAs(account, REGION, () -> assertEquals(0,
                        trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION))));
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

    private CloudTrailService.S3EventInput event() {
        return CloudTrailService.S3EventInput.builder()
                .region(REGION).eventName("PutObject").bucketName("source").key("example.txt").build();
    }
}
