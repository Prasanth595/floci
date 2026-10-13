package com.floci.test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.User;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StsPresignedIdentityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkPresignedRequestKeepsUserOrRoleIdentity(boolean role) throws Exception {
        String name = TestFixtures.uniqueName("sts-presigned");
        try (IamClient iam = TestFixtures.iamClient(); StsClient root = TestFixtures.stsClient()) {
            String expectedArn;
            String expectedId;
            AwsCredentials credentials;
            String accessKey = null;
            if (role) {
                String roleArn = iam.createRole(r -> r.roleName(name).assumeRolePolicyDocument(
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                + "\"Principal\":\"*\",\"Action\":\"sts:AssumeRole\"}]}"))
                        .role().arn();
                try {
                    AssumeRoleResponse session = root.assumeRole(r -> r.roleArn(roleArn).roleSessionName("identity"));
                    expectedArn = session.assumedRoleUser().arn();
                    expectedId = session.assumedRoleUser().assumedRoleId();
                    credentials = AwsSessionCredentials.create(session.credentials().accessKeyId(),
                            session.credentials().secretAccessKey(), session.credentials().sessionToken());
                } catch (RuntimeException error) {
                    iam.deleteRole(r -> r.roleName(name));
                    throw error;
                }
            } else {
                User user = iam.createUser(r -> r.userName(name)).user();
                expectedArn = user.arn();
                expectedId = user.userId();
                try {
                    AccessKey key = iam.createAccessKey(r -> r.userName(name)).accessKey();
                    accessKey = key.accessKeyId();
                    credentials = AwsBasicCredentials.create(accessKey, key.secretAccessKey());
                } catch (RuntimeException error) {
                    iam.deleteUser(r -> r.userName(name));
                    throw error;
                }
            }
            try (StsClient caller = StsClient.builder().endpointOverride(TestFixtures.endpoint())
                    .region(TestFixtures.region()).credentialsProvider(StaticCredentialsProvider.create(credentials)).build()) {
                GetCallerIdentityResponse header = caller.getCallerIdentity();
                assertThat(header.arn()).isEqualTo(expectedArn);
                assertThat(header.userId()).isEqualTo(expectedId);
                Document query = presignedIdentity(credentials);
                assertThat(query.getElementsByTagNameNS("*", "Arn").item(0).getTextContent()).isEqualTo(expectedArn);
                assertThat(query.getElementsByTagNameNS("*", "UserId").item(0).getTextContent()).isEqualTo(expectedId);
                assertThat(query.getElementsByTagNameNS("*", "Account").item(0).getTextContent()).isEqualTo(header.account());
            } finally {
                if (role) {
                    iam.deleteRole(r -> r.roleName(name));
                } else {
                    String key = accessKey;
                    try {
                        iam.deleteAccessKey(r -> r.userName(name).accessKeyId(key));
                    } finally {
                        iam.deleteUser(r -> r.userName(name));
                    }
                }
            }
        }
    }

    private static Document presignedIdentity(AwsCredentials credentials) throws Exception {
        byte[] body = "Action=GetCallerIdentity&Version=2011-06-15".getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest signed = Aws4Signer.create().presign(SdkHttpFullRequest.builder()
                        .uri(TestFixtures.endpoint()).method(SdkHttpMethod.POST)
                        .putHeader("Content-Type", "application/x-www-form-urlencoded")
                        .contentStreamProvider(() -> new ByteArrayInputStream(body)).build(),
                Aws4PresignerParams.builder().awsCredentials(credentials).signingRegion(TestFixtures.region())
                        .signingName("sts").expirationTime(Instant.now().plusSeconds(60)).build());
        HttpRequest.Builder request = HttpRequest.newBuilder(signed.getUri()).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length")) {
                values.forEach(value -> request.header(name, value));
            }
        });
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()));
    }
}
