package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An {@code AWS::S3::Bucket} created through CloudFormation carries the configuration its template
 * declares, and an {@code AWS::S3::BucketPolicy} puts its document on the bucket.
 *
 * <p>Before this, {@code S3CfnProvisioner} translated {@code CorsConfiguration} and
 * {@code VersioningConfiguration} and nothing else, and the policy resource took a physical id and
 * stopped, so a stack reported {@code CREATE_COMPLETE} for a bucket with no lifecycle, no
 * public-access block, no default encryption, no tags and no policy. The template below is the
 * shape a bootstrap stack deploys: a versioned bucket, locked down, with a transport policy.
 */
@QuarkusTest
class CloudFormationS3BucketPropertiesIntegrationTest {

    private static String template(String bucket, int noncurrentDays) {
        return """
            {
              "Resources": {
                "recordsBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "%s",
                    "VersioningConfiguration": { "Status": "Enabled" },
                    "PublicAccessBlockConfiguration": {
                      "BlockPublicAcls": true, "BlockPublicPolicy": true,
                      "IgnorePublicAcls": true, "RestrictPublicBuckets": true
                    },
                    "LifecycleConfiguration": { "Rules": [ {
                      "Id": "expire-superseded-record-versions",
                      "Status": "Enabled",
                      "NoncurrentVersionExpiration": { "NoncurrentDays": %d },
                      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 }
                    } ] },
                    "BucketEncryption": { "ServerSideEncryptionConfiguration": [ {
                      "ServerSideEncryptionByDefault": { "SSEAlgorithm": "aws:kms", "KMSMasterKeyID": "arn:aws:kms:us-east-1:000000000000:key/abc" },
                      "BucketKeyEnabled": true
                    } ] },
                    "Tags": [ { "Key": "purpose", "Value": "records" },
                              { "Key": "escaped", "Value": { "Fn::Sub": "${!NotAVariable}-${AWS::Region}" } } ]
                  }
                },
                "recordsBucketPolicy": {
                  "Type": "AWS::S3::BucketPolicy",
                  "Properties": {
                    "Bucket": { "Ref": "recordsBucket" },
                    "PolicyDocument": {
                      "Version": "2012-10-17",
                      "Statement": [ {
                        "Sid": "DenyInsecureTransport", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
                        "Resource": [ { "Fn::GetAtt": ["recordsBucket", "Arn"] }, { "Fn::Sub": "${recordsBucket.Arn}/*" } ],
                        "Condition": { "Bool": { "aws:SecureTransport": "false" } }
                      } ]
                    }
                  }
                }
              }
            }
            """.formatted(bucket, noncurrentDays);
    }

    @Test
    void theBucketCarriesWhatItsTemplateDeclares() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-bucket-" + suffix;
        String stackId = stack("CreateStack", "cfn-props-stack-" + suffix, template(bucket, 30));
        await(stackId, "CREATE_COMPLETE");

        bucketGet(bucket, "versioning").body(containsString("<Status>Enabled</Status>"));

        bucketGet(bucket, "publicAccessBlock")
            .body(containsString("<BlockPublicAcls>true</BlockPublicAcls>"))
            .body(containsString("<IgnorePublicAcls>true</IgnorePublicAcls>"))
            .body(containsString("<BlockPublicPolicy>true</BlockPublicPolicy>"))
            .body(containsString("<RestrictPublicBuckets>true</RestrictPublicBuckets>"));

        bucketGet(bucket, "lifecycle")
            .body(containsString("<ID>expire-superseded-record-versions</ID>"))
            .body(containsString("<Status>Enabled</Status>"))
            .body(containsString("<NoncurrentDays>30</NoncurrentDays>"))
            .body(containsString("<DaysAfterInitiation>7</DaysAfterInitiation>"));

        bucketGet(bucket, "encryption")
            .body(containsString("<SSEAlgorithm>aws:kms</SSEAlgorithm>"))
            .body(containsString("<KMSMasterKeyID>arn:aws:kms:us-east-1:000000000000:key/abc</KMSMasterKeyID>"))
            .body(containsString("<BucketKeyEnabled>true</BucketKeyEnabled>"));

