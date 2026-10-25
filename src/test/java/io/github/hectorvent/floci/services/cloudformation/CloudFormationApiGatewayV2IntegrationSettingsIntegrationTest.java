package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The settings of an AWS::ApiGatewayV2::Integration through a real stack: a setting the template
 * drops on update is removed from the stored integration and from GetIntegration, and a failed
 * update puts the earlier settings back.
 */
@QuarkusTest
class CloudFormationApiGatewayV2IntegrationSettingsIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String PARAMETERS =
            ",\"RequestParameters\":{\"overwrite:header.x-example\":\"%s\"}";
    private static final String TIMEOUT = ",\"TimeoutInMillis\":15000";
    private static final String FAILING_RESOURCE =
            ",\"Broken\":{\"Type\":\"AWS::ApiGatewayV2::VpcLink\",\"DependsOn\":\"Integration\","
                    + "\"Properties\":{\"Name\":\"broken\"}}";

    @Inject
    ApiGatewayV2Service apiGatewayV2Service;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void removingSettingsFromTheTemplateRemovesThemFromTheIntegration() {
        String stackName = "integration-settings-removal-stack";
        stackAction("CreateStack", stackName, template(PARAMETERS.formatted("value-1") + TIMEOUT, ""));
        waitForStackStatus(stackName, "CREATE_COMPLETE");
        Integration created = integration(stack(stackName));
        assertEquals(Map.of("overwrite:header.x-example", "value-1"), created.getRequestParameters());
        assertEquals(15000, created.getTimeoutInMillis());

        stackAction("UpdateStack", stackName, template(TIMEOUT, ""));
        waitForStackStatus(stackName, "UPDATE_COMPLETE");

        Stack updated = stack(stackName);
        Integration integration = integration(updated);
        assertNull(integration.getRequestParameters());
        assertEquals(15000, integration.getTimeoutInMillis());
        assertEquals("ANY", integration.getIntegrationMethod());
        assertEquals("http://backend.example", integration.getIntegrationUri());
        getIntegrationResponse(updated)
                .body("requestParameters", nullValue())
                .body("timeoutInMillis", equalTo(15000));

        stackAction("UpdateStack", stackName, template("", ""));
        waitForStackStatus(stackName, "UPDATE_COMPLETE");

        Stack withoutTimeout = stack(stackName);
        assertEquals(0, integration(withoutTimeout).getTimeoutInMillis());
        getIntegrationResponse(withoutTimeout).body("timeoutInMillis", nullValue());
    }

    @Test
    void aTimeoutThatBecomesNoValueIsRemoved() {
        String stackName = "integration-settings-novalue-stack";
        stackAction("CreateStack", stackName, template(TIMEOUT, ""));
        waitForStackStatus(stackName, "CREATE_COMPLETE");
        assertEquals(15000, integration(stack(stackName)).getTimeoutInMillis());

        stackAction("UpdateStack", stackName, template(
                ",\"TimeoutInMillis\":{\"Fn::If\":[\"Off\",{\"Ref\":\"AWS::NoValue\"},15000]}", ""));
        waitForStackStatus(stackName, "UPDATE_COMPLETE");

        assertEquals(0, integration(stack(stackName)).getTimeoutInMillis());
    }

    @Test
    void failedUpdateRestoresTheEarlierRequestParameters() {
        String stackName = "integration-settings-rollback-stack";
        stackAction("CreateStack", stackName, template(PARAMETERS.formatted("original") + TIMEOUT, ""));
        waitForStackStatus(stackName, "CREATE_COMPLETE");

        stackAction("UpdateStack", stackName, template(PARAMETERS.formatted("changed"), FAILING_RESOURCE));
        waitForStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        Stack rolledBack = stack(stackName);
        Integration integration = integration(rolledBack);
        assertEquals(Map.of("overwrite:header.x-example", "original"), integration.getRequestParameters());
        assertEquals(15000, integration.getTimeoutInMillis());
        getIntegrationResponse(rolledBack)
                .body("requestParameters.'overwrite:header.x-example'", equalTo("original"));
    }

    private ValidatableResponse getIntegrationResponse(Stack stack) {
        String apiId = stack.getResources().get("HttpApi").getPhysicalId();
        String integrationId = stack.getResources().get("Integration").getPhysicalId();
        return given().when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200);
    }

    private Integration integration(Stack stack) {
        StackResource api = stack.getResources().get("HttpApi");
        StackResource integration = stack.getResources().get("Integration");
        return apiGatewayV2Service.getIntegration(REGION, api.getPhysicalId(), integration.getPhysicalId());
    }

    private Stack stack(String stackName) {
        return cloudFormationService.describeStacks(stackName, REGION).getFirst();
    }

    private static void stackAction(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private void waitForStackStatus(String stackName, String expected) {
        String status = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            try {
                status = cloudFormationService.describeStacks(stackName, REGION).getFirst().getStatus();
            } catch (AwsException e) {
                status = "DELETE_COMPLETE";
            }
            if (expected.equals(status)) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack status", e);
            }
        }
        throw new AssertionError("Stack did not reach " + expected + ", last status " + status);
    }

    /**
     * An HTTP API and an HTTP_PROXY integration to a plain URL, so no container starts.
     * {@code settings} adds integration properties and {@code extraResources} adds resources, both
     * as JSON fragments that start with a comma.
     */
    private static String template(String settings, String extraResources) {
        return """
                {"Conditions":{"Off":{"Fn::Equals":["a","a"]}},
                 "Resources":{
                  "HttpApi":{"Type":"AWS::ApiGatewayV2::Api","Properties":{
                    "Name":"integration-settings-api","ProtocolType":"HTTP"}},
                  "Integration":{"Type":"AWS::ApiGatewayV2::Integration","Properties":{
                    "ApiId":{"Ref":"HttpApi"},"IntegrationType":"HTTP_PROXY","IntegrationMethod":"ANY",
                    "IntegrationUri":"http://backend.example","PayloadFormatVersion":"1.0"%s}}%s}}
                """.formatted(settings, extraResources);
    }
}
