package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;

/**
 * A set comes back in DynamoDB's order however it was written: strings by code point,
 * numbers by value, binaries by unsigned byte.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbSetOrderIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE = "set-member-order";
    private static final String KEY = "{\"pk\":{\"S\":\"a\"}}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putItemStoresEverySetInDynamoDbOrder() {
        request("DynamoDB_20120810.CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName":"pk","AttributeType":"S"}],
                  "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);
        request("DynamoDB_20120810.PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"a"},
                  "ss":{"SS":["b","a","B","\\uff5e","\\ud83d\\ude00"]},
                  "ns":{"NS":["10","9","1e2","-1"]},
                  "bs":{"BS":["/w==","gA==","fw==","AQ==","AP8="]},
                  "m":{"M":{"deep":{"SS":["b","a",""]}}},
                  "l":{"L":[{"NS":["2","1"]}]}}}
                """.formatted(TABLE))
            .statusCode(200);
        getItem()
            .body("Item.ss.SS", contains("B", "a", "b", "～", "😀"))
            .body("Item.ns.NS", contains("-1", "9", "10", "100"))
            .body("Item.bs.BS", contains("AP8=", "AQ==", "fw==", "gA==", "/w=="))
            .body("Item.m.M.deep.SS", contains("", "a", "b"))
            .body("Item.l.L[0].NS", contains("1", "2"));
    }

    @Test
    @Order(2)
    void updateExpressionsKeepTheOrder() {
        updateItem("\"UpdateExpression\":\"ADD ss :s, ns :n SET s2 = :v\",\"ReturnValues\":\"ALL_NEW\","
                + "\"ExpressionAttributeValues\":{\":s\":{\"SS\":[\"c\",\"\"]},\":n\":{\"NS\":[\"5\",\"-3\"]},"
                + "\":v\":{\"SS\":[\"z\",\"y\"]}}")
            .statusCode(200)
            .body("Attributes.ss.SS", contains("", "B", "a", "b", "c", "～", "😀"))
            .body("Attributes.ns.NS", contains("-3", "-1", "5", "9", "10", "100"))
            .body("Attributes.s2.SS", contains("y", "z"));
        updateItem("\"AttributeUpdates\":{\"s2\":{\"Action\":\"ADD\",\"Value\":{\"SS\":[\"x\"]}}},"
                + "\"ReturnValues\":\"UPDATED_NEW\"")
            .statusCode(200)
            .body("Attributes.s2.SS", contains("x", "y", "z"));
        getItem()
            .body("Item.ss.SS", contains("", "B", "a", "b", "c", "～", "😀"))
            .body("Item.s2.SS", contains("x", "y", "z"));
    }

    @Test
    @Order(3)
    void batchAndTransactWritesKeepTheOrder() {
        request("DynamoDB_20120810.BatchWriteItem", """
                {"RequestItems":{"%s":[{"PutRequest":{"Item":{"pk":{"S":"batch"},"ss":{"SS":["b","a"]}}}}]}}
                """.formatted(TABLE))
            .statusCode(200);
        request("DynamoDB_20120810.TransactWriteItems", """
                {"TransactItems":[
                  {"Put":{"TableName":"%1$s","Item":{"pk":{"S":"tx"},"ns":{"NS":["2","1"]}}}},
                  {"Update":{"TableName":"%1$s","Key":{"pk":{"S":"tx2"}},"UpdateExpression":"ADD ss :v",
                    "ExpressionAttributeValues":{":v":{"SS":["d","c"]}}}}]}
                """.formatted(TABLE))
            .statusCode(200);
        getItem("batch").body("Item.ss.SS", contains("a", "b"));
        getItem("tx").body("Item.ns.NS", contains("1", "2"));
        getItem("tx2").body("Item.ss.SS", contains("c", "d"));
    }

    private static ValidatableResponse getItem() {
        return getItem("a");
    }

    private static ValidatableResponse getItem(String pk) {
        return request("DynamoDB_20120810.GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"%s"}},"ConsistentRead":true}
                """.formatted(TABLE, pk))
            .statusCode(200);
    }

    private static ValidatableResponse updateItem(String body) {
        return request("DynamoDB_20120810.UpdateItem",
                "{\"TableName\":\"" + TABLE + "\",\"Key\":" + KEY + "," + body + "}");
    }

    private static ValidatableResponse request(String target, String body) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", target)
            .body(body)
            .when()
            .post("/")
            .then();
    }
}
