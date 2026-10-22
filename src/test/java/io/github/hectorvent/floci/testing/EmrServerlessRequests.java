package io.github.hectorvent.floci.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/** One EMR Serverless REST JSON request, signed for a named Region. */
public final class EmrServerlessRequests {
    private EmrServerlessRequests() {}

    public static RequestSpecification emrServerless(String region) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=TEST/20260819/" + region
                        + "/emr-serverless/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
                .contentType("application/json");
    }
}
