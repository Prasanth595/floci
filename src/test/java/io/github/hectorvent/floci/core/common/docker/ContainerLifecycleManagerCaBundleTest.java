package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every emulator-created container passes through {@link ContainerLifecycleManager#create}, so
 * asserting the CA trust here covers Lambda, ECS, MWAA, Flink, RDS and the rest by construction.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerCaBundleTest {

    private static final String BUNDLE_PEM = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n";

    @Mock DockerClient dockerClient;
    @Mock ImageCacheService imageCacheService;
    @Mock ContainerDetector containerDetector;
    @Mock EmulatorConfig config;
    @Mock EmulatorConfig.DockerConfig dockerConfig;
    @Mock EmulatorConfig.TlsConfig tlsConfig;
    @Mock EmulatorConfig.StorageConfig storageConfig;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        forgetBootstrapTlsDir();
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.tls()).thenReturn(tlsConfig);
        lenient().when(config.storage()).thenReturn(storageConfig);
        lenient().when(storageConfig.persistentPath()).thenReturn(tempDir.toString());
        lenient().when(imageCacheService.ensureImageExists(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void forgetBootstrapTlsDir() {
        // TlsConfigSource.resolvedTlsDir is static: a TLS-on bootstrap run by another test class
        // in this JVM would otherwise steer the bundle lookup at that test's directory.
        System.setProperty("floci.tls.enabled", "false");
        new TlsConfigSource();
        System.clearProperty("floci.tls.enabled");
    }

    @Test
    void tlsOnCopiesTheBundleAndAppendsTheTrustEnvAfterTheSpecEnv() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateContainerCmd createCmd = stubCreateContainer();
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        List<InputStream> streams = new ArrayList<>();
        when(copyCmd.withTarInputStream(any(InputStream.class))).thenAnswer(invocation -> {
            streams.add(invocation.getArgument(0));
            return copyCmd;
        });
        when(copyCmd.exec()).thenAnswer(invocation -> {
            assertBundleArchive(streams.getLast());
            return null;
        });

        assertEquals("container-id", manager().create(specWithEnv(List.of("FOO=bar"))));

        List<String> env = capturedEnv(createCmd);
        assertEquals(List.of("FOO=bar",
                "SSL_CERT_FILE=/etc/floci-ca-bundle.pem",
                "CURL_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "REQUESTS_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca-bundle.pem",
                "AWS_CA_BUNDLE=/etc/floci-ca-bundle.pem"), env);

        verify(copyCmd).withRemotePath("/etc");
        assertEquals(1, streams.size());
        verify(copyCmd).exec();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transientCopyFailurePreservesTheContainerAndTrustWithAFreshTar(boolean consumeCompletely) throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateContainerCmd createCmd = stubCreateContainer();
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        List<InputStream> streams = new ArrayList<>();
        when(copyCmd.withTarInputStream(any(InputStream.class))).thenAnswer(invocation -> {
            streams.add(invocation.getArgument(0));
            return copyCmd;
        });
        AtomicInteger attempts = new AtomicInteger();
        when(copyCmd.exec()).thenAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            InputStream stream = streams.getLast();
            if (attempt == 1 && !consumeCompletely) {
                assertEquals(513, stream.readNBytes(513).length, "consume the tar header and part of the CA bundle");
            } else {
                assertBundleArchive(stream);
            }
            if (attempt == 1) {
                throw new RuntimeException(new IOException("Connection reset"));
            }
            return null;
        });
        lenient().when(dockerClient.removeContainerCmd("container-id"))
                .thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));

        assertEquals("container-id", manager().create(specWithEnv(List.of("FOO=bar"))));

        assertEquals(2, attempts.get());
        assertEquals(2, streams.size());
        assertNotSame(streams.getFirst(), streams.get(1), "a consumed tar stream cannot be replayed");
        assertTrue(capturedEnv(createCmd).contains("AWS_CA_BUNDLE=/etc/floci-ca-bundle.pem"));
        verify(copyCmd, times(2)).withRemotePath("/etc");
        verify(createCmd).exec();
        verify(dockerClient, never()).removeContainerCmd(anyString());
    }

    @Test
    void specValuesWinOverTheInjectedEnv() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateContainerCmd createCmd = stubCreateContainer();
        when(dockerClient.copyArchiveToContainerCmd("container-id"))
                .thenReturn(mock(CopyArchiveToContainerCmd.class, RETURNS_SELF));

        manager().create(specWithEnv(List.of("SSL_CERT_FILE=/my/own.pem", "NODE_EXTRA_CA_CERTS=")));

        List<String> env = capturedEnv(createCmd);
        assertEquals(1, env.stream().filter(e -> e.startsWith("SSL_CERT_FILE=")).count());
        assertEquals(1, env.stream().filter(e -> e.startsWith("NODE_EXTRA_CA_CERTS=")).count());
        assertTrue(env.contains("SSL_CERT_FILE=/my/own.pem"));
        assertTrue(env.contains("NODE_EXTRA_CA_CERTS="));
        assertTrue(env.contains("AWS_CA_BUNDLE=/etc/floci-ca-bundle.pem"));
    }

    @Test
    void anEmptySpecEnvStillGetsTheTrustEnv() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateContainerCmd createCmd = stubCreateContainer();
        when(dockerClient.copyArchiveToContainerCmd("container-id"))
                .thenReturn(mock(CopyArchiveToContainerCmd.class, RETURNS_SELF));

        manager().create(new ContainerSpec("busybox:stable"));

        assertEquals(5, capturedEnv(createCmd).size());
    }

    @Test
    void tlsOffLeavesTheContainerAlone() {
        when(tlsConfig.enabled()).thenReturn(false);
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(specWithEnv(List.of("FOO=bar")));

        assertEquals(List.of("FOO=bar"), capturedEnv(createCmd));
        verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
    }

    @Test
    void tlsOffWithNoEnvSetsNoEnvAtAll() {
        when(tlsConfig.enabled()).thenReturn(false);
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(new ContainerSpec("busybox:stable"));

        verify(createCmd, never()).withEnv(any(List.class));
        verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
    }

    @Test
    void aMissingBundleMeansNoEnvAndNoCopy() {
        when(tlsConfig.enabled()).thenReturn(true);
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(specWithEnv(List.of("FOO=bar")));

        assertEquals(List.of("FOO=bar"), capturedEnv(createCmd));
        verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
    }

    @Test
    void aFailedCopyRecreatesTheContainerWithoutTheTrustVariables() throws Exception {
        // SSL_CERT_FILE and friends replace the image's trust store, so they must never name a
        // file that is not there: an image without /etc keeps its own trust instead.
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse first = mock(CreateContainerResponse.class);
        when(first.getId()).thenReturn("container-id");
        CreateContainerResponse second = mock(CreateContainerResponse.class);
        when(second.getId()).thenReturn("container-id-2");
        when(createCmd.exec()).thenReturn(first, second);
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        when(copyCmd.exec()).thenThrow(new NotFoundException("Could not find the file /etc in container"));
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(removeCmd);

        assertEquals("container-id-2", manager().create(specWithEnv(List.of("FOO=bar"))));

        verify(removeCmd).withForce(true);
        verify(removeCmd).exec();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(createCmd, times(2)).withEnv(env.capture());
        assertEquals(6, env.getAllValues().get(0).size(), "the first attempt carried the trust variables");
        assertEquals(List.of("FOO=bar"), env.getAllValues().get(1), "the retry carries only the spec's own");
        verify(dockerClient, times(1)).copyArchiveToContainerCmd(anyString());
    }

    @Test
    void aReadOnlyRootFilesystemGetsTheBundleFromAVolumeMountedReadOnly() throws Exception {
        // Docker refuses a copy into a read-only root filesystem: the bundle reaches the container in a
        // volume instead, filled through a helper container that is never started.
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateVolumeCmd createVolume = stubMissingVolume();
        CreateContainerCmd createCmd = stubCreateContainers("helper-id", "container-id");
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("helper-id")).thenReturn(copyCmd);
        List<InputStream> streams = new ArrayList<>();
        when(copyCmd.withTarInputStream(any(InputStream.class))).thenAnswer(invocation -> {
            streams.add(invocation.getArgument(0));
            return copyCmd;
        });
        when(copyCmd.exec()).thenAnswer(invocation -> {
            assertBundleArchive(streams.getLast());
            return null;
        });
        RemoveContainerCmd removeHelper = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("helper-id")).thenReturn(removeHelper);

        assertEquals("container-id", manager().create(readOnlySpecWithEnv(List.of("FOO=bar"))));

        ArgumentCaptor<String> volumeName = ArgumentCaptor.forClass(String.class);
        verify(createVolume).withName(volumeName.capture());
        assertTrue(volumeName.getValue().matches("floci-ca-bundle-[0-9a-f]{16}"), volumeName.getValue());
        verify(copyCmd).withRemotePath("/floci-ca-bundle");
        verify(removeHelper).exec();
        verify(dockerClient, never()).copyArchiveToContainerCmd("container-id");

        ArgumentCaptor<HostConfig> hostConfigs = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd, times(2)).withHostConfig(hostConfigs.capture());
        HostConfig container = hostConfigs.getAllValues().get(1);
        assertTrue(container.getReadonlyRootfs());
        Mount bundle = container.getMounts().stream()
                .filter(m -> "/etc/floci-ca".equals(m.getTarget())).findFirst().orElseThrow();
        assertEquals(MountType.VOLUME, bundle.getType());
        assertEquals(volumeName.getValue(), bundle.getSource());
        assertTrue(bundle.getReadOnly());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(createCmd).withEnv(env.capture());
        assertEquals(List.of("FOO=bar",
                "SSL_CERT_FILE=/etc/floci-ca/floci-ca-bundle.pem",
                "CURL_CA_BUNDLE=/etc/floci-ca/floci-ca-bundle.pem",
                "REQUESTS_CA_BUNDLE=/etc/floci-ca/floci-ca-bundle.pem",
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca/floci-ca-bundle.pem",
                "AWS_CA_BUNDLE=/etc/floci-ca/floci-ca-bundle.pem"), env.getValue());
    }

    @Test
    void theBundleVolumeIsFilledOnceForEveryContainerThatNeedsIt() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateVolumeCmd createVolume = stubMissingVolume();
        stubCreateContainers("helper-id", "container-id", "container-id-2");
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("helper-id")).thenReturn(copyCmd);
        when(dockerClient.removeContainerCmd("helper-id")).thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));
        ContainerLifecycleManager manager = manager();

        assertEquals("container-id", manager.create(readOnlySpecWithEnv(List.of())));
        assertEquals("container-id-2", manager.create(readOnlySpecWithEnv(List.of())));

        verify(createVolume, times(1)).exec();
        verify(copyCmd, times(1)).exec();
    }

    @Test
    void aBundleVolumeThatCannotBeFilledLeavesTheContainerItsOwnTrust() throws Exception {
        // As after a failed copy: SSL_CERT_FILE and friends must never name a file that is not there.
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        stubMissingVolume();
        CreateContainerCmd createCmd = stubCreateContainers("helper-id", "container-id");
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("helper-id")).thenReturn(copyCmd);
        when(copyCmd.exec()).thenThrow(new NotFoundException("Could not find the file /floci-ca-bundle"));
        RemoveContainerCmd removeHelper = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("helper-id")).thenReturn(removeHelper);

        assertEquals("container-id", manager().create(readOnlySpecWithEnv(List.of("FOO=bar"))));

        verify(removeHelper).exec();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(createCmd).withEnv(env.capture());
        assertEquals(List.of("FOO=bar"), env.getValue());
        ArgumentCaptor<HostConfig> hostConfigs = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd, times(2)).withHostConfig(hostConfigs.capture());
        HostConfig container = hostConfigs.getAllValues().get(1);
        assertFalse(container.getMounts() != null
                && container.getMounts().stream().anyMatch(m -> "/etc/floci-ca".equals(m.getTarget())));
    }

    private CreateVolumeCmd stubMissingVolume() {
        InspectVolumeCmd inspect = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd(anyString())).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("missing"));
        CreateVolumeCmd create = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(dockerClient.createVolumeCmd()).thenReturn(create);
        return create;
    }

    /** One create command for busybox:stable whose successive calls answer each id in turn. */
    private CreateContainerCmd stubCreateContainers(String... ids) {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        List<CreateContainerResponse> responses = new ArrayList<>();
        for (String id : ids) {
            CreateContainerResponse response = mock(CreateContainerResponse.class);
            when(response.getId()).thenReturn(id);
            responses.add(response);
        }
        when(createCmd.exec()).thenReturn(responses.getFirst(),
                responses.subList(1, responses.size()).toArray(new CreateContainerResponse[0]));
        return createCmd;
    }

    private static ContainerSpec readOnlySpecWithEnv(List<String> env) {
        return new ContainerSpec("busybox:stable", null, env, null, null, null, Map.of(), List.of(), List.of(),
                null, List.of(), List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(), null,
                null, List.of(), List.of(), null, null, true, List.of(), Map.of(), false);
    }

    private Path writeBundle() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        return Files.writeString(tlsDir.resolve("floci-ca-bundle.pem"), BUNDLE_PEM);
    }

    private static void assertBundleArchive(InputStream stream) throws IOException {
        byte[] archive = stream.readAllBytes();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            TarArchiveEntry entry = in.getNextEntry();
            assertNotNull(entry, "every attempt must begin with a complete tar header");
            assertEquals("floci-ca-bundle.pem", entry.getName());
            assertEquals(0644, entry.getMode() & 0777, "world-readable, so a non-root USER can read it");
            assertArrayEquals(BUNDLE_PEM.getBytes(StandardCharsets.UTF_8), in.readAllBytes());
            assertNull(in.getNextEntry(), "the archive holds only the bundle");
        }
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config, 0L);
    }

    private static ContainerSpec specWithEnv(List<String> env) {
        return new ContainerSpec("busybox:stable", null, env, null, null, null, Map.of(), List.of(), null,
                List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(), null, null, List.of());
    }

    private CreateContainerCmd stubCreateContainer() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    @SuppressWarnings("unchecked")
    private static List<String> capturedEnv(CreateContainerCmd createCmd) {
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(createCmd).withEnv(env.capture());
        return env.getValue();
    }
}
