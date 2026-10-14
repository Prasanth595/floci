package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Aurora member master credentials")
class RdsMemberCredentialsTest {
    private static final Logger LOG = Logger.getLogger(RdsMemberCredentialsTest.class.getName());

    @ParameterizedTest
    @CsvSource({"aurora-postgresql, false", "aurora-postgresql, true",
            "aurora-mysql, false", "aurora-mysql, true"})
    @DisplayName("Members expose the cluster master user with omitted or conflicting credentials")
    void memberMasterUserComesFromCluster(String engine, boolean conflicting) {
        String clusterId = TestFixtures.uniqueName("member-credentials");
        String memberId = clusterId + "-member";
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                rds.createDBCluster(b -> b.dbClusterIdentifier(clusterId).engine(engine)
                        .masterUsername("clusteradmin").masterUserPassword("ClusterPassword!"));
                CreateDbInstanceRequest.Builder request = CreateDbInstanceRequest.builder()
                        .dbInstanceIdentifier(memberId).dbClusterIdentifier(clusterId)
                        .engine(engine).dbInstanceClass("db.serverless");
                if (conflicting) {
                    request.masterUsername("differentuser").masterUserPassword("DifferentPassword!");
                }
                DBInstance created = rds.createDBInstance(request.build()).dbInstance();
                assertThat(created.masterUsername()).isEqualTo("clusteradmin");
                assertThat(rds.describeDBInstances(b -> b.dbInstanceIdentifier(memberId))
                        .dbInstances().getFirst().masterUsername()).isEqualTo("clusteradmin");
            } finally {
                try {
                    rds.deleteDBInstance(b -> b.dbInstanceIdentifier(memberId).skipFinalSnapshot(true));
                } catch (RdsException e) {
                    LOG.log(Level.WARNING, "Could not clean up member " + memberId, e);
                }
                try {
                    rds.deleteDBCluster(b -> b.dbClusterIdentifier(clusterId).skipFinalSnapshot(true));
                } catch (RdsException e) {
                    LOG.log(Level.WARNING, "Could not clean up cluster " + clusterId, e);
                }
            }
        }
    }
}
