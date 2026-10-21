package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.github.hectorvent.floci.services.rds.container.RdsBackendEndpointResolver;
import io.github.hectorvent.floci.services.rds.container.RdsBackendGate;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RdsDataBackendEndpointRefreshIntegrationTest {

    private static final String IMAGE = "postgres:16.3-alpine";
    private static final String USER = "admin";
    private static final String PASSWORD = "password123";
    private static final String DATABASE = "appdb";
    private static final String REGION = "us-east-1";
    private static final String FIRST_RESOURCE_ARN =
            "arn:aws:rds:us-east-1:000000000000:cluster:data-refresh-first";
    private static final String SECOND_RESOURCE_ARN =
            "arn:aws:rds:us-east-1:000000000000:cluster:data-refresh-second";
    private static final String SECRET_ARN =
            "arn:aws:secretsmanager:us-east-1:000000000000:secret:data-refresh";
    private static final int POSTGRES_PORT = 5432;

    private DockerClient dockerClient;
    private ContainerLifecycleManager lifecycleManager;
    private RdsDataService service;
    private String networkId;
    private String networkName;
    private String firstContainerId;
    private String secondContainerId;
    private String firstAddress;
    private String secondAddress;

    @BeforeAll
    void connectToDocker() {
        try {
            DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig
                    .createDefaultConfigBuilder()
                    .build();
            ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(clientConfig.getDockerHost())
                    .maxConnections(10)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .responseTimeout(Duration.ofMinutes(5))
                    .build();
            dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);
            dockerClient.pingCmd().exec();
            dockerClient.pullImageCmd(IMAGE).start().awaitCompletion(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            assumeTrue(false, "Docker or " + IMAGE + " is not available: " + e.getMessage());
        }

        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);
        lifecycleManager = new ContainerLifecycleManager(
                dockerClient, mock(ImageCacheService.class), detector, mock(EmulatorConfig.class));
    }

    @AfterAll
    void closeDocker() throws IOException {
        if (dockerClient != null) {
            dockerClient.close();
        }
    }

    @BeforeEach
    void startBackends() {
        networkName = "floci-rdsdata-endpoint-refresh-" + UUID.randomUUID();
        CreateNetworkResponse network = dockerClient.createNetworkCmd()
                .withName(networkName)
                .withIpam(new Network.Ipam().withConfig(
                        new Network.Ipam.Config().withSubnet("198.18.201.0/24")))
                .exec();
        networkId = network.getId();
        firstContainerId = startPostgres("first");
        secondContainerId = startPostgres("second");
        waitForPostgres(firstContainerId);
        waitForPostgres(secondContainerId);
        firstAddress = containerAddress(firstContainerId);
        secondAddress = containerAddress(secondContainerId);
        assertNotEquals(firstAddress, secondAddress);
        assumeTrue(canConnectEventually(firstAddress, POSTGRES_PORT)
                        && canConnectEventually(secondAddress, POSTGRES_PORT),
                "Docker bridge addresses are not reachable from this test JVM");
        service = dataService();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        removeContainer(firstContainerId);
        removeContainer(secondContainerId);
        if (networkId != null) {
            try {
                dockerClient.removeNetworkCmd(networkId).exec();
            } catch (DockerException ignored) {
                // best effort
            }
        }
        service = null;
        firstContainerId = null;
        secondContainerId = null;
        networkId = null;
    }

    @Test
    void dataApiKeepsRuntimeIdentityAfterContainerAddressesSwap() {
        String firstSystemIdentifier = systemIdentifier(FIRST_RESOURCE_ARN);
        String secondSystemIdentifier = systemIdentifier(SECOND_RESOURCE_ARN);
        assertNotEquals(firstSystemIdentifier, secondSystemIdentifier);

        stopAndDisconnect(firstContainerId);
        stopAndDisconnect(secondContainerId);
        connect(firstContainerId, secondAddress);
        connect(secondContainerId, firstAddress);
        dockerClient.startContainerCmd(secondContainerId).exec();
        dockerClient.startContainerCmd(firstContainerId).exec();
        waitForPostgres(firstContainerId);
        waitForPostgres(secondContainerId);
        assertEquals(secondAddress, containerAddress(firstContainerId));
        assertEquals(firstAddress, containerAddress(secondContainerId));

        assertEquals(firstSystemIdentifier, systemIdentifier(FIRST_RESOURCE_ARN));
        assertEquals(secondSystemIdentifier, systemIdentifier(SECOND_RESOURCE_ARN));
    }

    @Test
    void dataApiUsesRestartedRuntimeWhenItsPreviousAddressIsUnused() {
        String systemIdentifier = systemIdentifier(FIRST_RESOURCE_ARN);

        stopAndDisconnect(firstContainerId);
        stopAndDisconnect(secondContainerId);
        connect(firstContainerId, secondAddress);
        dockerClient.startContainerCmd(firstContainerId).exec();
        waitForPostgres(firstContainerId);
        assertEquals(secondAddress, containerAddress(firstContainerId));
        assertFalse(canConnect(firstAddress, POSTGRES_PORT),
                "the stale backend address must be unused in this scenario");

        assertEquals(systemIdentifier, systemIdentifier(FIRST_RESOURCE_ARN));
    }

    private RdsDataService dataService() {
        RdsDataResourceResolver resolver = mock(RdsDataResourceResolver.class);
        when(resolver.resolve(FIRST_RESOURCE_ARN, REGION)).thenReturn(target(FIRST_RESOURCE_ARN));
        when(resolver.resolve(SECOND_RESOURCE_ARN, REGION)).thenReturn(target(SECOND_RESOURCE_ARN));
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        SecretVersion version = new SecretVersion();
        version.setSecretString("{\"username\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}");
        when(secrets.getSecretValue(any(), any(), any(), any())).thenReturn(version);
        RdsBackendEndpointResolver endpointResolver = (runtimeId, engine) ->
                lifecycleManager.resolveEndpoint(containerFor(runtimeId), POSTGRES_PORT, networkName);
        return new RdsDataService(resolver, secrets, new ObjectMapper(), new RdsDataConnectionFactory(),
                Duration.ofSeconds(60), RdsBackendGate.OPEN, endpointResolver);
    }

    private static RdsDataResourceResolver.DatabaseTarget target(String resourceArn) {
        return new RdsDataResourceResolver.DatabaseTarget(
                resourceArn, resourceArn, DatabaseEngine.POSTGRES, USER, PASSWORD, DATABASE);
    }

    private String containerFor(String runtimeId) {
        return switch (runtimeId) {
            case FIRST_RESOURCE_ARN -> firstContainerId;
            case SECOND_RESOURCE_ARN -> secondContainerId;
            default -> throw new IllegalArgumentException("Unknown RDS runtime " + runtimeId);
        };
    }

    private String systemIdentifier(String resourceArn) {
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.put("resourceArn", resourceArn);
        request.put("secretArn", SECRET_ARN);
        request.put("database", DATABASE);
        request.put("sql", "SELECT system_identifier::text FROM pg_control_system()");
        ObjectNode response = service.executeStatement(request, REGION);
        String value = response.path("records").path(0).path(0).path("stringValue").asText();
        assertFalse(value.isBlank(), "PostgreSQL must return its system identifier");
        return value;
    }

    private String startPostgres(String suffix) {
        CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE)
                .withName(networkName + "-" + suffix)
                .withExposedPorts(ExposedPort.tcp(POSTGRES_PORT))
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(networkName))
                .withEnv(
                        "POSTGRES_USER=" + USER,
                        "POSTGRES_PASSWORD=" + PASSWORD,
                        "POSTGRES_DB=" + DATABASE)
                .exec();
        dockerClient.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    private void stopAndDisconnect(String containerId) {
        dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
        dockerClient.disconnectFromNetworkCmd()
                .withContainerId(containerId)
                .withNetworkId(networkId)
                .exec();
    }

    private void connect(String containerId, String address) {
        dockerClient.connectToNetworkCmd()
                .withContainerId(containerId)
                .withNetworkId(networkId)
                .withContainerNetwork(new ContainerNetwork().withIpamConfig(
                        new ContainerNetwork.Ipam().withIpv4Address(address)))
                .exec();
    }

    private String containerAddress(String containerId) {
        return dockerClient.inspectContainerCmd(containerId).exec()
                .getNetworkSettings().getNetworks().get(networkName).getIpAddress();
    }

    private void waitForPostgres(String containerId) {
        String[] command = {"pg_isready", "-U", USER, "-d", DATABASE};
        ContainerExec.Result lastResult = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            lastResult = ContainerExec.run(dockerClient, containerId, command, 5);
            if (lastResult.exitCode() == 0) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for PostgreSQL", e);
            }
        }
        throw new IllegalStateException(
                "PostgreSQL did not become ready in container " + containerId
                        + "; last pg_isready exit=" + lastResult.exitCode()
                        + ", stderr=" + lastResult.stderr());
    }

    private static boolean canConnectEventually(String host, int port) {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (canConnect(host, port)) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void removeContainer(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (DockerException ignored) {
            // best effort
        }
    }
}
