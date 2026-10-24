package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DhcpOptions;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2DhcpOptionsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/ec2/aws4_request";

    @Test
    void createDhcpOptionsReturnsAwsQueryResource() {
        String id = given()
            .formParam("Action", "CreateDhcpOptions")
            .formParam("DhcpConfiguration.1.Key", "domain-name")
            .formParam("DhcpConfiguration.1.Value.1", "example.com")
            .formParam("DhcpConfiguration.2.Key", "domain-name-servers")
            .formParam("DhcpConfiguration.2.Value.1", "10.2.5.1")
            .formParam("DhcpConfiguration.2.Value.2", "10.2.5.2")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then()
            .statusCode(200)
            .body("CreateDhcpOptionsResponse.dhcpOptions.dhcpOptionsId", startsWith("dopt-"))
            .body(containsString("<key>domain-name</key>"))
            .body(containsString("<value>example.com</value>"))
            .body(containsString("<key>domain-name-servers</key>"))
            .extract().xmlPath().getString("CreateDhcpOptionsResponse.dhcpOptions.dhcpOptionsId");
        deleteOptions(id);
    }

    @Test
    void describeFiltersCreatedOptionsAndDeleteRemovesThem() {
        String id = createOptions("describe.example");
        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("Filter.1.Name", "value")
            .formParam("Filter.1.Value.1", "describe.example")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then()
            .statusCode(200)
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpOptionsId", equalTo(id));

        deleteOptions(id);
        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("DhcpOptionsId.1", id)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then()
            .statusCode(400)
            .body(containsString("InvalidDhcpOptionsID.NotFound"));
    }

    @Test
    void associatedOptionsCannotBeDeletedUntilVpcUsesDefault() {
        String id = createOptions("association.example");
        String vpcId = given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "is-default")
            .formParam("Filter.1.Value.1", "true")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract()
            .xmlPath().getString("DescribeVpcsResponse.vpcSet.item.vpcId");
        String originalOptionsId = given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract()
            .xmlPath().getString("DescribeVpcsResponse.vpcSet.item.dhcpOptionsId");
        try {
            given()
                .formParam("Action", "AssociateDhcpOptions")
                .formParam("DhcpOptionsId", id)
                .formParam("VpcId", vpcId)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then()
                .statusCode(200)
                .body("AssociateDhcpOptionsResponse.return", equalTo("true"));
            given()
                .formParam("Action", "DescribeVpcs")
                .formParam("VpcId.1", vpcId)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then()
                .statusCode(200)
                .body("DescribeVpcsResponse.vpcSet.item.dhcpOptionsId", equalTo(id));
            given()
                .formParam("Action", "DeleteDhcpOptions")
                .formParam("DhcpOptionsId", id)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then()
                .statusCode(400)
                .body(containsString("DependencyViolation"));
        } finally {
            given()
                .formParam("Action", "AssociateDhcpOptions")
                .formParam("DhcpOptionsId", originalOptionsId)
                .formParam("VpcId", vpcId)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then().statusCode(200);
            deleteOptions(id);
        }
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.dhcpOptionsId", equalTo(originalOptionsId));
    }

    @Test
    void describePagesFiveOptionsUsingNextToken() {
        List<String> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                ids.add(createOptions("pagination.example"));
            }
            Response first = given()
                .formParam("Action", "DescribeDhcpOptions")
                .formParam("Filter.1.Name", "value")
                .formParam("Filter.1.Value.1", "pagination.example")
                .formParam("MaxResults", "5")
                .header("Authorization", AUTH_HEADER)
            .when().post("/");
            first.then().statusCode(200);
            String token = first.xmlPath().getString("DescribeDhcpOptionsResponse.nextToken");
            given()
                .formParam("Action", "DescribeDhcpOptions")
                .formParam("Filter.1.Name", "value")
                .formParam("Filter.1.Value.1", "pagination.example")
                .formParam("MaxResults", "5")
                .formParam("NextToken", token)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then().statusCode(200)
                .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.size()", equalTo(1));
        } finally {
            ids.forEach(this::deleteOptions);
        }
    }

    @Test
    void rejectedTagsDoNotLeaveAnOptionsSet() {
        given()
            .formParam("Action", "CreateDhcpOptions")
            .formParam("DhcpConfiguration.1.Key", "domain-name")
            .formParam("DhcpConfiguration.1.Value.1", "tag-rollback.example")
            .formParam("TagSpecification.1.ResourceType", "dhcp-options")
            .formParam("TagSpecification.1.Tag.1.Key", "oversized")
            .formParam("TagSpecification.1.Tag.1.Value", "x".repeat(257))
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(400);

        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("Filter.1.Name", "value")
            .formParam("Filter.1.Value.1", "tag-rollback.example")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.size()", equalTo(0));
    }

    @Test
    void missingRequiredIdsReturnMissingParameter() {
        for (String action : List.of("AssociateDhcpOptions", "DeleteDhcpOptions")) {
            given().formParam("Action", action).header("Authorization", AUTH_HEADER)
                .when().post("/").then().statusCode(400).body(containsString("MissingParameter"));
        }
    }

    @Test
    void defaultVpcOptionsCanBeDescribed() {
        String defaultId = given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "is-default")
            .formParam("Filter.1.Value.1", "true")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract()
            .xmlPath().getString("DescribeVpcsResponse.vpcSet.item.dhcpOptionsId");

        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("DhcpOptionsId.1", defaultId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpOptionsId", equalTo(defaultId))
            .body(containsString("AmazonProvidedDNS"));
    }

    @Test
    void associatingDefaultMeansNoOptionsInsteadOfTheRegionalDefaultSet() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.253.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract()
            .xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        try {
            given()
                .formParam("Action", "AssociateDhcpOptions")
                .formParam("DhcpOptionsId", "default")
                .formParam("VpcId", vpcId)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then().statusCode(200);
            given()
                .formParam("Action", "DescribeVpcs")
                .formParam("VpcId.1", vpcId)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then().statusCode(200)
                .body("DescribeVpcsResponse.vpcSet.item.dhcpOptionsId", equalTo("default"));
        } finally {
            given()
                .formParam("Action", "DeleteVpc")
                .formParam("VpcId", vpcId)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then().statusCode(200);
        }
    }

    @Test
    void numberedConfigurationsDoNotSilentlyDiscardLaterEntries() {
        String id = given()
            .formParam("Action", "CreateDhcpOptions")
            .formParam("DhcpConfiguration.1.Key", "domain-name")
            .formParam("DhcpConfiguration.1.Value.1", "numbered.example")
            .formParam("DhcpConfiguration.3.Key", "netbios-node-type")
            .formParam("DhcpConfiguration.3.Value.1", "2")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body(containsString("<key>netbios-node-type</key>"))
            .extract().xmlPath().getString("CreateDhcpOptionsResponse.dhcpOptions.dhcpOptionsId");
        deleteOptions(id);
    }

    @Test
    void invalidNetbiosNodeTypeIsRejected() {
        given()
            .formParam("Action", "CreateDhcpOptions")
            .formParam("DhcpConfiguration.1.Key", "netbios-node-type")
            .formParam("DhcpConfiguration.1.Value.1", "3")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(400).body(containsString("InvalidParameterValue"));
    }

    @Test
    void invalidDnsAddressAndLeaseTimeAreRejected() {
        for (String[] configuration : List.of(
                new String[] {"domain-name-servers", "not-an-ip"},
                new String[] {"ipv6-address-preferred-lease-time", "139"})) {
            given()
                .formParam("Action", "CreateDhcpOptions")
                .formParam("DhcpConfiguration.1.Key", configuration[0])
                .formParam("DhcpConfiguration.1.Value.1", configuration[1])
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then().statusCode(400).body(containsString("InvalidParameterValue"));
        }
    }

    @Test
    void commaSeparatedDnsAddressesAreReturnedAsSeparateValues() {
        String id = given()
            .formParam("Action", "CreateDhcpOptions")
            .formParam("DhcpConfiguration.1.Key", "domain-name-servers")
            .formParam("DhcpConfiguration.1.Value.1", "10.2.5.1,10.2.5.2")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body(containsString("<value>10.2.5.1</value>"))
            .body(containsString("<value>10.2.5.2</value>"))
            .extract().xmlPath().getString("CreateDhcpOptionsResponse.dhcpOptions.dhcpOptionsId");
        deleteOptions(id);
    }

    @Test
    void deletingAnEarlierPageItemDoesNotSkipTheNextPage() {
        List<String> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                ids.add(createOptions("deletion-page.example"));
            }
            Response first = given()
                .formParam("Action", "DescribeDhcpOptions")
                .formParam("Filter.1.Name", "value")
                .formParam("Filter.1.Value.1", "deletion-page.example")
                .formParam("MaxResults", "5")
                .header("Authorization", AUTH_HEADER)
            .when().post("/");
            first.then().statusCode(200);
            List<String> firstIds = first.xmlPath().getList(
                    "DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpOptionsId");
            String unseenId = ids.stream().filter(id -> !firstIds.contains(id)).findFirst().orElseThrow();
            String token = first.xmlPath().getString("DescribeDhcpOptionsResponse.nextToken");
            deleteOptions(firstIds.getFirst());
            ids.remove(firstIds.getFirst());

            given()
                .formParam("Action", "DescribeDhcpOptions")
                .formParam("Filter.1.Name", "value")
                .formParam("Filter.1.Value.1", "deletion-page.example")
                .formParam("MaxResults", "5")
                .formParam("NextToken", token)
                .header("Authorization", AUTH_HEADER)
            .when().post("/").then().statusCode(200)
                .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpOptionsId", equalTo(unseenId));
        } finally {
            ids.forEach(this::deleteOptions);
        }
    }

    @Test
    void awsSdkParsesCreatedAndDescribedOptions() {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            DhcpOptions created = client.createDhcpOptions(request -> request
                    .dhcpConfigurations(configuration -> configuration.key("domain-name")
                            .values("sdk.example"))).dhcpOptions();
            String id = created.dhcpOptionsId();
            assertTrue(id.startsWith("dopt-"));
            assertEquals("sdk.example", created.dhcpConfigurations().getFirst().values().getFirst().value());
            try {
                DhcpOptions described = client.describeDhcpOptions(request -> request.dhcpOptionsIds(id))
                        .dhcpOptions().getFirst();
                assertEquals(id, described.dhcpOptionsId());
                assertEquals("sdk.example", described.dhcpConfigurations().getFirst().values().getFirst().value());
            } finally {
                client.deleteDhcpOptions(request -> request.dhcpOptionsId(id));
            }
        }
    }

    private String createOptions(String domain) {
        return given()
            .formParam("Action", "CreateDhcpOptions")
            .formParam("DhcpConfiguration.1.Key", "domain-name")
            .formParam("DhcpConfiguration.1.Value.1", domain)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract()
            .xmlPath().getString("CreateDhcpOptionsResponse.dhcpOptions.dhcpOptionsId");
    }

    private void deleteOptions(String id) {
        given()
            .formParam("Action", "DeleteDhcpOptions")
            .formParam("DhcpOptionsId", id)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200);
    }
}
