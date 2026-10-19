package io.github.hectorvent.floci.services.codeartifact;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerdaccioConfigRetryTest {

    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final List<InputStream> streams = new ArrayList<>();
    private final List<CopyArchiveToContainerCmd> commands = new ArrayList<>();
    private final List<byte[]> completed = new ArrayList<>();
    private VerdaccioSidecarManager manager;
    private int failures;
    private int consumedBytes;
    private RuntimeException failure;

    @BeforeEach
    void setUp() {
        manager = new VerdaccioSidecarManager(mock(ContainerBuilder.class), lifecycle, mock(EmulatorConfig.class));
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(docker.copyArchiveToContainerCmd("container")).thenAnswer(ignored -> {
            CopyArchiveToContainerCmd command = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
            commands.add(command);
            AtomicReference<InputStream> input = new AtomicReference<>();
            when(command.withTarInputStream(any(InputStream.class))).thenAnswer(invocation -> {
                InputStream stream = invocation.getArgument(0);
                streams.add(stream);
                input.set(stream);
                return command;
            });
            doAnswer(invocation -> {
                if (commands.size() <= failures) {
                    input.get().readNBytes(consumedBytes);
                    throw failure;
                }
                completed.add(input.get().readAllBytes());
                return null;
            }).when(command).exec();
            return command;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void transientFailureRebuildsTheCompleteConfigArchive(int consumed) throws Exception {
        failures = 1;
        consumedBytes = consumed;
        failure = new RuntimeException(new SocketException("Connection reset"));

        manager.copyConfig("container", 0);

        assertEquals(2, commands.size());
        assertNotSame(streams.get(0), streams.get(1));
        for (CopyArchiveToContainerCmd command : commands) {
            verify(command).withRemotePath("/verdaccio/conf");
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(completed.getFirst()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("config.yaml", entry.getName());
            assertEquals(0644, entry.getMode());
            String yaml = new String(tar.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("uplinks: {}"));
            assertTrue(yaml.contains("publish: $all"));
            assertTrue(yaml.contains("listen: 0.0.0.0:4873"));
            assertNull(tar.getNextEntry());
        }
    }

    @Test
    void exhaustedTransportFailurePropagatesAfterTheRetryBudget() {
        failures = 10;
        failure = new RuntimeException(new SocketException("Connection reset"));
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> manager.copyConfig("container", 0));
        assertEquals(6, commands.size());
        assertSame(failure, thrown.getCause());
    }

    @Test
    void daemonRejectionDoesNotRetry() {
        failures = 10;
        failure = new NotFoundException("container missing");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> manager.copyConfig("container", 0));
        assertEquals(1, commands.size());
        assertTrue(thrown.getMessage().contains("container missing"));
    }

    @Test
    void failedConfigurationUploadRemovesTheUnstartedContainer() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codeartifact().npmImage()).thenReturn("verdaccio/verdaccio:test");
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        ContainerBuilder builder = new ContainerBuilder(config,
                mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        manager = new VerdaccioSidecarManager(builder, lifecycle, config);
        when(lifecycle.create(any(ContainerSpec.class))).thenReturn("container");
        failures = 1;
        failure = new NotFoundException("container missing");

        assertThrows(RuntimeException.class, () -> manager.ensureReady("npm-repo", "http://localhost:4566"));

        verify(lifecycle).stopAndRemove("container", null);
        verify(lifecycle, never()).startCreated(any(), any());
        assertEquals(1, commands.size());
    }
}
