package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.RedshiftException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftDefaultIamRoleIntegrationTest {

    @InjectMock
    RedshiftContainerManager containerManager;

    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkCreatesChangesAndRemovesDefaultRole() {
        String clusterId = "sdk-default-role";
        String first = "arn:aws:iam::000000000000:role/first";
        String second = "arn:aws:iam::000000000000:role/second";
        when(containerManager.start(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RedshiftContainerHandle("mock-container", clusterId, "localhost", 5432));
        try (RedshiftClient client = RedshiftClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            Cluster created = client.createCluster(request -> request.clusterIdentifier(clusterId)
                    .nodeType("dc2.large").masterUsername("admin").masterUserPassword("Secret123")
                    .iamRoles(first).defaultIamRoleArn(first)).cluster();
            try {
                assertEquals(first, created.defaultIamRoleArn());
                assertEquals(first, client.describeClusters(request -> request.clusterIdentifier(clusterId))
                        .clusters().getFirst().defaultIamRoleArn());

                RedshiftException error = assertThrows(RedshiftException.class,
                        () -> client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                                .defaultIamRoleArn(second)));
                assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());

                Cluster updated = client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                        .addIamRoles(second).defaultIamRoleArn(second)).cluster();
                assertEquals(second, updated.defaultIamRoleArn());

                Cluster removed = client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                        .removeIamRoles(second)).cluster();
                assertNull(removed.defaultIamRoleArn());
                assertEquals(first, removed.iamRoles().getFirst().iamRoleArn());
                assertNull(client.describeClusters(request -> request.clusterIdentifier(clusterId))
                        .clusters().getFirst().defaultIamRoleArn());
            } finally {
                client.deleteCluster(request -> request.clusterIdentifier(clusterId).skipFinalClusterSnapshot(true));
            }
        }
    }
}
