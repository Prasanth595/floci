package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RedshiftApiEventsIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void successfulSdkWriteReachesEventBridgeQueueWithoutLeakingFailedCalls() throws Exception {
        String name = "rs-api-events-" + UUID.randomUUID().toString().substring(0, 8);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("111111111111", "secret"));
        try (RedshiftClient redshift = RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
                EventBridgeClient events = EventBridgeClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build();
                SqsClient sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build()) {
            String queue = sqs.createQueue(request -> request.queueName(name)).queueUrl();
            String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queue)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            events.putRule(request -> request.name(name).eventPattern("""
                    {"source":["aws.redshift"],"detail-type":["AWS API Call via CloudTrail"],
                     "detail":{"eventName":["CreateClusterParameterGroup"]}}
                    """));
            events.putTargets(request -> request.rule(name)
                    .targets(Target.builder().id("queue").arn(queueArn).build()));
            boolean created = false;
            try {
                redshift.createClusterParameterGroup(request -> request.parameterGroupName(name)
                        .parameterGroupFamily("redshift-1.0").description("API event verification"));
                created = true;
                ObjectMapper mapper = new ObjectMapper();
                AtomicReference<JsonNode> delivered = new AtomicReference<>();
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queue)
                            .maxNumberOfMessages(1)).messages();
                    if (messages.isEmpty()) {
                        return false;
                    }
                    Message message = messages.getFirst();
                    delivered.set(mapper.readTree(message.body()));
                    sqs.deleteMessage(request -> request.queueUrl(queue).receiptHandle(message.receiptHandle()));
                    return true;
                });
                JsonNode envelope = delivered.get();
                assertEquals("aws.redshift", envelope.path("source").asText());
                assertEquals("AWS API Call via CloudTrail", envelope.path("detail-type").asText());
                assertEquals("111111111111", envelope.path("account").asText());
                assertEquals("us-east-1", envelope.path("region").asText());
                assertEquals("CreateClusterParameterGroup", envelope.path("detail").path("eventName").asText());
                assertFalse(envelope.path("detail").has("requestParameters"));
                assertThrows(RedshiftException.class, () -> redshift.createClusterParameterGroup(request -> request
                        .parameterGroupName(name).parameterGroupFamily("redshift-1.0").description("duplicate")));
                assertTrue(sqs.receiveMessage(request -> request.queueUrl(queue).waitTimeSeconds(1))
                        .messages().isEmpty());
            } finally {
                if (created) {
                    redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
                }
                events.removeTargets(request -> request.rule(name).ids("queue"));
                events.deleteRule(request -> request.name(name));
                sqs.deleteQueue(request -> request.queueUrl(queue));
            }
        }
    }
}
