package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheParameterGroup;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.ElastiCacheException;
import software.amazon.awssdk.services.elasticache.model.Tag;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ElastiCacheTaggingIntegrationTest {
    @Inject
    StorageFactory storage;

    @TestHTTPResource
    URI endpoint;

    private ElastiCacheClient client() {
        return ElastiCacheClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }

    private static String arn(String type, String id) {
        return "arn:aws:elasticache:us-east-1:000000000000:" + type + ":" + id;
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static Map<String, String> tags(ElastiCacheClient client, String arn) {
        return client.listTagsForResource(r -> r.resourceName(arn)).tagList().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static void cycle(ElastiCacheClient client, String arn) {
        client.addTagsToResource(r -> r.resourceName(arn).tags(tag("env", "old"), tag("keep", "yes")));
        Map<String, String> added = client.addTagsToResource(r -> r.resourceName(arn)
                        .tags(tag("env", "new"), tag("Env", "case-sensitive")))
                .tagList().stream().collect(Collectors.toMap(Tag::key, Tag::value));
        assertEquals(Map.of("env", "new", "Env", "case-sensitive", "keep", "yes"), added);
        assertEquals(added, tags(client, arn));
        assertEquals(2, client.removeTagsFromResource(r -> r.resourceName(arn).tagKeys("env")).tagList().size());
        assertEquals(Map.of("Env", "case-sensitive", "keep", "yes"), tags(client, arn));
    }

    @Test
    void sdkPreservesUserAndUserGroupCreationTagsAndMutations() {
        try (ElastiCacheClient client = client()) {
            client.createUser(r -> r.userId("tags-user").userName("default").engine("valkey")
                    .accessString("on ~* +@all").passwords("synthetic-tags-password-1234").tags(tag("created", "user")));
            client.createUserGroup(r -> r.userGroupId("tags-group").engine("valkey").userIds("tags-user")
                    .tags(tag("created", "group")));
            try {
                for (String resource : new String[]{arn("user", "tags-user"), arn("usergroup", "tags-group")}) {
                    assertEquals(1, tags(client, resource).size());
                    client.removeTagsFromResource(r -> r.resourceName(resource).tagKeys("created"));
                    cycle(client, resource);
                }
            } finally {
                client.deleteUserGroup(r -> r.userGroupId("tags-group"));
                client.deleteUser(r -> r.userId("tags-user"));
            }
        }
    }

    @Test
    void sdkTagsEveryPersistedResourceKindWithoutStartingContainers() {
        CacheCluster redis = new CacheCluster();
        redis.setCacheClusterId("tags-redis");
        redis.setArn(arn("cluster", "tags-redis"));
        CacheCluster memcached = new CacheCluster();
        memcached.setCacheClusterId("tags-memcached");
        memcached.setArn(arn("cluster", "tags-memcached"));
        ReplicationGroup group = new ReplicationGroup();
        group.setReplicationGroupId("tags-repl");
        group.setArn(arn("replicationgroup", "tags-repl"));
        CacheParameterGroup parameters = new CacheParameterGroup("tags-params", "redis7", "tags");
        CacheSubnetGroup subnets = new CacheSubnetGroup("tags-subnets", "tags", "vpc-test", Map.of());
        ServerlessCache serverless = new ServerlessCache();
        serverless.setServerlessCacheName("tags-serverless");
        serverless.setArn(arn("serverlesscache", "tags-serverless"));
        storage.create("elasticache", "elasticache-redis-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {}).put("tags-redis", redis);
        storage.create("elasticache", "elasticache-cache-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {}).put("tags-memcached", memcached);
        storage.create("elasticache", "elasticache-groups.json",
                new TypeReference<Map<String, ReplicationGroup>>() {}).put("tags-repl", group);
        storage.create("elasticache", "elasticache-parameter-groups.json",
                new TypeReference<Map<String, CacheParameterGroup>>() {}).put("tags-params", parameters);
        storage.create("elasticache", "elasticache-subnet-groups.json",
                new TypeReference<Map<String, CacheSubnetGroup>>() {}).put("tags-subnets", subnets);
        storage.create("elasticache", "elasticache-serverless-caches.json",
                new TypeReference<Map<String, ServerlessCache>>() {}).put("us-east-1/tags-serverless", serverless);
        try (ElastiCacheClient client = client()) {
            for (String resource : new String[]{arn("cluster", "tags-redis"), arn("cluster", "tags-memcached"),
                    group.getArn(), arn("parametergroup", "tags-params"), arn("subnetgroup", "tags-subnets"),
                    serverless.getArn()}) {
                cycle(client, resource);
            }
            assertEquals(tags(client, group.getArn()), tags(client, arn("cluster", "tags-repl-001")));
            client.addTagsToResource(r -> r.resourceName(arn("cluster", "tags-repl-001"))
                    .tags(tag("member", "only")));
            client.addTagsToResource(r -> r.resourceName(group.getArn()).tags(tag("propagated", "yes")));
            assertEquals("only", tags(client, arn("cluster", "tags-repl-001")).get("member"));
            assertEquals("yes", tags(client, arn("cluster", "tags-repl-001")).get("propagated"));
        }
    }

    @Test
    void rejectsInvalidArnsMissingResourcesAndMissingTagsWithoutMutation() {
        try (ElastiCacheClient client = client()) {
            client.createCacheParameterGroup(r -> r.cacheParameterGroupName("tags-errors")
                    .cacheParameterGroupFamily("redis7").description("tags").tags(tag("keep", "yes")));
            String resource = arn("parametergroup", "tags-errors");
            try {
                for (String invalid : new String[]{"invalid", resource.replace(":elasticache:", ":rds:"),
                        resource.replace(":000000000000:", ":111111111111:"),
                        resource.replace(":us-east-1:", ":eu-west-2:")}) {
                    assertThrows(ElastiCacheException.class, () -> client.addTagsToResource(r ->
                            r.resourceName(invalid).tags(tag("wrong", "no"))));
                }
                assertThrows(ElastiCacheException.class, () -> client.addTagsToResource(r ->
                        r.resourceName(arn("user", "missing-user")).tags(tag("wrong", "no"))));
                ElastiCacheException missing = assertThrows(ElastiCacheException.class, () ->
                        client.removeTagsFromResource(r -> r.resourceName(resource).tagKeys("keep", "missing")));
                assertEquals("TagNotFound", missing.awsErrorDetails().errorCode());
                assertEquals(Map.of("keep", "yes"), tags(client, resource));
            } finally {
                client.deleteCacheParameterGroup(r -> r.cacheParameterGroupName("tags-errors"));
            }
        }
    }
}
