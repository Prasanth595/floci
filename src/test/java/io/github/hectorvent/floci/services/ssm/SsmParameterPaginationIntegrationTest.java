package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class SsmParameterPaginationIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getParametersByPathPagesWithMaxResultsAndNextToken() {
        String path = "/pagination-" + UUID.randomUUID();
        List<String> names = IntStream.rangeClosed(1, 5).mapToObj(i -> path + "/p" + i).toList();
        names.forEach(name -> put(name, "v"));

        List<String> seen = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String token = null;
        do {
            Map<String, Object> request = new HashMap<>(Map.of("Path", path, "MaxResults", 2));
            if (token != null) {
                request.put("NextToken", token);
            }
            JsonPath page = ok("GetParametersByPath", request);
            List<String> pageNames = page.getList("Parameters.Name", String.class);
            pageSizes.add(pageNames.size());
            seen.addAll(pageNames);
            token = page.getString("NextToken");
        } while (token != null);

        assertEquals(List.of(2, 2, 1), pageSizes);
        assertEquals(names, seen);
    }

    @Test
    void getParametersByPathWithoutMaxResultsReturnsEverything() {
        String path = "/pagination-" + UUID.randomUUID();
        IntStream.rangeClosed(1, 12).forEach(i -> put(path + "/p" + i, "v"));

        JsonPath response = ok("GetParametersByPath", Map.of("Path", path));

        assertEquals(12, response.getList("Parameters").size());
        assertNull(response.getString("NextToken"));
    }

    @Test
    void getParametersByPathRejectsOutOfRangeMaxResultsAndBadTokens() {
        error("GetParametersByPath", Map.of("Path", "/pagination", "MaxResults", 0), "ValidationException");
        error("GetParametersByPath", Map.of("Path", "/pagination", "MaxResults", 11), "ValidationException");
        error("GetParametersByPath", Map.of("Path", "/pagination", "NextToken", "%%%"), "InvalidNextToken");
    }

    @Test
    void getParameterHistoryPagesInVersionOrder() {
        String name = "/pagination-" + UUID.randomUUID() + "/history";
        // The test profile keeps the last five versions (7 to 11), so a cursor compared as text
        // would put versions 10 and 11 before 7.
        IntStream.rangeClosed(1, 11).forEach(i -> put(name, "v" + i));

        List<Long> versions = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String token = null;
        do {
            Map<String, Object> request = new HashMap<>(Map.of("Name", name, "MaxResults", 2));
            if (token != null) {
                request.put("NextToken", token);
            }
            JsonPath page = ok("GetParameterHistory", request);
            List<Long> pageVersions = page.getList("Parameters.Version", Long.class);
            pageSizes.add(pageVersions.size());
            versions.addAll(pageVersions);
            token = page.getString("NextToken");
        } while (token != null);

        assertEquals(List.of(2, 2, 1), pageSizes);
        assertEquals(LongStream.rangeClosed(7, 11).boxed().toList(), versions);

        JsonPath all = ok("GetParameterHistory", Map.of("Name", name));
        assertEquals(5, all.getList("Parameters").size());
        assertNull(all.getString("NextToken"));
    }

    @Test
    void getParameterHistoryRejectsOutOfRangeMaxResults() {
        String name = "/pagination-" + UUID.randomUUID() + "/history";
        put(name, "v");
        error("GetParameterHistory", Map.of("Name", name, "MaxResults", 0), "ValidationException");
        error("GetParameterHistory", Map.of("Name", name, "MaxResults", 51), "ValidationException");
        error("GetParameterHistory", Map.of("Name", name, "NextToken", "%%%"), "InvalidNextToken");
    }

    @Test
    void refusesATokenMintedForAnotherListing() {
        String root = "/pagination-" + UUID.randomUUID();
        IntStream.rangeClosed(1, 3).forEach(i -> {
            put(root + "/a/p" + i, "v");
            put(root + "/b/p" + i, "v");
        });
        String first = root + "/a/p1";
        String second = root + "/b/p1";
        put(first, "v2");
        put(second, "v2");

        String pathToken = ok("GetParametersByPath", Map.of("Path", root + "/a", "MaxResults", 1))
                .getString("NextToken");
        String historyToken = ok("GetParameterHistory", Map.of("Name", first, "MaxResults", 1))
                .getString("NextToken");

        error("GetParametersByPath", Map.of("Path", root + "/b", "NextToken", pathToken), "InvalidNextToken");
        error("GetParametersByPath", Map.of("Path", root + "/a", "Recursive", true, "NextToken", pathToken),
                "InvalidNextToken");
        error("GetParametersByPath", Map.of("Path", root + "/a", "NextToken", historyToken), "InvalidNextToken");
        error("GetParameterHistory", Map.of("Name", second, "NextToken", historyToken), "InvalidNextToken");
        error("GetParameterHistory", Map.of("Name", first, "NextToken", pathToken), "InvalidNextToken");
        // The token still resumes the listing it came from, with or without a trailing slash.
        assertEquals(List.of(root + "/a/p2"), ok("GetParametersByPath",
                Map.of("Path", root + "/a/", "MaxResults", 1, "NextToken", pathToken))
                .getList("Parameters.Name", String.class));
    }

    @Test
    void rejectsWronglyTypedPaginationFields() {
        String name = "/pagination-" + UUID.randomUUID() + "/history";
        put(name, "v");
        error("GetParametersByPath", Map.of("Path", "/pagination", "MaxResults", "2"), "SerializationException");
        error("GetParametersByPath", Map.of("Path", "/pagination", "NextToken", 5), "SerializationException");
        error("GetParameterHistory", Map.of("Name", name, "MaxResults", "2"), "SerializationException");
        error("GetParameterHistory", Map.of("Name", name, "NextToken", 5), "SerializationException");
    }

    private void put(String name, String value) {
        ok("PutParameter", Map.of("Name", name, "Value", value, "Type", "String", "Overwrite", true));
    }

    private JsonPath ok(String action, Map<String, ?> body) {
        return given()
            .header("X-Amz-Target", "AmazonSSM." + action)
            .contentType(SSM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }

    private void error(String action, Map<String, ?> body, String errorType) {
        given()
            .header("X-Amz-Target", "AmazonSSM." + action)
            .contentType(SSM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo(errorType));
    }
}