        bucketGet(bucket, "tagging").body(containsString("<Key>purpose</Key>")).body(containsString("<Value>records</Value>"));
        // Fn::Sub: ${!Name} is a literal, ${AWS::Region} a pseudo-parameter, and (in the policy
        // below) ${recordsBucket.Arn} the GetAtt shorthand, which used to come back unresolved.
        bucketGet(bucket, "tagging").body(containsString("<Value>${NotAVariable}-us-east-1</Value>"));

        // The policy is on the bucket, with its intrinsics resolved to the bucket's real ARN.
        bucketGet(bucket, "policy")
            .body(containsString("DenyInsecureTransport"))
            .body(containsString("arn:aws:s3:::" + bucket + "/*"))
            .body(not(containsString("Fn::")));
    }

    @Test
    void aStackUpdateChangesTheLifecycleWindow() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-update-" + suffix;
        String stackName = "cfn-props-update-stack-" + suffix;
        String stackId = stack("CreateStack", stackName, template(bucket, 30));
        await(stackId, "CREATE_COMPLETE");

        stack("UpdateStack", stackName, template(bucket, 45));
        await(stackId, "UPDATE_COMPLETE");

        bucketGet(bucket, "lifecycle").body(containsString("<NoncurrentDays>45</NoncurrentDays>"));
        bucketGet(bucket, "versioning").body(containsString("<Status>Enabled</Status>"));
    }

    @Test
    void aBucketDeclaringNoneOfItStaysBare() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-bare-" + suffix;
        String stackId = stack("CreateStack", "cfn-props-bare-stack-" + suffix, """
            { "Resources": { "b": { "Type": "AWS::S3::Bucket", "Properties": { "BucketName": "%s" } } } }
            """.formatted(bucket));
        await(stackId, "CREATE_COMPLETE");

        given().when().get("/" + bucket + "?versioning").then().statusCode(200).body(not(containsString("<Status>")));
        given().when().get("/" + bucket + "?lifecycle").then().statusCode(404);
        // A bucket nobody configured reports the AWS default rather than a 404.
        given().when().get("/" + bucket + "?publicAccessBlock").then().statusCode(200)
                .body(containsString("<BlockPublicAcls>true</BlockPublicAcls>"));
    }

    @Test
    void droppingThePolicyResourceTakesThePolicyOffTheBucket() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-drop-" + suffix;
        String stackName = "cfn-props-drop-stack-" + suffix;
        String stackId = stack("CreateStack", stackName, template(bucket, 30));
        await(stackId, "CREATE_COMPLETE");
        bucketGet(bucket, "policy").body(containsString("DenyInsecureTransport"));

        // The same template with the policy resource removed. CloudFormation deletes the dropped
        // resource, which must clear the policy rather than report DELETE_COMPLETE and leave it.
        stack("UpdateStack", stackName, """
            { "Resources": { "recordsBucket": { "Type": "AWS::S3::Bucket",
              "Properties": { "BucketName": "%s" } } } }
            """.formatted(bucket));
        await(stackId, "UPDATE_COMPLETE");

        given().when().get("/" + bucket + "?policy").then().statusCode(404);
    }

    /** A bucket named {@code bucket} carrying {@code properties}, each entry prefixed with a comma. */
    private static String bareBucketTemplate(String bucket, String properties) {
        return """
            { "Resources": { "b": { "Type": "AWS::S3::Bucket",
              "Properties": { "BucketName": "%s"%s } } } }
            """.formatted(bucket, properties);
    }

    private static String ownershipControls(String objectOwnership) {
        return ", \"OwnershipControls\": { \"Rules\": [ { \"ObjectOwnership\": \"" + objectOwnership + "\" } ] }";
    }

    @Test
    void ownershipControlsFollowTheTemplate() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-own-" + suffix;
        String stackName = "cfn-props-own-stack-" + suffix;
        String stackId = stack("CreateStack", stackName,
                bareBucketTemplate(bucket, ownershipControls("BucketOwnerEnforced")));
        await(stackId, "CREATE_COMPLETE");
        bucketGet(bucket, "ownershipControls")
            .body(containsString("<ObjectOwnership>BucketOwnerEnforced</ObjectOwnership>"));

        stack("UpdateStack", stackName, bareBucketTemplate(bucket, ownershipControls("ObjectWriter")));
        await(stackId, "UPDATE_COMPLETE");
        bucketGet(bucket, "ownershipControls").body(containsString("<ObjectOwnership>ObjectWriter</ObjectOwnership>"));

        // Dropping the property takes the controls off the bucket.
        stack("UpdateStack", stackName, bareBucketTemplate(bucket, ""));
        await(stackId, "UPDATE_COMPLETE");
        given().when().get("/" + bucket + "?ownershipControls").then().statusCode(404)
            .body(containsString("OwnershipControlsNotFoundError"));

        // Controls put outside the stack stay through an update when no template declares them.
        given().body("""
            <OwnershipControls xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <Rule><ObjectOwnership>BucketOwnerPreferred</ObjectOwnership></Rule>
            </OwnershipControls>
            """).when().put("/" + bucket + "?ownershipControls").then().statusCode(200);
        stack("UpdateStack", stackName,
                bareBucketTemplate(bucket, ", \"Tags\": [ { \"Key\": \"purpose\", \"Value\": \"records\" } ]"));
        await(stackId, "UPDATE_COMPLETE");
        bucketGet(bucket, "ownershipControls")
            .body(containsString("<ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>"));
    }

    @Test
    void theOwnershipModeCanComeFromAParameter() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-own-ref-" + suffix;
        String stackId = stack("CreateStack", "cfn-props-own-ref-stack-" + suffix, """
            { "Parameters": { "Mode": { "Type": "String", "Default": "BucketOwnerPreferred" } },
              "Resources": { "b": { "Type": "AWS::S3::Bucket", "Properties": { "BucketName": "%s",
                "OwnershipControls": { "Rules": [ { "ObjectOwnership": { "Ref": "Mode" } } ] } } } } }
            """.formatted(bucket));
        await(stackId, "CREATE_COMPLETE");
        bucketGet(bucket, "ownershipControls")
            .body(containsString("<ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>"));
    }

    /**
     * An {@code Fn::If} that chooses {@code AWS::NoValue} leaves the property undeclared. The update
     * that turns it off removes the controls the stack put; the next one leaves alone the controls
     * put outside the stack, since the template before it did not declare the property either.
     */
    @Test
    void ownershipControlsAConditionTurnsOffAreRemovedOnce() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-own-if-" + suffix;
        String stackName = "cfn-props-own-if-stack-" + suffix;
        String template = """
            { "Parameters": { "Enforce": { "Type": "String", "Default": "true" } },
              "Conditions": { "Enforced": { "Fn::Equals": [ { "Ref": "Enforce" }, "true" ] } },
              "Resources": { "b": { "Type": "AWS::S3::Bucket", "Properties": { "BucketName": "%s",
                "OwnershipControls": { "Fn::If": [ "Enforced",
                  { "Rules": [ { "ObjectOwnership": "BucketOwnerEnforced" } ] }, { "Ref": "AWS::NoValue" } ] }%s } } } }
            """;
        String stackId = stack("CreateStack", stackName, template.formatted(bucket, ""));
        await(stackId, "CREATE_COMPLETE");
        bucketGet(bucket, "ownershipControls")
            .body(containsString("<ObjectOwnership>BucketOwnerEnforced</ObjectOwnership>"));

        stack("UpdateStack", stackName, template.formatted(bucket, ""), "Enforce", "false");
        await(stackId, "UPDATE_COMPLETE");
        given().when().get("/" + bucket + "?ownershipControls").then().statusCode(404)
            .body(containsString("OwnershipControlsNotFoundError"));

        given().body("""
            <OwnershipControls xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <Rule><ObjectOwnership>BucketOwnerPreferred</ObjectOwnership></Rule>
            </OwnershipControls>
            """).when().put("/" + bucket + "?ownershipControls").then().statusCode(200);
        stack("UpdateStack", stackName, template.formatted(bucket,
                ", \"Tags\": [ { \"Key\": \"purpose\", \"Value\": \"records\" } ]"), "Enforce", "false");
        await(stackId, "UPDATE_COMPLETE");
        bucketGet(bucket, "ownershipControls")
            .body(containsString("<ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>"));
    }

    @Test
    void aMalformedOwnershipRuleFailsTheUpdateBeforeTheBucketChanges() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-own-bad-" + suffix;
        String stackName = "cfn-props-own-bad-stack-" + suffix;
        String stackId = stack("CreateStack", stackName, bareBucketTemplate(bucket,
                ownershipControls("ObjectWriter") + ", \"Tags\": [ { \"Key\": \"purpose\", \"Value\": \"before\" } ]"));
        await(stackId, "CREATE_COMPLETE");

        // The tag changes too, as a canary: the rule is checked before anything is applied, so the
        // rolled-back stack still describes the bucket.
        stack("UpdateStack", stackName, bareBucketTemplate(bucket,
                ownershipControls("public") + ", \"Tags\": [ { \"Key\": \"purpose\", \"Value\": \"after\" } ]"));
        await(stackId, "UPDATE_ROLLBACK_COMPLETE");

        String events = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackId)
        .when().post("/").then().statusCode(200).extract().asString();
        assertTrue(events.contains("#/OwnershipControls/Rules/0/ObjectOwnership: failed validation constraint"),
                "the update failed on the rule: " + events);
        bucketGet(bucket, "tagging").body(containsString("<Value>before</Value>")).body(not(containsString("after")));
        bucketGet(bucket, "ownershipControls").body(containsString("<ObjectOwnership>ObjectWriter</ObjectOwnership>"));
    }

    @Test
    void thePolicyResourceIsIdentifiedByItsBucket() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cfn-props-ref-" + suffix;
        String stackId = stack("CreateStack", "cfn-props-ref-stack-" + suffix, template(bucket, 30));
        await(stackId, "CREATE_COMPLETE");

        // primaryIdentifier in the registry schema is /properties/Bucket, so that is the physical
        // id, and it is what makes the policy findable at delete.
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackId)
        .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(bucket, physicalIdOf(xml, "recordsBucketPolicy"),
                "the policy's physical id is its bucket: " + xml);
    }

    /** The PhysicalResourceId of one member of a DescribeStackResources response. */
    private static String physicalIdOf(String xml, String logicalId) {
        String marker = "<LogicalResourceId>" + logicalId + "</LogicalResourceId>";
        int at = xml.indexOf(marker);
        assertTrue(at > 0, logicalId + " is not in the stack: " + xml);
        int memberStart = xml.lastIndexOf("<member>", at);
        int memberEnd = xml.indexOf("</member>", at);
        String member = xml.substring(memberStart, memberEnd);
        int idStart = member.indexOf("<PhysicalResourceId>");
        assertTrue(idStart > 0, logicalId + " has no physical id: " + member);
        idStart += "<PhysicalResourceId>".length();
        return member.substring(idStart, member.indexOf("</PhysicalResourceId>", idStart));
    }

    private static io.restassured.response.ValidatableResponse bucketGet(String bucket, String subresource) {
        return given().when().get("/" + bucket + "?" + subresource).then().statusCode(200);
    }

    private static String stack(String action, String stackName, String template) {
        return stack(action, stackName, template, null, null);
    }

    /** Runs {@code action}, setting the template parameter {@code parameter} when it is not null. */
    private static String stack(String action, String stackName, String template, String parameter, String value) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template);
        if (parameter != null) {
            request.formParam("Parameters.member.1.ParameterKey", parameter)
                .formParam("Parameters.member.1.ParameterValue", value);
        }
        String xml = request
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        int start = xml.indexOf("<StackId>") + "<StackId>".length();
        return xml.substring(start, xml.indexOf("</StackId>", start));
    }

    private static void await(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackId)
            .when().post("/").then().statusCode(200).extract().asString();
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            if (xml.contains("_FAILED</StackStatus>") || xml.contains("ROLLBACK_COMPLETE</StackStatus>")) {
                break;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }
}
