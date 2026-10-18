package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A load balancer whose {@code SubnetMappings} name no subnet fails its stack, as on AWS, rather than
 * being created with no subnets.
 */
@QuarkusTest
class CloudFormationElbV2SubnetMappingsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261018/us-east-1/cloudformation/aws4_request";
    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261018/us-east-1/elasticloadbalancing/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** A mapping with no {@code SubnetId}, and one whose {@code SubnetId} is blank. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"AllocationId\": \"eipalloc-12345678\"}", "{\"SubnetId\": \"\"}"})
    void aSubnetMappingThatNamesNoSubnetFailsTheStackWithoutALoadBalancer(String mapping) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "nlb-mappings-" + suffix;
        String name = "nlb-" + suffix;
        String template = """
            {
              "Resources": {
                "Nlb": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Name": "%s", "Type": "network", "SubnetMappings": [%s]}
                }
              }
            }
            """.formatted(name, mapping);
        try {
            request(CFN_AUTH, "CreateStack", "StackName", stack, "TemplateBody", template).statusCode(200);
            CfnStackWaits.StackState state = CfnStackWaits.awaitStatus(stack, "ROLLBACK_COMPLETE");
            assertTrue(state.reason().contains("Nlb"), state.reason());

            request(ELB_AUTH, "DescribeLoadBalancers", "Names.member.1", name).statusCode(400)
                    .body(containsString("LoadBalancerNotFound"));
        } finally {
            request(CFN_AUTH, "DeleteStack", "StackName", stack);
        }
    }

    private static ValidatableResponse request(String auth, String action, String... values) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        for (int i = 0; i < values.length; i += 2) {
            parameters.put(values[i], values[i + 1]);
        }
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", auth)
                .formParams(parameters).when().post("/").then().log().ifValidationFails();
    }
}
