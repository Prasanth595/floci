package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AthenaDataCatalogPaginationIntegrationTest {

    private static final String ACCOUNT = "741666100001";
    private static final String REGION = "eu-central-2";
    private final List<String> created = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        for (String name : created) {
            delete(name);
        }
    }

    @Test
    void requestedPagesKeepTheBuiltInFirstAndResumeWithoutDuplicates() {
        create("zzz-last");
        create("000-before-the-built-in");
        create("middle");
        Map<String, Object> first = list(REGION, ACCOUNT, "{\"MaxResults\":2}", 200);
        assertEquals(List.of(AthenaService.DEFAULT_CATALOG, "000-before-the-built-in"), names(first));
        String token = (String) first.get("NextToken");
        assertNotNull(token);
        Map<String, Object> last = list(REGION, ACCOUNT, tokenRequest(token), 200);
        assertEquals(List.of("middle", "zzz-last"), names(last));
        assertFalse(last.containsKey("NextToken"));
    }

    @Test
    void omittedNullAndMaximumPageSizesUseTheBoundedDefault() {
        for (int i = 0; i < 50; i++) {
            create("catalog-" + i);
        }
        for (String body : List.of("{}", "{\"MaxResults\":null}", "{\"MaxResults\":50}")) {
            Map<String, Object> first = list(REGION, ACCOUNT, body, 200);
            assertEquals(50, names(first).size());
            String token = (String) first.get("NextToken");
            assertNotNull(token);
            Map<String, Object> last = list(REGION, ACCOUNT, tokenRequest(token), 200);
            assertEquals(1, names(last).size());
            assertFalse(last.containsKey("NextToken"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 51})
    void outOfRangePageSizesAreRejected(int size) {
        invalid("{\"MaxResults\":" + size + "}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "2.5", "\"2\"", "{}", "[]", "true"})
    void malformedPageSizesAreRejectedWithoutCoercion(String size) {
        invalid("{\"MaxResults\":" + size + "}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"%%%%\"", "\"AA\"", "\"2AA\"", "1", "true", "{}", "[]"})
    void malformedTokensAreRejected(String token) {
        invalid("{\"NextToken\":" + token + "}");
    }

    @Test
    void tokensLongerThanTheApiLimitAreRejected() {
        invalid(tokenRequest("A".repeat(1025)));
    }

    @Test
    void tokensCannotCrossAccountOrRegion() {
        create("catalog-a");
        create("catalog-b");
        String token = (String) list(REGION, ACCOUNT, "{\"MaxResults\":2}", 200).get("NextToken");
        assertNotNull(token);
        assertEquals(List.of(AthenaService.DEFAULT_CATALOG), names(list(REGION, "741666100002", "{}", 200)));
        for (Map<String, Object> error : List.of(
                list(REGION, "741666100002", tokenRequest(token), 400),
                list("ap-south-2", ACCOUNT, tokenRequest(token), 400))) {
            assertEquals("InvalidRequestException", error.get("__type"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"界", "😀", "A界😀"})
    void maximumLengthUnicodeCursorIsBoundedAndResumesAfterDeletion(String unit) {
        String prefix = unit.repeat(252 / unit.length());
        String firstName = prefix + "aaaa";
        String secondName = prefix + "aaab";
        assertEquals(256, firstName.length());
        create(firstName);
        create(secondName);
        Map<String, Object> first = list(REGION, ACCOUNT, "{\"MaxResults\":2}", 200);
        assertEquals(List.of(AthenaService.DEFAULT_CATALOG, firstName), names(first));
        String token = (String) first.get("NextToken");
        assertNotNull(token);
        assertTrue(token.length() <= 1024);
        Map<String, Object> last = list(REGION, ACCOUNT, tokenRequest(token), 200);
        assertEquals(List.of(secondName), names(last));
        assertFalse(last.containsKey("NextToken"));
        delete(firstName);
        created.remove(firstName);
        assertEquals(List.of(secondName), names(list(REGION, ACCOUNT, tokenRequest(token), 200)));
    }

    @Test
    void malformedUtf16CursorIsRejectedEvenWithAnIssuedScope() {
        create("catalog-a");
        create("catalog-b");
        String token = (String) list(REGION, ACCOUNT, "{\"MaxResults\":2}", 200).get("NextToken");
        assertNotNull(token);
        byte[] payload = Base64.getUrlDecoder().decode(token);
        for (byte[] suffix : List.of(new byte[] {0}, new byte[] {(byte) 0xD8, 0})) {
            byte[] malformed = Arrays.copyOf(payload, payload.length + suffix.length);
            System.arraycopy(suffix, 0, malformed, payload.length, suffix.length);
            invalid(tokenRequest(Base64.getUrlEncoder().withoutPadding().encodeToString(malformed)));
        }
    }

    @Test
    void builtInOnlyFinalPageOmitsTheToken() {
        for (String body : List.of("{}", "{\"NextToken\":null}")) {
            Map<String, Object> response = list(REGION, ACCOUNT, body, 200);
            assertEquals(List.of(AthenaService.DEFAULT_CATALOG), names(response));
            assertFalse(response.containsKey("NextToken"));
        }
    }

    private void create(String name) {
        call("CreateDataCatalog", REGION, ACCOUNT, "{\"Name\":\"" + name + "\",\"Type\":\"LAMBDA\"}", 200);
        created.add(name);
    }

    private void delete(String name) {
        call("DeleteDataCatalog", REGION, ACCOUNT, "{\"Name\":\"" + name + "\"}", 200);
    }

    private void invalid(String body) {
        assertEquals("InvalidRequestException", list(REGION, ACCOUNT, body, 400).get("__type"));
    }

    private List<String> names(Map<String, Object> response) {
        return ((List<?>) response.get("DataCatalogsSummary")).stream()
                .map(item -> (String) ((Map<?, ?>) item).get("CatalogName")).toList();
    }

    private Map<String, Object> list(String region, String account, String body, int status) {
        return call("ListDataCatalogs", region, account, body, status);
    }

    private Map<String, Object> call(String action, String region, String account, String body, int status) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonAthena." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/" + region + "/athena/aws4_request")
                .body(body.getBytes(StandardCharsets.UTF_8)).when().post("/").then().statusCode(status)
                .extract().jsonPath().getMap("");
    }

    private String tokenRequest(String token) {
        return "{\"NextToken\":\"" + token + "\"}";
    }
}
