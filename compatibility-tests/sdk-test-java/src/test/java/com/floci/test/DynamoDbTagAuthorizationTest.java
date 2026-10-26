package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.Tag;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two ABAC keys a shared-role policy conditions on: {@code aws:ResourceTag} for a table a
 * data call names, and {@code aws:PrincipalTag} for a tag set when the role was assumed.
 */
@DisplayName("DynamoDB resource and session tag conditions with IAM enforcement")
class DynamoDbTagAuthorizationTest {

    private static final String OPEN_TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sts:AssumeRole\"}]}";

    private IamClient iam;
    private DynamoDbClient admin;
    private DynamoDbClient userClient;
    private StsClient userSts;
    private String userName;
    private String taggedTable;
    private String untaggedTable;
    private String taggedTableArn;
    private String roleName;
    private String roleArn;
    private AccessKey accessKey;
    private boolean userPolicyCreated;
    private boolean rolePolicyCreated;

    @BeforeEach
    void setUp() {
        iam = TestFixtures.iamClient();
        admin = TestFixtures.dynamoDbClient();
        String name = TestFixtures.uniqueName("tag-auth");
        userName = name;
        iam.createUser(r -> r.userName(userName));
        accessKey = iam.createAccessKey(r -> r.userName(userName)).accessKey();
        userClient = DynamoDbClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        accessKey.accessKeyId(), accessKey.secretAccessKey())))
                .build();
        userSts = StsClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        accessKey.accessKeyId(), accessKey.secretAccessKey())))
                .build();

        boolean enforced = false;
        try {
            userClient.listTables();
        } catch (DynamoDbException error) {
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDeniedException");
            enforced = true;
        }
        Assumptions.assumeTrue(enforced, "Requires floci.services.iam.enforcement-enabled=true");

        taggedTable = name + "-tagged";
        untaggedTable = name + "-untagged";
        taggedTableArn = createTable(taggedTable);
        createTable(untaggedTable);
        admin.putItem(r -> r.tableName(taggedTable).item(item()));
        admin.putItem(r -> r.tableName(untaggedTable).item(item()));
        admin.tagResource(r -> r.resourceArn(taggedTableArn)
                // The DynamoDB Tag: this file also imports the STS Tag.
                .tags(software.amazon.awssdk.services.dynamodb.model.Tag.builder()
                        .key("team").value("blue").build()));

        roleName = name + "-role";
        roleArn = iam.createRole(r -> r.roleName(roleName)
                .assumeRolePolicyDocument(OPEN_TRUST_POLICY)).role().arn();
    }

    @AfterEach
    void cleanUp() {
        try {
            if (roleName != null) {
                if (rolePolicyCreated) {
                    iam.deleteRolePolicy(r -> r.roleName(roleName).policyName("tags"));
                }
                iam.deleteRole(r -> r.roleName(roleName));
            }
        } finally {
            try {
                if (taggedTable != null) {
                    admin.deleteTable(r -> r.tableName(taggedTable));
                }
                if (untaggedTable != null) {
                    admin.deleteTable(r -> r.tableName(untaggedTable));
                }
            } finally {
                try {
                    if (userPolicyCreated) {
                        iam.deleteUserPolicy(r -> r.userName(userName).policyName("tags"));
                    }
                } finally {
                    try {
                        if (accessKey != null) {
                            iam.deleteAccessKey(r -> r.userName(userName).accessKeyId(accessKey.accessKeyId()));
                        }
                    } finally {
                        try {
                            if (userName != null) {
                                iam.deleteUser(r -> r.userName(userName));
                            }
                        } finally {
                            closeQuietly(userClient, userSts, admin, iam);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aResourceTagAllowsTheDataActionAndAnUntaggedTableDeniesIt() {
        putUserPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"dynamodb:GetItem\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}}]}");

        assertThat(userClient.getItem(r -> r.tableName(taggedTable).key(key())).item()).isNotEmpty();
        assertThatThrownBy(() -> userClient.getItem(r -> r.tableName(untaggedTable).key(key())))
                .isInstanceOfSatisfying(DynamoDbException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode())
                                .isEqualTo("AccessDeniedException"));
    }

    @Test
    void aSessionTagReachesTheAssumedRoleAndAnAbsentTagDeniesIt() {
        putUserPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"sts:AssumeRole\",\"Resource\":\"*\"}]}");
        iam.putRolePolicy(r -> r.roleName(roleName).policyName("tags").policyDocument(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"dynamodb:GetItem\",\"Resource\":\"*\","
                        + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/team\":\"blue\"}}}]}"));
        rolePolicyCreated = true;

        DynamoDbClient taggedSession = sessionClient(assumeRole(roleArn, true));
        assertThat(taggedSession.getItem(r -> r.tableName(taggedTable).key(key())).item()).isNotEmpty();

        DynamoDbClient untaggedSession = sessionClient(assumeRole(roleArn, false));
        assertThatThrownBy(() -> untaggedSession.getItem(r -> r.tableName(taggedTable).key(key())))
                .isInstanceOfSatisfying(DynamoDbException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode())
                                .isEqualTo("AccessDeniedException"));

        taggedSession.close();
        untaggedSession.close();
    }

    private String createTable(String tableName) {
        return admin.createTable(r -> r.tableName(tableName).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(k -> k.attributeName("PK").keyType(KeyType.HASH))
                .attributeDefinitions(a -> a.attributeName("PK").attributeType(ScalarAttributeType.S)))
                .tableDescription().tableArn();
    }

    private Credentials assumeRole(String targetRoleArn, boolean withTags) {
        return userSts.assumeRole(r -> {
                    r.roleArn(targetRoleArn).roleSessionName(TestFixtures.uniqueName("tag-session"));
                    if (withTags) {
                        r.tags(Tag.builder().key("team").value("blue").build());
                    }
                }).credentials();
    }

    private static DynamoDbClient sessionClient(Credentials credentials) {
        return DynamoDbClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken())))
                .build();
    }

    private void putUserPolicy(String document) {
        iam.putUserPolicy(r -> r.userName(userName).policyName("tags").policyDocument(document));
        userPolicyCreated = true;
    }

    private static Map<String, AttributeValue> key() {
        return Map.of("PK", AttributeValue.fromS("item"));
    }

    private static Map<String, AttributeValue> item() {
        return Map.of("PK", AttributeValue.fromS("item"), "value", AttributeValue.fromS("original"));
    }

    private static void closeQuietly(AutoCloseable... clients) {
        for (AutoCloseable client : clients) {
            if (client == null) {
                continue;
            }
            try {
                client.close();
            } catch (Exception ignored) {
                // A client that is already closed must not hide the test's own failure.
            }
        }
    }
}
