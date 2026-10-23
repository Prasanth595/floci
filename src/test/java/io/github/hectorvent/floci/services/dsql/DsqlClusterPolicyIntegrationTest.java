package io.github.hectorvent.floci.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.dsql.dataplane.DsqlEndpoint;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cluster's resource-based policy on connect, with IAM enforcement on, as Aurora DSQL evaluates it
 * (measured, eu-west-1, 2026-10-22): an explicit Deny that applies to the caller refuses the
 * connection, with a hint naming the caller, the action and the cluster, under the conditions the
 * statement sets; a statement that does not apply leaves the decision to the caller's own policies.
 */
@QuarkusTest
@TestProfile(DsqlEnforcementProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlClusterPolicyIntegrationTest {

    private static final String ACCOUNT = DsqlClusterFixture.ACCOUNT;

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private DsqlClusterFixture cluster;

    @BeforeAll
    void createCluster() {
        cluster = new DsqlClusterFixture(httpPort, endpoint);
        cluster.create(dockerClient);
    }

    @AfterAll
    void deleteCluster() {
        cluster.delete();
    }

    @AfterEach
    void removePolicy() {
        cluster.removePolicy();
    }

    @Test
    void anExplicitDenyThatAppliesToTheCallerRefusesItsConnection() throws Exception {
        String userName = "dsql-" + Long.toString(System.nanoTime(), 36);
        EnforcementFixtures.UserCredentials user = adminUser(userName);
        String userArn = "arn:aws:iam::" + ACCOUNT + ":user/" + userName;
        signsIn(user);

        String[] denials = {
            statement("Deny", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "dsql:DbConnectAdmin", "*", null),
            statement("Deny", "\"*\"", "dsql:DbConnectAdmin", "*",
                    "{\"ArnEquals\":{\"aws:PrincipalArn\":\"" + userArn + "\"}}"),
            statement("Deny", "\"*\"", "dsql:DbConnectAdmin", "*",
                    "{\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.1/32\"}}"),
        };
        for (String denial : denials) {
            cluster.putPolicy(denial);
            PSQLException refused = assertThrows(PSQLException.class, () -> connect(user).close(), denial);
            assertEquals("08006", refused.getSQLState(), denial);
            assertEquals("unable to accept connection, access denied", refused.getServerErrorMessage().getMessage());
            assertTrue(refused.getServerErrorMessage().getDetail().startsWith("Session Id: "));
            assertEquals("User: " + userArn + " is not authorized to perform: dsql:DbConnectAdmin on resource: "
                    + cluster.arn() + " with an explicit deny in a resource-based policy",
                    refused.getServerErrorMessage().getHint(), denial);
        }

        cluster.removePolicy();
        signsIn(user);
    }

    @Test
    void aStatementThatDoesNotApplyLeavesTheCallersOwnPoliciesToDecide() throws Exception {
        EnforcementFixtures.UserCredentials user = adminUser("dsql-" + Long.toString(System.nanoTime(), 36));
        String[] others = {
            statement("Deny", "\"*\"", "dsql:DbConnectAdmin", "*",
                    "{\"IpAddress\":{\"aws:SourceIp\":\"192.0.2.0/24\"}}"),
            statement("Deny", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "dsql:DbConnect", "*", null),
            statement("Deny", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "dsql:DbConnectAdmin",
                    "arn:aws:dsql:" + DsqlClusterFixture.REGION + ":" + ACCOUNT + ":cluster/abcdefghijklmnopqrstuvwxyz",
                    null),
            statement("Allow", "{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"}", "dsql:DbConnectAdmin", "*",
                    "{\"ArnEquals\":{\"aws:PrincipalArn\":\"arn:aws:iam::" + ACCOUNT + ":role/someone-else\"}}"),
        };
        for (String other : others) {
            cluster.putPolicy(other);
            signsIn(user);
        }
    }

    /** A new IAM user whose own policy lets it connect as {@code admin}. */
    private EnforcementFixtures.UserCredentials adminUser(String userName) {
        String authorization = EnforcementFixtures.auth(ACCOUNT, "iam");
        EnforcementFixtures.UserCredentials user = EnforcementFixtures.createUser(cleanup, httpPort, authorization,
                userName);
        EnforcementFixtures.putUserPolicy(httpPort, authorization, userName, "connect", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"dsql:DbConnectAdmin",
                  "Resource":"*"}]}""");
        return user;
    }

    private Connection connect(EnforcementFixtures.UserCredentials user) throws Exception {
        return cluster.connect("admin", cluster.token("DbConnectAdmin", user.accessKeyId(), user.secretAccessKey(),
                Instant.now(), 900, null));
    }

    private void signsIn(EnforcementFixtures.UserCredentials user) throws Exception {
        try (Connection connection = connect(user);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_user")) {
            assertTrue(row.next());
            assertEquals("admin", row.getString(1));
        }
    }

    private static String statement(String effect, String principal, String action, String resource,
                                    String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect + "\",\"Principal\":" + principal
                + ",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\""
                + (condition == null ? "" : ",\"Condition\":" + condition) + "}]}";
    }
}
