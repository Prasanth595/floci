package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes carrying a legacy {@code Expected} condition, sent over HTTP so the condition goes through
 * the request handler. Concurrent writers of one version: exactly one may win, as on DynamoDB.
 */
@QuarkusTest
class DynamoDbLegacyExpectedIntegrationTest {

    private static final int REPEATS = 3;
    private static final int WRITERS = 16;
    private static final int ROUNDS = 5;

    private String table;

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTable() {
        table = "legacy-expected-" + UUID.randomUUID();
        call("CreateTable", """
                {"TableName":"%s","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(table)).then().statusCode(200);
    }

    @AfterEach
    void deleteTable() {
        call("DeleteTable", "{\"TableName\":\"" + table + "\"}").then().statusCode(200);
    }

    @RepeatedTest(REPEATS)
    void updateItem_concurrentWritersOfOneVersion_acceptsExactlyOne() throws InterruptedException {
        assertOneWriterWinsEachRound("update", (pk, writer) -> call("UpdateItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"}},
                 "AttributeUpdates":{"version":{"Action":"PUT","Value":{"N":"1"}},
                                     "writer":{"Action":"PUT","Value":{"S":"%d"}}},
                 "Expected":{"version":{"Value":{"N":"0"}}}}
                """.formatted(table, pk, writer)));
    }

    @RepeatedTest(REPEATS)
    void putItem_concurrentWritersOfOneVersion_acceptsExactlyOne() throws InterruptedException {
        assertOneWriterWinsEachRound("put", (pk, writer) -> call("PutItem", """
                {"TableName":"%s",
                 "Item":{"pk":{"S":"%s"},"version":{"N":"1"},"writer":{"S":"%d"}},
                 "Expected":{"version":{"Value":{"N":"0"}}}}
                """.formatted(table, pk, writer)));
    }

    @RepeatedTest(REPEATS)
    void deleteItem_concurrentDeletesOfOneVersion_acceptsExactlyOne() throws InterruptedException {
        assertOneWriterWinsEachRound("delete", (pk, writer) -> call("DeleteItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"}},
                 "Expected":{"version":{"Value":{"N":"0"}}}}
                """.formatted(table, pk)));
    }

    @RepeatedTest(REPEATS)
    void putItem_concurrentAllOld_returnsTheItemEachPutReplaced() throws InterruptedException {
        String pk = "all-old";
        call("PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"writer":{"S":"seed"}}}
                """.formatted(table, pk)).then().statusCode(200);

        List<Response> responses = runConcurrently(writer -> call("PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"writer":{"S":"%d"}},
                 "ReturnValues":"ALL_OLD"}
                """.formatted(table, pk, writer)));

        List<String> replaced = new ArrayList<>();
        for (Response response : responses) {
            response.then().statusCode(200);
            replaced.add(response.jsonPath().getString("Attributes.writer.S"));
        }
        replaced.add(call("GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"}},"ConsistentRead":true}
                """.formatted(table, pk)).jsonPath().getString("Item.writer.S"));

        List<String> everyValue = new ArrayList<>();
        everyValue.add("seed");
        for (int writer = 0; writer < WRITERS; writer++) {
            everyValue.add(String.valueOf(writer));
        }
        Collections.sort(replaced);
        Collections.sort(everyValue);
        assertEquals(everyValue, replaced,
                "every value but the last must be replaced by exactly one put, and the last must be stored");
    }

    @Test
    void updateItem_staleExpectedVersion_isRejected() {
        seed("stale");
        String update = """
                {"TableName":"%s","Key":{"pk":{"S":"stale"}},
                 "AttributeUpdates":{"version":{"Action":"PUT","Value":{"N":"1"}}},
                 "Expected":{"version":{"Value":{"N":"0"}}}}
                """.formatted(table);
        call("UpdateItem", update).then().statusCode(200);
        call("UpdateItem", update).then().statusCode(400)
                .body("__type", equalTo("ConditionalCheckFailedException"));
    }

    @Test
    void updateAndDelete_malformedKeyAndInvalidExpected_reportTheKeyError() {
        call("UpdateItem", """
                {"TableName":"%s","Key":{"other":{"S":"a"}},
                 "AttributeUpdates":{"version":{"Action":"PUT","Value":{"N":"1"}}},
                 "Expected":{"version":{}}}
                """.formatted(table)).then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("The provided key element does not match the schema"));
        call("DeleteItem", """
                {"TableName":"%s","Key":{"other":{"S":"a"}},"Expected":{"version":{}}}
                """.formatted(table)).then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("The provided key element does not match the schema"));
    }

    @Test
    void putItem_itemWithoutItsKey_reportsTheItemKeyErrorWithAnyReturnValuesOrExpected() {
        for (String extra : List.of("", ",\"ReturnValues\":\"ALL_OLD\"", ",\"Expected\":{\"version\":{}}")) {
            call("PutItem", """
                    {"TableName":"%s","Item":{"other":{"S":"a"}}%s}
                    """.formatted(table, extra)).then().statusCode(400)
                    .body("__type", equalTo("ValidationException"))
                    .body("message", equalTo("One of the required keys was not given a value"));
        }
    }

    @Test
    void updateItem_invalidExpectedOnAMissingTable_reportsTheMissingTable() {
        call("UpdateItem", """
                {"TableName":"absent-%s","Key":{"pk":{"S":"a"}},"Expected":{"version":{}}}
                """.formatted(UUID.randomUUID())).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private void seed(String pk) {
        call("PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"version":{"N":"0"}}}
                """.formatted(table, pk)).then().statusCode(200);
    }

    /** Seeds a fresh item at version 0 each round and races every writer at it. */
    private void assertOneWriterWinsEachRound(String keyPrefix, BiFunction<String, Integer, Response> request)
            throws InterruptedException {
        for (int round = 0; round < ROUNDS; round++) {
            String pk = keyPrefix + "-" + round;
            seed(pk);
            List<Response> responses = runConcurrently(writer -> request.apply(pk, writer));
            int accepted = 0;
            for (Response response : responses) {
                if (response.statusCode() == 200) {
                    accepted++;
                } else {
                    response.then().statusCode(400)
                            .body("__type", equalTo("ConditionalCheckFailedException"));
                }
            }
            assertEquals(1, accepted, "exactly one writer of a version may be accepted");
        }
    }

    /** Sends one request per writer, all released together. */
    private static List<Response> runConcurrently(IntFunction<Response> request) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Response> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int writer = 0; writer < WRITERS; writer++) {
                int id = writer;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        responses.add(request.apply(id));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            startGate.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "concurrent requests did not complete");
        }
        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertEquals(WRITERS, responses.size());
        return responses;
    }

    private static Response call(String action, String body) {
        return given().header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/");
    }
}
