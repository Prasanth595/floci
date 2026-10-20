package io.github.hectorvent.floci.services.rds.container;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsArchiveRetryTest {
    private static final String CONTAINER = "rds-retry-id";

    @TempDir
    Path temporary;

    private DockerClient docker;
    private ContainerLifecycleManager lifecycle;
    private RdsContainerManager manager;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any())).thenReturn(CONTAINER);
        when(lifecycle.startCreated(eq(CONTAINER), any())).thenReturn(new ContainerInfo(CONTAINER,
                Map.of(3306, new EndpointInfo("localhost", 3306))));
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(spec.build()).thenReturn(new ContainerSpec("test-image"));
        when(builder.newContainer(anyString())).thenReturn(spec);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().rds().dockerNetwork()).thenReturn(Optional.empty());
        when(config.storage().hostPersistentPath()).thenReturn(temporary.toString());
        when(config.storage().mode()).thenReturn("persistent");
        ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        manager = new RdsContainerManager(builder, lifecycle, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class), 0);
    }

    @AfterEach
    void tearDown() {
        manager.clear();
        manager.shutdownAutoPause();
    }

    @ParameterizedTest
    @CsvSource({"MYSQL,0", "MYSQL,73", "MYSQL,8192", "MARIADB,0", "MARIADB,73", "MARIADB,8192"})
    void masterGrantRetriesConsumedUploadBeforeStarting(DatabaseEngine engine, int consumedBytes) throws Exception {
        DockerArchiveUploadFixture uploads = failingUploads(consumedBytes, 1);

        RdsContainerHandle handle = manager.start("db", "volume", engine, "test-image", "we'ird\\", "password", "db");

        assertEquals(CONTAINER, handle.getContainerId());
        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        verify(uploads.commands.get(1)).withRemotePath("/docker-entrypoint-initdb.d");
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("floci-master-grants.sql", entry.getName());
            assertEquals(0644, entry.getMode());
            assertArrayEquals("GRANT ALL PRIVILEGES ON *.* TO 'we\\'ird\\\\'@'%' WITH GRANT OPTION;"
                    .getBytes(StandardCharsets.UTF_8), tar.readAllBytes());
            assertNull(tar.getNextEntry());
        }
        InOrder order = inOrder(lifecycle, uploads.commands.get(1));
        order.verify(lifecycle).create(any());
        order.verify(uploads.commands.get(1)).exec();
        order.verify(lifecycle).startCreated(eq(CONTAINER), any());
        verify(docker, never()).execCreateCmd(anyString());
    }

    private DockerArchiveUploadFixture failingUploads(int consumedBytes, int failures) {
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, CONTAINER);
        uploads.failures = failures;
        uploads.consumedBytes = consumedBytes;
        uploads.failure = new RuntimeException(new IOException("Connection reset"));
        return uploads;
    }
}
