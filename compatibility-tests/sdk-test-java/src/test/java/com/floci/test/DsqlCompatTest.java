package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dsql.DsqlClient;
import software.amazon.awssdk.services.dsql.model.ClusterStatus;
import software.amazon.awssdk.services.dsql.model.CreateClusterResponse;
import software.amazon.awssdk.services.dsql.model.GetClusterResponse;
import software.amazon.awssdk.services.dsql.model.ValidationException;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aurora DSQL's control plane through the real AWS SDK and its waiters: a cluster created,
 * described, listed, tagged, protected from deletion and deleted.
 */
@DisplayName("Aurora DSQL")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DsqlCompatTest {

    private static final Region REGION = TestFixtures.region();
    private static final Duration WAIT_TIMEOUT =
            TestFixtures.isRealAws() ? Duration.ofMinutes(10) : Duration.ofSeconds(10);

    private static DsqlClient dsql;
    private static String identifier;

    @BeforeAll
    static void createCluster() {
        dsql = TestFixtures.dsqlClient();
        CreateClusterResponse created = dsql.createCluster(b -> b
                .deletionProtectionEnabled(false)
                .tags(Map.of("Name", TestFixtures.uniqueName("dsql-compat"))));
        identifier = created.identifier();
        assertThat(created.status()).isEqualTo(ClusterStatus.CREATING);

        dsql.waiter().waitUntilClusterActive(b -> b.identifier(identifier), w -> w.waitTimeout(WAIT_TIMEOUT));
    }

    @AfterAll
    static void deleteCluster() {
        if (identifier == null) {
            return;
        }
        dsql.deleteCluster(b -> b.identifier(identifier));
        dsql.waiter().waitUntilClusterNotExists(b -> b.identifier(identifier), w -> w.waitTimeout(WAIT_TIMEOUT));
        dsql.close();
    }

    @Test
    @Order(1)
    void aClusterIsActiveWithItsEndpointAndTags() {
        GetClusterResponse cluster = dsql.getCluster(b -> b.identifier(identifier));

        assertThat(identifier).matches("[a-z2-7]{26}");
        assertThat(cluster.status()).isEqualTo(ClusterStatus.ACTIVE);
        assertThat(cluster.deletionProtectionEnabled()).isFalse();
        assertThat(cluster.endpoint()).startsWith(identifier + ".dsql." + REGION.id() + ".");
        assertThat(cluster.arn()).endsWith(":cluster/" + identifier);
        assertThat(dsql.listClusters(b -> { }).clusters())
                .anySatisfy(summary -> assertThat(summary.identifier()).isEqualTo(identifier));
        assertThat(dsql.listTagsForResource(b -> b.resourceArn(cluster.arn())).tags()).containsKey("Name");
    }

    @Test
    @Order(2)
    void aProtectedClusterIsNotDeleted() {
        dsql.updateCluster(b -> b.identifier(identifier).deletionProtectionEnabled(true));

        assertThatThrownBy(() -> dsql.deleteCluster(b -> b.identifier(identifier)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Deletion protection is enabled");

        dsql.updateCluster(b -> b.identifier(identifier).deletionProtectionEnabled(false));
    }
}
