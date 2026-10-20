package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RunInstances copies a key pair's stored public key into the guest's authorized_keys, so an ED25519
 * key pair from CreateKeyPair has to be launched with the public half of the ED25519 key it returned,
 * in either format.
 */
@QuarkusTest
@TestProfile(Ec2CreateKeyPairLaunchIntegrationTest.ContainerBackedEc2.class)
class Ec2CreateKeyPairLaunchIntegrationTest {

    /**
     * The shared test configuration runs EC2 in mock mode, which launches no container and so hands
     * no key to one. This turns it back on; the container manager is mocked, so no Docker is involved.
     */
    public static class ContainerBackedEc2 implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false");
        }
    }

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager containerManager;

    @InjectMock
    AmiImageResolver amiImageResolver;

    @BeforeEach
    void resolveEveryAmiToABaseImage() {
        when(amiImageResolver.resolveImage(anyString())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pem", "ppk"})
    void anEd25519KeyPairIsLaunchedWithThePublicHalfOfTheKeyItReturned(String keyFormat) throws Exception {
        String name = "launch-key-" + UUID.randomUUID();
        String material = Ec2CreateKeyPairIntegrationTest.createKeyPair(name, "ed25519", keyFormat)
                .statusCode(200).extract().path("CreateKeyPairResponse.keyMaterial");
        byte[] blob = "pem".equals(keyFormat)
                ? Ec2CreateKeyPairIntegrationTest.sshEd25519Blob(Ec2CreateKeyPairIntegrationTest.openSshEd25519Key(material))
                : PuttyKeyFile.parse(material).publicBlob();

        Instance instance = service.runInstances("us-east-1", "ami-amazonlinux2023", "t3.micro", 1, 1, name,
                List.of(), null, null, List.of(), null, null).getInstances().getFirst();

        ArgumentCaptor<String> injected = ArgumentCaptor.forClass(String.class);
        verify(containerManager).launch(eq(instance), any(), injected.capture(), anyString(), any(), any(), any());
        assertEquals("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob), injected.getValue());
    }
}
