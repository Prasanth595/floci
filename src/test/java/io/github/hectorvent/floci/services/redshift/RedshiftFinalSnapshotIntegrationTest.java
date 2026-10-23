package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.Snapshot;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class RedshiftFinalSnapshotIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String DUMP = "CREATE TABLE preserved (id integer); INSERT INTO preserved VALUES (42);";

    @InjectMock
    RedshiftContainerManager containerManager;

    @InjectMock
    RedshiftProxyManager proxyManager;

    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void prepareRuntime() {
        when(containerManager.start(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RedshiftContainerHandle("mock-container", "cluster", "localhost", 5432));
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(3, Path.class), DUMP);
            return null;
        }).when(containerManager).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1, 3653})
    void sdkFinalSnapshotSurvivesDeletionAndFeedsRestore(int retention) {
        String source = "sdk-final-source";
        String backup = "sdk-final-backup";
        String restored = "sdk-final-restored";
        AtomicReference<String> restoredDump = new AtomicReference<>();
        doAnswer(invocation -> {
            restoredDump.set(Files.readString(invocation.getArgument(3, Path.class)));
            return null;
        }).when(containerManager).restoreSnapshot(eq(ACCOUNT), eq(restored), eq("admin"), any(Path.class));
        try (RedshiftClient client = client(ACCOUNT); RedshiftClient other = client("222222222222")) {
            create(client, source);
            try {
                assertEquals("deleting", client.deleteCluster(request -> request.clusterIdentifier(source)
                        .finalClusterSnapshotIdentifier(backup).finalClusterSnapshotRetentionPeriod(retention))
                        .cluster().clusterStatus());
                RedshiftException gone = assertThrows(RedshiftException.class,
                        () -> client.describeClusters(request -> request.clusterIdentifier(source)));
                assertEquals("ClusterNotFound", gone.awsErrorDetails().errorCode());
                Snapshot snapshot = client.describeClusterSnapshots(request -> request.snapshotIdentifier(backup))
                        .snapshots().getFirst();
                assertEquals(retention, snapshot.manualSnapshotRetentionPeriod());
                assertEquals(source, snapshot.clusterIdentifier());
                assertEquals("available", snapshot.status());
                assertNotNull(snapshot.snapshotCreateTime());
                RedshiftException hidden = assertThrows(RedshiftException.class,
                        () -> other.describeClusterSnapshots(request -> request.snapshotIdentifier(backup)));
                assertEquals("ClusterSnapshotNotFound", hidden.awsErrorDetails().errorCode());
                client.restoreFromClusterSnapshot(request -> request.clusterIdentifier(restored).snapshotIdentifier(backup));
                assertEquals(DUMP, restoredDump.get());
            } finally {
                deleteIfPresent(client, source);
                deleteIfPresent(client, restored);
                try {
                    client.deleteClusterSnapshot(request -> request.snapshotIdentifier(backup));
                } catch (RedshiftException expected) {
                    if (!"ClusterSnapshotNotFound".equals(expected.awsErrorDetails().errorCode())) {
                        throw expected;
                    }
                }
            }
        }
    }

    @Test
    void sdkDefaultNeedsSnapshotNameAndExplicitSkipLeavesNoSnapshot() {
        String source = "sdk-final-validation";
        try (RedshiftClient client = client(ACCOUNT)) {
            create(client, source);
            try {
                RedshiftException missing = assertThrows(RedshiftException.class,
                        () -> client.deleteCluster(request -> request.clusterIdentifier(source)));
                assertEquals("InvalidParameterCombination", missing.awsErrorDetails().errorCode());
                assertEquals("available", client.describeClusters(request -> request.clusterIdentifier(source))
                        .clusters().getFirst().clusterStatus());
                client.deleteCluster(request -> request.clusterIdentifier(source).skipFinalClusterSnapshot(true));
                assertTrue(client.describeClusterSnapshots(request -> request.clusterIdentifier(source)).snapshots().isEmpty());
                verify(containerManager, never()).takeSnapshot(eq(ACCOUNT), eq(source), anyString(), any(Path.class));
            } finally {
                deleteIfPresent(client, source);
            }
        }
    }

    private RedshiftClient client(String account) {
        return RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }

    private static void create(RedshiftClient client, String name) {
        client.createCluster(request -> request.clusterIdentifier(name).nodeType("dc2.large")
                .masterUsername("admin").masterUserPassword("Secret123"));
    }

    private static void deleteIfPresent(RedshiftClient client, String name) {
        try {
            client.deleteCluster(request -> request.clusterIdentifier(name).skipFinalClusterSnapshot(true));
        } catch (RedshiftException expected) {
            if (!"ClusterNotFound".equals(expected.awsErrorDetails().errorCode())) {
                throw expected;
            }
        }
    }
}
