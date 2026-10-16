package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("CloudWatch Logs ARN tag resource permissions")
class CloudWatchLogsArnTagAuthorizationTest {

    private IamClient iam;
    private CloudWatchLogsClient root;
    private CloudWatchLogsClient caller;
    private String user;
    private AccessKey key;
    private String protectedGroup;
    private String otherGroup;
    private boolean policyCreated;

    @BeforeEach
    void setUp() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses emulator account-root credentials");
        iam = TestFixtures.iamClient("000000000000");
        root = client("000000000000", "test");
        String name = TestFixtures.uniqueName("logs-arn-tag-permissions");
        iam.createUser(r -> r.userName(name));
        user = name;
        key = iam.createAccessKey(r -> r.userName(user)).accessKey();
        caller = client(key.accessKeyId(), key.secretAccessKey());
        boolean enforced = false;
        try {
            caller.describeLogGroups();
        } catch (CloudWatchLogsException error) {
            assertDenied(error);
            enforced = true;
        }
        Assumptions.assumeTrue(enforced, "Requires floci.services.iam.enforcement-enabled=true");
        String group = name + "-protected";
        root.createLogGroup(r -> r.logGroupName(group).tags(Map.of("protected", "true")));
        protectedGroup = group;
        String other = name + "-other";
        root.createLogGroup(r -> r.logGroupName(other).tags(Map.of("protected", "false")));
        otherGroup = other;
    }

    @AfterEach
    void cleanUp() {
        assertAll("remove Logs and IAM fixtures and close every client",
                () -> {
                    if (protectedGroup != null) {
                        root.deleteLogGroup(r -> r.logGroupName(protectedGroup));
                    }
                },
                () -> {
                    if (otherGroup != null) {
                        root.deleteLogGroup(r -> r.logGroupName(otherGroup));
                    }
                },
                () -> {
                    if (policyCreated) {
                        iam.deleteUserPolicy(r -> r.userName(user).policyName("arn-tags"));
                    }
                },
                () -> {
                    if (key != null) {
                        iam.deleteAccessKey(r -> r.userName(user).accessKeyId(key.accessKeyId()));
                    }
                },
                () -> {
                    if (user != null) {
                        iam.deleteUser(r -> r.userName(user));
                    }
                },
                () -> {
                    if (caller != null) {
                        caller.close();
                    }
                },
                () -> {
                    if (root != null) {
                        root.close();
                    }
                },
                () -> {
                    if (iam != null) {
                        iam.close();
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void exactBareArnGrant(String action) {
        policy(statement("Allow", action, arn(protectedGroup)));
        assertThatThrownBy(() -> invoke(action, otherGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertTags(otherGroup, Map.of("protected", "false"));
        Map<String, String> response = invoke(action, protectedGroup);
        assertResult(action, protectedGroup, "true", response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void exactBareArnDenyOverridesWildcard(String action) {
        policy(statement("Allow", action, "*") + "," + statement("Deny", action, arn(protectedGroup)));
        assertThatThrownBy(() -> invoke(action, protectedGroup))
                .isInstanceOfSatisfying(CloudWatchLogsException.class, this::assertDenied);
        assertTags(protectedGroup, Map.of("protected", "true"));
        Map<String, String> response = invoke(action, otherGroup);
        assertResult(action, otherGroup, "false", response);
    }

    private void assertResult(String action, String group, String original, Map<String, String> response) {
        Map<String, String> expected = switch (action) {
            case "TagResource" -> Map.of("protected", original, "team", "red");
            case "UntagResource" -> Map.of();
            case "ListTagsForResource" -> Map.of("protected", original);
            default -> throw new IllegalArgumentException(action);
        };
        if (action.equals("ListTagsForResource")) {
            assertThat(response).isEqualTo(expected);
        }
        assertTags(group, expected);
    }

    private Map<String, String> invoke(String action, String group) {
        switch (action) {
            case "TagResource" -> caller.tagResource(r -> r.resourceArn(arn(group)).tags(Map.of("team", "red")));
            case "UntagResource" -> caller.untagResource(r -> r.resourceArn(arn(group)).tagKeys("protected"));
            case "ListTagsForResource" -> {
                return caller.listTagsForResource(r -> r.resourceArn(arn(group))).tags();
            }
            default -> throw new IllegalArgumentException(action);
        }
        return Map.of();
    }

    private String arn(String group) {
        return TestFixtures.arn("logs", "000000000000", "log-group:" + group);
    }

    private void assertTags(String group, Map<String, String> expected) {
        assertThat(root.listTagsLogGroup(r -> r.logGroupName(group)).tags()).isEqualTo(expected);
    }

    private String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"" + resource + "\"}";
    }

    private void policy(String statements) {
        iam.putUserPolicy(r -> r.userName(user).policyName("arn-tags")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}"));
        policyCreated = true;
    }

    private void assertDenied(CloudWatchLogsException error) {
        assertThat(error.statusCode()).isEqualTo(400);
        assertThat(error.awsErrorDetails().errorCode()).contains("AccessDenied");
    }

    private static CloudWatchLogsClient client(String accessKey, String secret) {
        return CloudWatchLogsClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .build();
    }
}
