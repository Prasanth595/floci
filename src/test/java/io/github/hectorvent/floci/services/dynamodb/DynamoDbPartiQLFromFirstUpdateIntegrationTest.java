package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * PartiQL's FROM-first form of UPDATE, {@code FROM "T" WHERE key SET ... REMOVE ...}, which
 * DynamoDB runs as the same update as {@code UPDATE "T" SET ... WHERE key}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbPartiQLFromFirstUpdateIntegrationTest {

    private static final String TABLE = "partiql-from-first-update";
    private static final String FROM = "FROM \"" + TABLE + "\" ";
    private static final String NOT_WELL_FORMED = "Statement wasn't well formed, can't be processed: ";
    private static final String MISSING_KEY = "Where clause does not contain a mandatory equality on all key attributes";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void updatesTheItemTheWhereNames() {
        ddb("CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName":"pk","AttributeType":"S"}],
                  "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);
        for (String pk : List.of("ff-set", "ff-remove", "ff-both", "ff-lower")) {
            seed(pk);
        }

        statement(FROM + "WHERE pk = 'ff-set' SET c = 1").statusCode(200);
        getItem("ff-set").body("Item.c.N", equalTo("1")).body("Item.b.S", equalTo("bee"));

        statement(FROM + "WHERE pk = 'ff-remove' REMOVE b").statusCode(200);
        getItem("ff-remove").body("Item.b", nullValue()).body("Item.a.N", equalTo("1"));

        statement(FROM + "WHERE pk = 'ff-both' SET c = 2 REMOVE a").statusCode(200);
        getItem("ff-both").body("Item.c.N", equalTo("2")).body("Item.a", nullValue());

        statement("from \"" + TABLE + "\" where pk = 'ff-lower' set e = 4").statusCode(200);
        getItem("ff-lower").body("Item.e.N", equalTo("4"));
    }

    @Test
    @Order(2)
    void returnsWhatTheReturningClauseAsks() {
        seed("ff-returning");

        statement(FROM + "WHERE pk = 'ff-returning' SET d = 3 RETURNING ALL NEW *")
            .statusCode(200)
            .body("Items[0].d.N", equalTo("3"))
            .body("Items[0].a.N", equalTo("1"));
        statement(FROM + "WHERE pk = 'ff-returning' SET d = 4 RETURNING MODIFIED OLD *")
            .statusCode(200)
            .body("Items[0].d.N", equalTo("3"))
            .body("Items[0].a", nullValue());
    }

    @Test
    @Order(3)
    void bindsParameters() {
        seed("ff-params");

        ddb("ExecuteStatement", "{\"Statement\":" + quoted(FROM + "WHERE pk = ? SET c = ?")
                + ",\"Parameters\":[{\"S\":\"ff-params\"},{\"N\":\"7\"}]}")
            .statusCode(200);
        getItem("ff-params").body("Item.c.N", equalTo("7"));
    }

    @Test
    @Order(4)
    void treatsAFurtherConditionAsAConditionOnTheWrite() {
        seed("ff-condition");

        statement(FROM + "WHERE pk = 'ff-condition' AND a = 1 SET c = 5").statusCode(200);
        getItem("ff-condition").body("Item.c.N", equalTo("5"));

        statement(FROM + "WHERE pk = 'ff-condition' AND a = 2 SET c = 6")
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"))
            .body("message", equalTo("The conditional request failed"));
        getItem("ff-condition").body("Item.c.N", equalTo("5"));

        statement(FROM + "WHERE pk = 'ff-absent' SET c = 1")
            .statusCode(400)
            .body("__type", equalTo("ConditionalCheckFailedException"));
        getItem("ff-absent").body("Item", nullValue());
    }

    @Test
    @Order(5)
    void refusesAStatementThatDoesNotPinTheKeyOrIsOutOfOrder() {
        for (String partiql : List.of(FROM + "SET c = 1", FROM + "REMOVE a", FROM + "WHERE a = 1 SET c = 1")) {
            statement(partiql)
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(MISSING_KEY));
        }
        statement(FROM + "SET c = 1 WHERE pk = 'ff-set'")
            .statusCode(400)
            .body("message", equalTo(NOT_WELL_FORMED + "Unexpected token after expression"));
        for (String partiql : List.of(FROM + "WHERE pk = 'ff-set'", FROM + "WHERE pk = 'ff-set' RETURNING ALL OLD *")) {
            statement(partiql)
                .statusCode(400)
                .body("message", equalTo(NOT_WELL_FORMED + "Expected data manipulation"));
        }
        statement("FROM \"" + TABLE + "\".\"idx\" WHERE pk = 'ff-set' SET c = 1")
            .statusCode(400)
            .body("message", equalTo("This operation is not supported on an index"));
        statement("FROM \"" + TABLE + "-never\" WHERE pk = 'ff-set' SET c = 1")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(6)
    void runsInsideATransactionAndABatch() {
        seed("ff-transaction");
        seed("ff-batch");

        ddb("ExecuteTransaction", "{\"TransactStatements\":[{\"Statement\":"
                + quoted(FROM + "WHERE pk = 'ff-transaction' SET c = 1") + "}]}")
            .statusCode(200);
        getItem("ff-transaction").body("Item.c.N", equalTo("1"));
        ddb("ExecuteTransaction", "{\"TransactStatements\":[{\"Statement\":"
                + quoted(FROM + "WHERE pk = 'ff-transaction' SET c = 2 RETURNING ALL NEW *") + "}]}")
            .statusCode(400)
            .body("message", equalTo("Validation failed in TransactStatements[0]: "
                    + "RETURNING clause is not supported in ExecuteTransaction."));

        ddb("BatchExecuteStatement", "{\"Statements\":[{\"Statement\":"
                + quoted(FROM + "WHERE pk = 'ff-batch' SET c = 1") + "},{\"Statement\":"
                + quoted(FROM + "WHERE pk = 'ff-batch' AND a = 2 SET c = 3") + "},{\"Statement\":"
                + quoted(FROM + "SET c = 1") + "}]}")
            .statusCode(200)
            .body("Responses[0].Error", nullValue())
            .body("Responses[0].TableName", equalTo(TABLE))
            .body("Responses[1].Error.Code", equalTo("ConditionalCheckFailed"))
            .body("Responses[2].Error.Code", equalTo("ValidationError"))
            .body("Responses[2].Error.Message", equalTo(MISSING_KEY));
        getItem("ff-batch").body("Item.c.N", equalTo("1"));
    }

    private static void seed(String pk) {
        ddb("PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"a":{"N":"1"},"b":{"S":"bee"}}}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }

    private static ValidatableResponse statement(String partiql) {
        return ddb("ExecuteStatement", "{\"Statement\":" + quoted(partiql) + "}");
    }

    private static String quoted(String text) {
        return JsonNodeFactory.instance.textNode(text).toString();
    }

    private static ValidatableResponse getItem(String pk) {
        return ddb("GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"}},"ConsistentRead":true}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }
}
