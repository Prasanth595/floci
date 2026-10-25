package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RedshiftAutomatedSnapshotDockerIntegrationTest {
    @Inject
    RedshiftService service;
    @Inject
    RedshiftSnapshotScheduler scheduler;

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            Process probe = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                available = probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0;
            } finally {
                if (probe.isAlive()) {
                    probe.destroyForcibly();
                }
            }
        } catch (IOException ignored) {
            // The test needs the actual PostgreSQL backend; an absent daemon skips it.
            available = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker is required for snapshot data restoration");
    }

    @Test
    void automatedDumpRestoresTheRowsPresentAtSnapshotTime() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceId = "auto-source-" + suffix;
        String restoredId = "auto-restored-" + suffix;
        String scheduleId = "auto-hourly-" + suffix;
        service.createSnapshotSchedule(scheduleId, null, List.of("rate(1 hour)"), Map.of());
        Cluster source = service.createCluster(sourceId, "dc2.large", "admin", "Secret123");
        boolean restored = false;
        Instant due = null;
        try {
            try (Connection connection = connect(source); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE snapshot_rows (id integer)");
                statement.execute("INSERT INTO snapshot_rows VALUES (42)");
                Cluster associated = service.modifyClusterSnapshotSchedule(sourceId, scheduleId, false);
                due = associated.getAutomatedSnapshotAnchor().plusSeconds(3600);
                scheduler.tick(due);
                statement.execute("INSERT INTO snapshot_rows VALUES (99)");
            }
            Snapshot snapshot = service.describeSnapshots(null, sourceId).stream()
                    .filter(value -> "automated".equals(value.getSnapshotType())).findFirst().orElseThrow();
            Cluster target = service.restoreFromClusterSnapshot(restoredId, snapshot.getSnapshotIdentifier());
            restored = true;
            try (Connection connection = connect(target); Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT count(*), min(id), max(id) FROM snapshot_rows")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
                assertEquals(42, rows.getInt(2));
                assertEquals(42, rows.getInt(3));
            }
        } finally {
            if (restored) {
                service.deleteCluster(restoredId);
            }
            service.deleteCluster(sourceId);
            service.deleteSnapshotSchedule(scheduleId);
            scheduler.tick(due == null ? Instant.now() : due.plusSeconds(1));
        }
    }

    private static Connection connect(Cluster cluster) {
        return Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .ignoreExceptions().until(() -> DriverManager.getConnection(
                        "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev", "admin", "Secret123"),
                        Objects::nonNull);
    }
}
